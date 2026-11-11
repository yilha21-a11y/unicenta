//    OLPOS (derive d'uniCenta oPOS) - synchronisation caisse / API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.openbravo.pos.sales.restaurant;

import com.openbravo.basic.BasicException;
import com.openbravo.data.loader.Session;
import com.openbravo.pos.sales.DataLogicReceipts;
import com.openbravo.pos.ticket.TicketInfo;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Lecture / ecriture des commandes en cours d'une table (table SHAREDTICKETS)
 * avec un NUMERO DE VERSION, pour ne jamais ecraser silencieusement une
 * modification faite ailleurs.
 *
 * <p>La version et l'auteur de la derniere modification sont ranges dans les
 * proprietes du ticket (deja enregistrees par la caisse) : aucune modification
 * du schema de la base n'est necessaire. Un ticket ancien sans version est
 * considere en version 1.</p>
 *
 * <p>Chaque ecriture se fait dans une transaction qui verrouille d'abord la
 * ligne SHAREDTICKETS ({@code UPDATE ... SET NAME = NAME}), relit la version,
 * puis ecrit seulement si elle n'a pas change ("compare-and-swap"). Cela
 * protege aussi entre plusieurs postes partageant la meme base.</p>
 *
 * Une instance par Session ; non thread-safe (comme Session).
 */
public final class TableTicketStore {

    public static final String P_VERSION = "order.version";
    public static final String P_UPDATED_BY = "order.updatedBy";
    public static final String P_UPDATED_SOURCE = "order.updatedSource";
    public static final String P_UPDATED_AT = "order.updatedAt";

    /** Commande lue avec sa version. */
    public static final class Snapshot {
        private final TicketInfo ticket;
        private final long version;

        Snapshot(TicketInfo ticket, long version) {
            this.ticket = ticket;
            this.version = version;
        }

        public TicketInfo getTicket() {
            return ticket;
        }

        public long getVersion() {
            return version;
        }

        public String getUpdatedBy() {
            return ticket.getProperty(P_UPDATED_BY);
        }

        public String getUpdatedSource() {
            return ticket.getProperty(P_UPDATED_SOURCE);
        }

        public long getUpdatedAt() {
            try {
                return Long.parseLong(ticket.getProperty(P_UPDATED_AT, "0"));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** Exemple : "par Serveur Test (mobile) a 12:34". */
        public String describeLastChange() {
            StringBuilder sb = new StringBuilder();
            if (getUpdatedBy() != null) {
                sb.append("par ").append(getUpdatedBy());
                if (getUpdatedSource() != null) {
                    sb.append(" (").append(getUpdatedSource()).append(")");
                }
            }
            long at = getUpdatedAt();
            if (at > 0) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append("a ").append(new SimpleDateFormat("HH:mm:ss").format(new Date(at)));
            }
            return sb.toString();
        }
    }

    private final Session session;
    private final DataLogicReceipts receipts;

    public TableTicketStore(Session session) {
        this.session = session;
        this.receipts = new DataLogicReceipts();
        this.receipts.init(session);
    }

