//    OLPOS (derive d'uniCenta oPOS) - synchronisation caisse / API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.sales.restaurant;

import com.unicenta.basic.BasicException;
import com.unicenta.data.loader.Session;
import com.unicenta.pos.sales.DataLogicReceipts;
import com.unicenta.pos.sales.OrderNumbers;
import com.unicenta.pos.ticket.TicketInfo;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Lecture / ecriture des commandes en cours d'une table (table sharedtickets)
 * en verifiant l'IDENTIFIANT DE LA COMMANDE et son NUMERO DE VERSION, pour ne
 * jamais ecraser silencieusement une modification faite ailleurs.
 *
 * <ul>
 *   <li>Identifiant : celui du ticket (UUID unique, {@link TicketInfo#getId()}).
 *       Une demande preparee sur une ANCIENNE commande ne peut jamais modifier
 *       une nouvelle commande ouverte ensuite sur la meme table, meme si les
 *       deux ont la meme version (1, 2...).</li>
 *   <li>Version : rangee dans les proprietes du ticket (aucun changement de
 *       schema). Un ticket ancien sans version est considere en version 1.</li>
 *   <li>Chaque ecriture se fait dans une transaction qui verrouille d'abord la
 *       ligne ({@code UPDATE ... SET NAME = NAME}), relit identifiant et version,
 *       puis ecrit seulement s'ils n'ont pas change ("compare-and-swap").
 *       Cela protege aussi entre plusieurs postes partageant la meme base.</li>
 * </ul>
 *
 * Une instance par Session ; non thread-safe (comme Session).
 */
public final class TableTicketStore {

    public static final String P_VERSION = "order.version";
    public static final String P_UPDATED_BY = "order.updatedBy";
    public static final String P_UPDATED_SOURCE = "order.updatedSource";
    public static final String P_UPDATED_AT = "order.updatedAt";

    /** Commande lue avec son identifiant et sa version. */
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

        /** Identifiant unique de la commande (distinct du numero visible et du numero comptable). */
        public String getOrderId() {
            return ticket.getId();
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

        /** Meme commande (identifiant) a la meme version ? */
        public boolean is(String orderId, long expectedVersion) {
            return orderId != null && orderId.equals(getOrderId()) && version == expectedVersion;
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
        boolean numbered = false;
        try {
            if (lockRow(tableId)) {
                Snapshot cur = load(tableId);
                throw new OrderConflictException("Une commande existe deja sur cette table"
                        + suffix(cur) + ".", 0, cur);
            }
            // numero de commande 1-100 a la premiere sauvegarde avec des articles,
            // dans la meme transaction (annule avec elle en cas d'echec : pas de trou)
            numbered = OrderNumbers.assignInTransaction(ticket, session);
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
                if (numbered) {
                    OrderNumbers.unstamp(ticket);
                }
            }
        }
    }

    /**
     * Enregistre la commande {@code orderId} si personne ne l'a modifiee depuis
     * la version {@code expectedVersion}.
     * @return la nouvelle version
     * @throws OrderConflictException si la commande a ete remplacee par une autre,
     *         si sa version a change ou si elle n'existe plus
     */
    public long update(String tableId, TicketInfo ticket, String orderId, long expectedVersion, String who, String source)
            throws BasicException, OrderConflictException {
        if (orderId == null || !orderId.equals(ticket.getId())) {
            throw new IllegalArgumentException("Identifiant de commande incoherent");
        }
        begin();
        boolean ok = false;
        boolean numbered = false;
        try {
            if (!lockRow(tableId)) {
                throw new OrderConflictException("La commande n'existe plus (encaissee, deplacee ou annulee ailleurs).",
                        expectedVersion, null);
            }
            Snapshot cur = load(tableId);
            if (!orderId.equals(cur.getOrderId())) {
                throw new OrderConflictException("La commande a ete encaissee ou annulee, et une NOUVELLE commande"
                        + " a ete ouverte sur cette table" + suffix(cur) + ".", expectedVersion, cur);
            }
            if (cur.getVersion() != expectedVersion) {
                throw new OrderConflictException("La commande a ete modifiee" + suffix(cur) + ".",
                        expectedVersion, cur);
            }
            long next = expectedVersion + 1;
            numbered = OrderNumbers.assignInTransaction(ticket, session);
            stamp(ticket, next, who, source);
            receipts.updateSharedTicket(tableId, ticket, ticket.getPickupId());
            commit();
            ok = true;
            return next;
        } finally {
            if (!ok) {
                rollbackQuietly();
                if (numbered) {
                    OrderNumbers.unstamp(ticket);
                }
            }
        }
    }

    /**
     * Supprime la commande {@code orderId} (apres encaissement, annulation ou
     * deplacement) si elle est toujours a la version {@code expectedVersion}.
     * Si la table n'a plus de commande, ne fait rien.
     * @throws OrderConflictException si elle a ete modifiee, ou remplacee par une autre commande
     */
    public void delete(String tableId, String orderId, long expectedVersion) throws BasicException, OrderConflictException {
        begin();
        boolean ok = false;
        try {
            if (lockRow(tableId)) {
                Snapshot cur = load(tableId);
                if (!cur.is(orderId, expectedVersion)) {
                    throw new OrderConflictException(differs(cur, orderId) + suffix(cur)
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

    /**
     * Verifie, sans rien ecrire, que la table porte toujours la commande
     * {@code orderId} a la version {@code expectedVersion} (avant un encaissement).
     * @return la commande telle qu'enregistree
     * @throws OrderConflictException sinon
     */
    public Snapshot check(String tableId, String orderId, long expectedVersion) throws BasicException, OrderConflictException {
        Snapshot cur = load(tableId);
        if (cur == null) {
            throw new OrderConflictException("La commande n'existe plus (encaissee, deplacee ou annulee ailleurs).",
                    expectedVersion, null);
        }
        if (!cur.is(orderId, expectedVersion)) {
            throw new OrderConflictException(differs(cur, orderId) + suffix(cur) + ".", expectedVersion, cur);
        }
        return cur;
    }

    private static String differs(Snapshot cur, String orderId) {
        return orderId != null && orderId.equals(cur.getOrderId())
                ? "La commande a ete modifiee" : "Une autre commande a ete ouverte sur cette table";
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
                "UPDATE sharedtickets SET NAME = NAME WHERE ID = ?")) {
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
