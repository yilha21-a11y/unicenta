//    OLPOS Portail Patron - statistiques (lecture seule)
//    Licence GPL v3 (voir ../LICENSE.md).
package com.olpos.portal;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Statistiques d'UN restaurant (celui de la session) sur une periode.
 *
 * <p>Memes sources que les rapports OLPOS : HT et taxes = lignes de taxes du
 * ticket (rapport "Taxes"), encaissements = paiements (rapport "Clotures"),
 * produits = lignes de ticket (quantite x prix HT, rapport "Ventes par
 * produit"). TTC = HT + taxes.</p>
 *
 * <ul>
 *   <li>Ventes : tickets normaux ; remboursements : tickets de remboursement
 *       (montants negatifs), presentes a part ; net = ventes + remboursements.</li>
 *   <li>Remises : lignes de remise (prix negatif) des tickets normaux, en HT,
 *       affichees en valeur positive ; elles sont deja deduites du HT.</li>
 *   <li>Jours et heures : fuseau horaire de la caisse.</li>
 * </ul>
 */
public final class StatsService {

    private final PortalDb db;

    public StatsService(PortalDb db) {
        this.db = db;
    }

    /** Tickets (contenu complet) du restaurant sur [from, to[. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tickets(String rid, long from, long to) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT json FROM tickets WHERE rid = ? AND ts >= ? AND ts < ? ORDER BY ts",
                rid, from, to)) {
            try {
                out.add((Map<String, Object>) Json.parse((String) r.get("json")));
            } catch (Json.ParseException e) {
                // ticket illisible : ignore (ne devrait pas arriver)
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> summary(String rid, long from, long to, ZoneId zone) throws SQLException {
        List<Map<String, Object>> tickets = tickets(rid, from, to);
        double salesBase = 0, salesTax = 0, refundBase = 0, refundTax = 0, discounts = 0, paid = 0;
        int sales = 0, refunds = 0;
        Map<String, double[]> payments = new TreeMap<>();
        Map<String, double[]> products = new LinkedHashMap<>();
        Map<String, double[]> categories = new LinkedHashMap<>();
        Map<String, double[]> employees = new LinkedHashMap<>();
        Map<String, double[]> days = new TreeMap<>();
        double[][] hours = new double[24][2];
        for (Map<String, Object> t : tickets) {
            boolean refund = num(t.get("type")) == 1;
            double base = num(t.get("base"));
            double tax = num(t.get("tax"));
            if (refund) {
                refunds++;
                refundBase += base;
                refundTax += tax;
            } else {
                sales++;
                salesBase += base;
                salesTax += tax;
                discounts += num(t.get("discounts"));
            }
            double ttc = base + tax;
            ZonedDateTime when = Instant.ofEpochMilli((long) num(t.get("ts"))).atZone(zone);
            add(days, when.toLocalDate().toString(), refund ? 0 : 1, ttc);
            hours[when.getHour()][0] += refund ? 0 : 1;
            hours[when.getHour()][1] += ttc;
            add(employees, str(t.get("personName"), "?"), refund ? 0 : 1, ttc);
            for (Object o : list(t.get("payments"))) {
                Map<String, Object> p = (Map<String, Object>) o;
                add(payments, str(p.get("type"), "?"), 1, num(p.get("total")));
                paid += num(p.get("total"));
            }
            for (Object o : list(t.get("lines"))) {
                Map<String, Object> l = (Map<String, Object>) o;
                if (Boolean.TRUE.equals(l.get("discount"))) {
                    continue;   // les remises sont comptees a part
                }
                double lineBase = num(l.get("total"));
                double lineTtc = lineBase * (1 + num(l.get("taxRate")));
                add(products, str(l.get("name"), "?"), num(l.get("units")), lineBase, lineTtc);
                add(categories, str(l.get("category"), "Sans categorie"), num(l.get("units")), lineBase, lineTtc);
            }
        }
        int count = sales;
        double salesTtc = salesBase + salesTax;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("to", to);
        m.put("ticketCount", sales);
        m.put("refundCount", refunds);
        m.put("sales", Json.obj("base", r2(salesBase), "tax", r2(salesTax), "total", r2(salesTtc)));
        m.put("refunds", Json.obj("base", r2(refundBase), "tax", r2(refundTax), "total", r2(refundBase + refundTax)));
        m.put("net", Json.obj("base", r2(salesBase + refundBase), "tax", r2(salesTax + refundTax),
                "total", r2(salesTtc + refundBase + refundTax)));
        m.put("discounts", r2(discounts));
        m.put("averageBasket", count == 0 ? 0.0 : r2(salesTtc / count));
        m.put("paymentsTotal", r2(paid));
        m.put("payments", rows(payments, "type", "count", "total"));
        m.put("products", sorted(rows(products, "name", "quantity", "base", "total"), "total"));
        m.put("categories", sorted(rows(categories, "name", "quantity", "base", "total"), "total"));
        m.put("employees", sorted(rows(employees, "name", "tickets", "total"), "total"));
        m.put("days", rows(days, "day", "tickets", "total"));
        List<Object> h = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            if (hours[i][0] != 0 || hours[i][1] != 0) {
                h.add(Json.obj("hour", (long) i, "tickets", (long) hours[i][0], "total", r2(hours[i][1])));
            }
        }
        m.put("hours", h);
        return m;
    }

    /** Historique des tickets (le plus recent d'abord), sans le detail des lignes. */
    public List<Object> ticketList(String rid, long from, long to, int limit) throws SQLException {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT id, ticket_no, ticket_type, ts, person_name, place, order_number,"
                + " base, tax, total FROM tickets WHERE rid = ? AND ts >= ? AND ts < ? ORDER BY ts DESC", rid, from, to)) {
            if (out.size() >= limit) {
                break;
            }
            out.add(Json.obj("id", r.get("id"), "ticketNo", ((Number) r.get("ticket_no")).longValue(),
                    "type", ((Number) r.get("ticket_type")).longValue(), "ts", ((Number) r.get("ts")).longValue(),
                    "employee", r.get("person_name"), "place", r.get("place"),
                    "orderNumber", r.get("order_number") == null ? null : ((Number) r.get("order_number")).longValue(),
                    "base", r2(((Number) r.get("base")).doubleValue()), "tax", r2(((Number) r.get("tax")).doubleValue()),
                    "total", r2(((Number) r.get("base")).doubleValue() + ((Number) r.get("tax")).doubleValue())));
        }
        return out;
    }

    /** Un ticket avec ses articles ; null s'il n'appartient pas a CE restaurant. */
    public Object ticket(String rid, String id) throws SQLException {
        Map<String, Object> r = db.one("SELECT json FROM tickets WHERE rid = ? AND id = ?", rid, id);
        if (r == null) {
            return null;
        }
        try {
            return Json.parse((String) r.get("json"));
        } catch (Json.ParseException e) {
            return null;
        }
    }

    /** Clotures de caisse avec encaissements par moyen de paiement. */
    @SuppressWarnings("unchecked")
    public List<Object> closures(String rid, long from, long to) throws SQLException {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> c : db.query("SELECT json FROM closures WHERE rid = ? AND date_start < ?"
                + " AND (date_end IS NULL OR date_end >= ?) ORDER BY date_start DESC", rid, to, from)) {
            try {
                Map<String, Object> cl = (Map<String, Object>) Json.parse((String) c.get("json"));
                Map<String, double[]> pay = new TreeMap<>();
                double total = 0;
                int n = 0;
                for (Map<String, Object> t : db.query("SELECT json FROM tickets WHERE rid = ? AND money = ?", rid,
                        cl.get("money")).stream().map(x -> {
                            try {
                                return (Map<String, Object>) Json.parse((String) x.get("json"));
                            } catch (Json.ParseException e) {
                                return new LinkedHashMap<String, Object>();
                            }
                        }).toArray(Map[]::new)) {
                    n++;
                    for (Object o : list(t.get("payments"))) {
                        Map<String, Object> p = (Map<String, Object>) o;
                        add(pay, str(p.get("type"), "?"), 1, num(p.get("total")));
                        total += num(p.get("total"));
                    }
                }
                cl.put("tickets", (long) n);
                cl.put("paymentsTotal", r2(total));
                cl.put("payments", rows(pay, "type", "count", "total"));
                out.add(cl);
            } catch (Json.ParseException e) {
                // ignoree
            }
        }
        return out;
    }

    /** Commandes en cours (tables ouvertes, non encaissees) : derniere photo envoyee par la caisse. */
    public Map<String, Object> openOrders(String rid) throws SQLException {
        Map<String, Object> r = db.one("SELECT ts, json FROM open_orders WHERE rid = ?", rid);
        if (r == null) {
            return Json.obj("ts", null, "orders", new ArrayList<Object>());
        }
        try {
            return Json.obj("ts", ((Number) r.get("ts")).longValue(), "orders", Json.parse((String) r.get("json")));
        } catch (Json.ParseException e) {
            return Json.obj("ts", null, "orders", new ArrayList<Object>());
        }
    }

    /** Debut de periode : jour / semaine (lundi) / mois, dans le fuseau du restaurant. */
    public static long[] period(String kind, String fromDay, String toDay, ZoneId zone, long now) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate a;
        LocalDate b;
        switch (kind == null ? "day" : kind) {
            case "week":
                a = today.minusDays(today.getDayOfWeek().getValue() - 1);
                b = a.plusDays(7);
                break;
            case "month":
                a = today.withDayOfMonth(1);
                b = a.plusMonths(1);
                break;
            case "custom":
                a = LocalDate.parse(fromDay);
                b = LocalDate.parse(toDay).plusDays(1);   // date de fin incluse
                break;
            default:
                a = today;
                b = today.plusDays(1);
                break;
        }
        return new long[] {a.atStartOfDay(zone).toInstant().toEpochMilli(), b.atStartOfDay(zone).toInstant().toEpochMilli()};
    }

    // ------------------------------------------------------------------ outils

    private static void add(Map<String, double[]> m, String k, double... v) {
        double[] a = m.get(k);
        if (a == null) {
            a = new double[v.length];
            m.put(k, a);
        }
        for (int i = 0; i < v.length; i++) {
            a[i] += v[i];
        }
    }

    private static List<Object> rows(Map<String, double[]> m, String key, String... cols) {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : m.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put(key, e.getKey());
            for (int i = 0; i < cols.length; i++) {
                double v = e.getValue()[i];
                r.put(cols[i], cols[i].equals("count") || cols[i].equals("tickets") ? (Object) (long) v : (Object) r2(v));
            }
            out.add(r);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> sorted(List<Object> l, String by) {
        l.sort(Comparator.comparingDouble((Object o) -> -num(((Map<String, Object>) o).get(by))));
        return l;
    }

    static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    static String str(Object o, String def) {
        return o == null || String.valueOf(o).isEmpty() ? def : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }
}