    /** Version d'un ticket (1 s'il n'en a pas encore). */
    public static long versionOf(TicketInfo t) {
        try {
            return Long.parseLong(t.getProperty(P_VERSION, "1"));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Commande en cours de la table, ou null s'il n'y en a pas. */
    public Snapshot load(String tableId) throws BasicException {
        TicketInfo t = receipts.getSharedTicket(tableId);
        return t == null ? null : new Snapshot(t, versionOf(t));
    }

    /**
     * Cree la commande d'une table libre.
     * @return la nouvelle version (1)
     * @throws OrderConflictException si une commande existe deja pour cette table
     */
    public long create(String tableId, TicketInfo ticket, String who, String source)
            throws BasicException, OrderConflictException {
        begin();
        boolean ok = false;
        try {
            if (lockRow(tableId)) {
                Snapshot cur = load(tableId);
                throw new OrderConflictException("Une commande existe deja sur cette table"
                        + suffix(cur) + ".", 0, cur);
            }
            stamp(ticket, 1, who, source);
            try {
                receipts.insertSharedTicket(tableId, ticket, ticket.getPickupId());
            } catch (BasicException e) {
                // creation simultanee par un autre poste (cle primaire deja prise)
                rollbackQuietly();
                Snapshot cur = load(tableId);
                if (cur != null) {
                    throw new OrderConflictException("Une commande vient d'etre creee sur cette table"
                            + suffix(cur) + ".", 0, cur);
                }
                throw e;
            }
            commit();
            ok = true;
            return 1;
        } finally {
            if (!ok) {
                rollbackQuietly();
            }
        }
    }

    /**
     * Enregistre la commande si personne ne l'a modifiee depuis la version
     * {@code expectedVersion}.
     * @return la nouvelle version
     * @throws OrderConflictException si la version a change ou si la commande n'existe plus
     */
    public long update(String tableId, TicketInfo ticket, long expectedVersion, String who, String source)
            throws BasicException, OrderConflictException {
        begin();
        boolean ok = false;
        try {
            if (!lockRow(tableId)) {
                throw new OrderConflictException("La commande n'existe plus (encaissee, deplacee ou annulee ailleurs).",
                        expectedVersion, null);
            }
            Snapshot cur = load(tableId);
            if (cur.getVersion() != expectedVersion) {
                throw new OrderConflictException("La commande a ete modifiee" + suffix(cur) + ".",
                        expectedVersion, cur);
            }
            long next = expectedVersion + 1;
            stamp(ticket, next, who, source);
            receipts.updateSharedTicket(tableId, ticket, ticket.getPickupId());
            commit();
            ok = true;
            return next;
        } finally {
            if (!ok) {
                rollbackQuietly();
            }
        }
    }

    /**
     * Supprime la commande (apres encaissement, annulation ou deplacement)
     * si elle est toujours a la version {@code expectedVersion}.
     * Si elle n'existe deja plus, ne fait rien.
     * @throws OrderConflictException si elle a ete modifiee entre-temps
     */
    public void delete(String tableId, long expectedVersion) throws BasicException, OrderConflictException {
        begin();
        boolean ok = false;
        try {
            if (lockRow(tableId)) {
                Snapshot cur = load(tableId);
                if (cur.getVersion() != expectedVersion) {
                    throw new OrderConflictException("La commande a ete modifiee" + suffix(cur)
                            + " : elle n'a pas ete supprimee.", expectedVersion, cur);
                }
                receipts.deleteSharedTicket(tableId);
            }
            commit();
            ok = true;
        } finally {
            if (!ok) {
                rollbackQuietly();
            }
        }
    }

    private static String suffix(Snapshot cur) {
        if (cur == null) {
            return "";
        }
        String d = cur.describeLastChange();
        return d.isEmpty() ? "" : " " + d;
    }

    private static void stamp(TicketInfo t, long version, String who, String source) {
        t.setProperty(P_VERSION, Long.toString(version));
        if (who != null) {
            t.setProperty(P_UPDATED_BY, who);
        }
        if (source != null) {
            t.setProperty(P_UPDATED_SOURCE, source);
        }
        t.setProperty(P_UPDATED_AT, Long.toString(System.currentTimeMillis()));
    }

    /** Verrouille la ligne de la table jusqu'a la fin de la transaction ; false si elle n'existe pas. */
    private boolean lockRow(String tableId) throws BasicException {
        try (PreparedStatement ps = session.getConnection().prepareStatement(
                "UPDATE SHAREDTICKETS SET NAME = NAME WHERE ID = ?")) {
            ps.setString(1, tableId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new BasicException(e);
        }
    }

    private void begin() throws BasicException {
        try {
            session.begin();
        } catch (SQLException e) {
            throw new BasicException(e);
        }
    }

    private void commit() throws BasicException {
        try {
            session.commit();
        } catch (SQLException e) {
            throw new BasicException(e);
        }
    }

    private void rollbackQuietly() {
        if (session.isTransaction()) {
            try {
                session.rollback();
            } catch (SQLException e) {
                // rien de plus a faire : la transaction est abandonnee
            }
        }
    }
}
