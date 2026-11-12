//    OLPOS (derive d'uniCenta oPOS) - donnees envoyees au portail Patron
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.portal;

import com.unicenta.pos.api.Json;
import com.unicenta.pos.ticket.TicketInfo;
import com.unicenta.pos.ticket.TicketLineInfo;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Lecture des ventes ENCAISSEES (memes tables que les rapports OLPOS), des
 * clotures de caisse et des commandes en cours.
 *
 * <p>Donnees envoyees : montants, produits, categories, employes (nom),
 * moyens de paiement, table et numero de commande. JAMAIS : clients (nom,
 * adresse, carte), mots de passe, notes libres de paiement.</p>
 */
public final class PortalExporter {

    static final int MAX_TICKETS = 300;

    /** Fournit une connexion valide (MySQL : reconnexion si le serveur a coupe). */
    public interface ConnectionSource {
        Connection get() throws SQLException;
    }

    private final ConnectionSource source;

    public PortalExporter(Connection c) {
        this(() -> c);
    }

    public PortalExporter(ConnectionSource source) {
        this.source = source;
    }

    private Connection c() throws SQLException {
        return source.get();
    }

    /** Tickets encaisses apres le curseur (date, identifiant), dans l'ordre. */
    public List<Object> ticketsAfter(long cursorTs, String cursorId) throws SQLException {
        List<Object> out = new ArrayList<>();
        try (PreparedStatement ps = c().prepareStatement("SELECT r.ID, r.DATENEW, r.MONEY, r.ATTRIBUTES, t.TICKETTYPE,"
                + " t.TICKETID, t.PERSON, p.NAME FROM receipts r JOIN tickets t ON r.ID = t.ID"
                + " LEFT JOIN people p ON t.PERSON = p.ID WHERE r.DATENEW > ? OR (r.DATENEW = ? AND r.ID > ?)"
                + " ORDER BY r.DATENEW, r.ID")) {
            Timestamp ts = new Timestamp(cursorTs);
            ps.setTimestamp(1, ts);
            ps.setTimestamp(2, ts);
            ps.setString(3, cursorId == null ? "" : cursorId);
            ps.setMaxRows(MAX_TICKETS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Properties attr = props(rs.getBytes(4));
                    Map<String, Object> t = Json.obj("id", rs.getString(1), "ts", rs.getTimestamp(2).getTime(),
                            "money", rs.getString(3), "type", (long) rs.getInt(5), "ticketNo", (long) rs.getInt(6),
                            "person", rs.getString(7), "personName", rs.getString(8));
                    String on = attr.getProperty("order.number");
                    t.put("orderNumber", on == null ? null : (Object) Long.parseLong(on.trim()));
                    t.put("place", attr.getProperty("olpos.place"));
                    out.add(t);
                }
            }
        }
        for (Object o : out) {
            fill(asMap(o));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private void fill(Map<String, Object> t) throws SQLException {
        String id = (String) t.get("id");
        boolean refund = ((Long) t.get("type")) == 1L;
        List<Object> lines = new ArrayList<>();
        double discounts = 0;
        try (PreparedStatement ps = c().prepareStatement("SELECT tl.LINE, tl.PRODUCT, tl.UNITS, tl.PRICE, tl.ATTRIBUTES,"
                + " pr.NAME, ca.NAME, tx.RATE FROM ticketlines tl LEFT JOIN products pr ON tl.PRODUCT = pr.ID"
                + " LEFT JOIN categories ca ON pr.CATEGORY = ca.ID LEFT JOIN taxes tx ON tl.TAXID = tx.ID"
                + " WHERE tl.TICKET = ? ORDER BY tl.LINE")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Properties a = props(rs.getBytes(5));
                    double units = rs.getDouble(3);
                    double price = rs.getDouble(4);
                    String name = rs.getString(6) != null ? rs.getString(6) : a.getProperty("product.name", "Article");
                    boolean discount = !refund && price < 0;
                    if (discount) {
                        discounts += -units * price;
                    }
                    lines.add(Json.obj("line", (long) rs.getInt(1), "productId", rs.getString(2), "name", name,
                            "category", rs.getString(7), "units", units, "price", price, "total", units * price,
                            "taxRate", rs.getDouble(8), "discount", discount));
                }
            }
        }
        double base = 0;
        double tax = 0;
        List<Object> taxes = new ArrayList<>();
        try (PreparedStatement ps = c().prepareStatement("SELECT tx.NAME, tl.BASE, tl.AMOUNT FROM taxlines tl"
                + " LEFT JOIN taxes tx ON tl.TAXID = tx.ID WHERE tl.RECEIPT = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    base += rs.getDouble(2);
                    tax += rs.getDouble(3);
                    taxes.add(Json.obj("name", rs.getString(1), "base", rs.getDouble(2), "amount", rs.getDouble(3)));
                }
            }
        }
        List<Object> payments = new ArrayList<>();
        try (PreparedStatement ps = c().prepareStatement("SELECT PAYMENT, TOTAL, TIP FROM payments WHERE RECEIPT = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    payments.add(Json.obj("type", rs.getString(1), "total", rs.getDouble(2), "tip", rs.getDouble(3)));
                }
            }
        }
        t.put("lines", lines);
        t.put("taxes", taxes);
        t.put("payments", payments);
        t.put("base", round(base));
        t.put("tax", round(tax));
        t.put("total", round(base + tax));
        t.put("discounts", round(discounts));
    }

    /** Clotures de caisse terminees apres {@code afterEnd}, plus la caisse en cours. */
    public List<Object> closures(long afterEnd) throws SQLException {
        List<Object> out = new ArrayList<>();
        try (PreparedStatement ps = c().prepareStatement("SELECT MONEY, HOST, HOSTSEQUENCE, DATESTART, DATEEND, NOSALES"
                + " FROM closedcash WHERE DATEEND IS NULL OR DATEEND > ? ORDER BY DATESTART")) {
            ps.setTimestamp(1, new Timestamp(afterEnd));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp end = rs.getTimestamp(5);
                    out.add(Json.obj("money", rs.getString(1), "host", rs.getString(2), "seq", (long) rs.getInt(3),
                            "start", rs.getTimestamp(4).getTime(), "end", end == null ? null : (Object) end.getTime(),
                            "noSales", (long) rs.getInt(6)));
                }
            }
        }
        return out;
    }

    /** Commandes en cours (tables ouvertes, non encaissees). */
    public List<Object> openOrders() throws SQLException {
        List<Object> out = new ArrayList<>();
        try (PreparedStatement ps = c().prepareStatement("SELECT s.ID, s.CONTENT, p.NAME FROM sharedtickets s"
                + " LEFT JOIN places p ON s.ID = p.ID");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TicketInfo t = ticket(rs.getBytes(2));
                if (t == null) {
                    continue;
                }
                List<Object> lines = new ArrayList<>();
                double total = 0;
                for (TicketLineInfo l : t.getLines()) {
                    total += l.getValue();
                    lines.add(Json.obj("name", l.getProductName(), "units", l.getMultiply(), "total", round(l.getValue())));
                }
                String on = t.getProperty("order.number");
                out.add(Json.obj("place", rs.getString(3) != null ? rs.getString(3) : rs.getString(1),
                        "orderNumber", on == null ? null : (Object) Long.parseLong(on.trim()),
                        "opened", t.getDate() == null ? null : (Object) t.getDate().getTime(),
                        "employee", t.getUser() == null ? null : t.getUser().getName(),
                        "lines", lines, "total", round(total)));
            }
        }
        return out;
    }

    private static TicketInfo ticket(byte[] b) {
        if (b == null) {
            return null;
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(b))) {
            return (TicketInfo) in.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static Properties props(byte[] b) {
        Properties p = new Properties();
        if (b != null) {
            try {
                p.loadFromXML(new ByteArrayInputStream(b));
            } catch (Exception e) {
                // pas d'attributs lisibles
            }
        }
        return p;
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
