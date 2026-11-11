//    OLPOS (derive d'uniCenta oPOS) - numero de commande 1 a 100
//    Licence GPL v3 (voir LICENSE.md).
package com.openbravo.pos.sales;

import com.openbravo.basic.BasicException;
import com.openbravo.data.loader.Session;
import com.openbravo.pos.ticket.TicketInfo;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;

/**
 * Numero de commande visible (1, 2, ... 100, puis 1, 2 ...), commun au ticket
 * client et au bon cuisine.
 *
 * <ul>
 *   <li>Compteur unique en base (ligne {@value #RESOURCE} de la table RESOURCES,
 *       aucun changement de schema), partage par la caisse, l'API et tous les
 *       postes ; il survit aux redemarrages et n'est jamais remis a zero chaque
 *       jour : seul le passage apres {@value #MAX} ramene a 1.</li>
 *   <li>Attribution atomique : la ligne du compteur est verrouillee dans une
 *       transaction ({@code UPDATE ... SET RESTYPE = RESTYPE}) ; deux commandes
 *       simultanees recoivent deux numeros successifs.</li>
 *   <li>Le numero est range dans les proprietes du ticket ({@value #P_NUMBER}),
 *       DISTINCT du numero de ticket comptable (TICKETID) qui ne change pas.</li>
 *   <li>Le numero visible n'identifie pas une commande (il est reutilise) :
 *       l'identifiant interne reste l'ID du ticket / de la table.</li>
 * </ul>
 */
public final class OrderNumbers {

    public static final int MAX = 100;
    public static final String RESOURCE = "olpos.order.counter";
    public static final String P_NUMBER = "order.number";
    public static final String P_ASSIGNED_AT = "order.numberAt";

    private OrderNumbers() {
    }

    /** Numero suivant apres {@code last} : 1..MAX puis retour a 1 (0 ou invalide => 1). */
    public static int next(int last) {
        return last < 1 || last >= MAX ? 1 : last + 1;
    }

    /** Numero de la commande, ou null si elle n'en a pas encore. */
    public static Integer numberOf(TicketInfo t) {
        String v = t == null ? null : t.getProperty(P_NUMBER);
        if (v == null) {
            return null;
        }
        try {
            int n = Integer.parseInt(v.trim());
            return n >= 1 && n <= MAX ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Texte affiche : "COMMANDE N\u00b0 42", ou null. */
    public static String label(TicketInfo t) {
        Integer n = numberOf(t);
        return n == null ? null : "COMMANDE N\u00b0 " + n;
    }

    /** Une commande recoit un numero des qu'elle contient au moins une ligne (ticket de vente normal). */
    public static boolean needsNumber(TicketInfo t) {
        return t != null && t.getLinesCount() > 0 && numberOf(t) == null
                && t.getTicketType() == TicketInfo.RECEIPT_NORMAL;
    }

    static void stamp(TicketInfo t, int n) {
        t.setProperty(P_NUMBER, Integer.toString(n));
        t.setProperty(P_ASSIGNED_AT, Long.toString(System.currentTimeMillis()));
    }

    /** Retire un numero attribue dans une transaction annulee (il n'a pas ete consomme). */
    public static void unstamp(TicketInfo t) {
        t.getProperties().remove(P_NUMBER);
        t.getProperties().remove(P_ASSIGNED_AT);
    }

    /**
     * Attribue un numero si la commande n'en a pas, DANS la transaction en cours
     * de {@code s} (validee ou annulee par l'appelant).
     * @return true si un numero vient d'etre attribue
     */
    public static boolean assignInTransaction(TicketInfo t, Session s) throws BasicException {
        if (!needsNumber(t)) {
            return false;
        }
        stamp(t, nextInTransaction(s));
        return true;
    }

    /**
     * Attribue un numero si besoin, dans sa propre transaction (caisse : avant
     * l'envoi en cuisine ou le paiement d'une commande pas encore enregistree).
     * @return le numero de la commande (null si elle n'en a pas besoin)
     */
    public static Integer ensure(TicketInfo t, Session s) throws BasicException {
        if (needsNumber(t)) {
            boolean own = !s.isTransaction();
            try {
                if (own) {
                    s.begin();
                }
                int n = nextInTransaction(s);
                if (own) {
                    s.commit();
                }
                stamp(t, n);
            } catch (SQLException e) {
                rollback(s, own);
                throw new BasicException(e);
            } catch (BasicException | RuntimeException e) {
                rollback(s, own);
                throw e;
            }
        }
        return numberOf(t);
    }

    private static void rollback(Session s, boolean own) {
        if (own && s.isTransaction()) {
            try {
                s.rollback();
            } catch (SQLException ignored) {
                // transaction abandonnee
            }
        }
    }

    /**
     * Incremente le compteur dans la transaction en cours et renvoie le
     * nouveau numero. La ligne reste verrouillee jusqu'a la fin de la transaction.
     */
    static int nextInTransaction(Session s) throws BasicException {
        try {
            Connection c = s.getConnection();
            if (lock(c) == 0) {
                // premiere utilisation : creation du compteur (protegee contre une creation simultanee)
                Savepoint sp = c.getAutoCommit() ? null : c.setSavepoint();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO RESOURCES(ID, NAME, RESTYPE, CONTENT) VALUES (?, ?, 0, ?)")) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, RESOURCE);
                    ps.setBytes(3, "0".getBytes(StandardCharsets.UTF_8));
                    ps.executeUpdate();
                } catch (SQLException created) {
                    // deja cree par un autre poste au meme instant
                    if (sp != null) {
                        c.rollback(sp);
                    }
                }
                if (lock(c) == 0) {
                    throw new BasicException("Compteur de commandes introuvable");
                }
            }
            int last = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT CONTENT FROM RESOURCES WHERE NAME = ?")) {
                ps.setString(1, RESOURCE);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        byte[] b = rs.getBytes(1);
                        try {
                            last = b == null ? 0 : Integer.parseInt(new String(b, StandardCharsets.UTF_8).trim());
                        } catch (NumberFormatException e) {
                            last = 0;
                        }
                    }
                }
            }
            int n = next(last);
            try (PreparedStatement ps = c.prepareStatement("UPDATE RESOURCES SET CONTENT = ? WHERE NAME = ?")) {
                ps.setBytes(1, Integer.toString(n).getBytes(StandardCharsets.UTF_8));
                ps.setString(2, RESOURCE);
                ps.executeUpdate();
            }
            return n;
        } catch (SQLException e) {
            throw new BasicException(e);
        }
    }

    private static int lock(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE RESOURCES SET RESTYPE = RESTYPE WHERE NAME = ?")) {
            ps.setString(1, RESOURCE);
            return ps.executeUpdate();
        }
    }
}
