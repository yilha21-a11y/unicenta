package com.unicenta.pos.portal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Ventes FICTIVES ecrites dans une base de test, avec les memes tables que la
 * caisse (receipts, tickets, ticketlines, taxlines, payments, closedcash).
 * Taxe '001' = 10 % dans la base de test, '000' = exoneree.
 */
public final class PortalTestData {

    private PortalTestData() {
    }

    /** Ligne : produit, quantite, prix HT unitaire, taxe ('001' ou '000'), taux. */
    public static Object[] line(String product, double units, double price, String taxId, double rate) {
        return new Object[] {product, units, price, taxId, rate};
    }

    public static String openCash(Connection c, String host, int seq, long start, Long end) throws Exception {
        String money = UUID.randomUUID().toString();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO closedcash (money, host, hostsequence, datestart,"
                + " dateend, nosales) VALUES (?, ?, ?, ?, ?, 0)")) {
            ps.setString(1, money);
            ps.setString(2, host);
            ps.setInt(3, seq);
            ps.setTimestamp(4, new Timestamp(start));
            ps.setTimestamp(5, end == null ? null : new Timestamp(end));
            ps.executeUpdate();
        }
        return money;
    }

    /**
     * Ticket encaisse (type 0) ou remboursement (type 1, quantites negatives).
     * @return identifiant du ticket
     */
    public static String sale(Connection c, String money, long ts, int ticketNo, int type, String personId,
            String payment, Integer orderNumber, String place, Object[]... lines) throws Exception {
        String id = UUID.randomUUID().toString();
        String attrs = null;
        if (orderNumber != null || place != null) {
            StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>"
                    + "<!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\"><properties>");
            if (orderNumber != null) {
                b.append("<entry key=\"order.number\">").append(orderNumber).append("</entry>");
            }
            if (place != null) {
                b.append("<entry key=\"olpos.place\">").append(place).append("</entry>");
            }
            attrs = b.append("</properties>").toString();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO receipts (id, money, datenew, attributes, person)"
                + " VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, money);
            ps.setTimestamp(3, new Timestamp(ts));
            ps.setBytes(4, attrs == null ? null : attrs.getBytes("UTF-8"));
            ps.setString(5, personId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO tickets (id, tickettype, ticketid, person, customer,"
                + " status) VALUES (?, ?, ?, ?, NULL, 0)")) {
            ps.setString(1, id);
            ps.setInt(2, type);
            ps.setInt(3, ticketNo);
            ps.setString(4, personId);
            ps.executeUpdate();
        }
        double base10 = 0;
        double base0 = 0;
        int n = 0;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO ticketlines (ticket, line, product, units, price,"
                + " taxid) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (Object[] l : lines) {
                double units = (Double) l[1] * (type == 1 ? -1 : 1);
                double price = (Double) l[2];
                ps.setString(1, id);
                ps.setInt(2, n++);
                ps.setString(3, (String) l[0]);
                ps.setDouble(4, units);
                ps.setDouble(5, price);
                ps.setString(6, (String) l[3]);
                ps.executeUpdate();
                if ("001".equals(l[3])) {
                    base10 += units * price;
                } else {
                    base0 += units * price;
                }
            }
        }
        double total = 0;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO taxlines (id, receipt, taxid, base, amount)"
                + " VALUES (?, ?, ?, ?, ?)")) {
            if (base10 != 0) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, id);
                ps.setString(3, "001");
                ps.setDouble(4, base10);
                ps.setDouble(5, base10 * 0.10);
                ps.executeUpdate();
                total += base10 * 1.10;
            }
            if (base0 != 0) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, id);
                ps.setString(3, "000");
                ps.setDouble(4, base0);
                ps.setDouble(5, 0);
                ps.executeUpdate();
                total += base0;
            }
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO payments (id, receipt, payment, total, tip, notes)"
                + " VALUES (?, ?, ?, ?, 0, ?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, id);
            ps.setString(3, payment);
            ps.setDouble(4, Math.round(total * 100) / 100.0);
            ps.setString(5, "NOTE-PRIVEE-NE-PAS-ENVOYER");
            ps.executeUpdate();
        }
        return id;
    }
}
