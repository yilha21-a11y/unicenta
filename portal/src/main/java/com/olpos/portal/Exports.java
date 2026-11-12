//    OLPOS Portail Patron - exports CSV et PDF
//    Licence GPL v3 (voir ../LICENSE.md).
package com.olpos.portal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Exports du rapport de periode : CSV (Excel, separateur ;) et PDF (texte, sans bibliotheque externe). */
public final class Exports {

    private Exports() {
    }

    /** Lignes du rapport (titre, puis sections) communes au CSV et au PDF. */
    @SuppressWarnings("unchecked")
    static List<String[]> reportRows(Map<String, Object> restaurant, Map<String, Object> s, ZoneId zone, Money money) {
        DateTimeFormatter d = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zone);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"OLPOS - rapport de ventes", String.valueOf(restaurant.get("name"))});
        rows.add(new String[] {"Periode", d.format(Instant.ofEpochMilli(((Number) s.get("from")).longValue())) + " -> "
                + d.format(Instant.ofEpochMilli(((Number) s.get("to")).longValue())) + " (" + zone.getId() + ")"});
        rows.add(new String[] {"Devise", money.code});
        rows.add(new String[] {});
        Map<String, Object> sales = (Map<String, Object>) s.get("sales");
        Map<String, Object> refunds = (Map<String, Object>) s.get("refunds");
        Map<String, Object> net = (Map<String, Object>) s.get("net");
        rows.add(new String[] {"Chiffre d'affaires", "HT", "Taxes", "TTC"});
        rows.add(new String[] {"Ventes", money.f(sales.get("base")), money.f(sales.get("tax")), money.f(sales.get("total"))});
        rows.add(new String[] {"Remboursements", money.f(refunds.get("base")), money.f(refunds.get("tax")),
            money.f(refunds.get("total"))});
        rows.add(new String[] {"Net", money.f(net.get("base")), money.f(net.get("tax")), money.f(net.get("total"))});
        rows.add(new String[] {"Remises (HT, deja deduites)", money.f(s.get("discounts"))});
        rows.add(new String[] {"Tickets", String.valueOf(s.get("ticketCount")), "Remboursements",
            String.valueOf(s.get("refundCount"))});
        rows.add(new String[] {"Panier moyen (TTC)", money.f(s.get("averageBasket"))});
        section(rows, "Paiements", new String[] {"Moyen", "Nombre", "Montant"}, (List<Object>) s.get("payments"),
                new String[] {"type", "count", "total"}, money, 2);
        section(rows, "Produits", new String[] {"Produit", "Quantite", "HT", "TTC"}, (List<Object>) s.get("products"),
                new String[] {"name", "quantity", "base", "total"}, money, 2);
        section(rows, "Categories", new String[] {"Categorie", "Quantite", "HT", "TTC"}, (List<Object>) s.get("categories"),
                new String[] {"name", "quantity", "base", "total"}, money, 2);
        section(rows, "Employes", new String[] {"Employe", "Tickets", "TTC"}, (List<Object>) s.get("employees"),
                new String[] {"name", "tickets", "total"}, money, 2);
        section(rows, "Jours", new String[] {"Jour", "Tickets", "TTC"}, (List<Object>) s.get("days"),
                new String[] {"day", "tickets", "total"}, money, 2);
        section(rows, "Heures", new String[] {"Heure", "Tickets", "TTC"}, (List<Object>) s.get("hours"),
                new String[] {"hour", "tickets", "total"}, money, 2);
        return rows;
    }

    /** Libelle francais des moyens de paiement d'OLPOS (comme a la caisse). */
    static String paymentName(String t) {
        switch (t) {
            case "cash": return "Especes";
            case "magcard": return "Carte bancaire";
            case "cheque": return "Cheque";
            case "paperin": return "Bon / ticket";
            case "free": return "Offert";
            case "debt": return "Ardoise";
            case "cashrefund": return "Remboursement especes";
            case "magcardrefund": return "Remboursement carte";
            case "chequerefund": return "Remboursement cheque";
            case "paperout": return "Remboursement bon";
            case "voucherin": return "Bon d'achat";
            case "voucherout": return "Remboursement bon d'achat";
            case "bank": return "Virement";
            default: return t;
        }
    }

    @SuppressWarnings("unchecked")
    private static void section(List<String[]> rows, String title, String[] head, List<Object> data, String[] keys,
            Money money, int firstMoney) {
        rows.add(new String[] {});
        rows.add(new String[] {title.toUpperCase()});
        rows.add(head);
        for (Object o : data) {
            Map<String, Object> m = (Map<String, Object>) o;
            String[] r = new String[keys.length];
            for (int i = 0; i < keys.length; i++) {
                Object v = m.get(keys[i]);
                boolean isMoney = i >= firstMoney || keys[i].equals("total") || keys[i].equals("base");
                r[i] = isMoney && v instanceof Number && !keys[i].equals("count") && !keys[i].equals("tickets")
                        && !keys[i].equals("quantity") ? money.f(v)
                        : keys[i].equals("type") ? paymentName(String.valueOf(v)) : String.valueOf(v);
            }
            rows.add(r);
        }
    }

    public static byte[] csv(List<String[]> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        for (String[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                if (i > 0) {
                    sb.append(';');
                }
                sb.append(cell(r[i]));
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String cell(String s) {
        if (s == null) {
            return "";
        }
        if (s.startsWith("=") || s.startsWith("+") || s.startsWith("@") || s.startsWith("\t") || s.startsWith("\r")
                || (s.startsWith("-") && !s.matches("-[0-9][0-9 .,\u00a0\u202f]*[^0-9=+@(-]{0,4}"))) {
            s = "'" + s;   // jamais interprete comme formule par le tableur
        }
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** PDF A4 simple (Helvetica, WinAnsi), une ligne de rapport par ligne de texte. */
    public static byte[] pdf(List<String[]> rows) {
        List<String> lines = new ArrayList<>();
        for (String[] r : rows) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.length; i++) {
                String c = r[i] == null ? "" : r[i];
                if (i == 0) {
                    sb.append(pad(c, 38));
                } else {
                    sb.append(padLeft(c, 16));
                }
            }
            lines.add(sb.toString());
        }
        int perPage = 60;
        List<String> pages = new ArrayList<>();
        for (int p = 0; p * perPage < Math.max(1, lines.size()); p++) {
            StringBuilder content = new StringBuilder("BT /F1 8 Tf 10 TL 36 806 Td\n");
            for (int i = p * perPage; i < Math.min(lines.size(), (p + 1) * perPage); i++) {
                content.append('(').append(esc(lines.get(i))).append(") Tj T*\n");
            }
            content.append("ET\n");
            pages.add(content.toString());
        }
        Charset win = Charset.forName("windows-1252");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        StringBuilder head = new StringBuilder("%PDF-1.4\n");
        write(out, head.toString(), win);
        int n = 3 + pages.size() * 2;
        // 1 catalogue, 2 pages, 3 police, puis (page, contenu) par page
        offsets.add(out.size());
        write(out, "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n", win);
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            kids.append(4 + i * 2).append(" 0 R ");
        }
        offsets.add(out.size());
        write(out, "2 0 obj << /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >> endobj\n", win);
        offsets.add(out.size());
        write(out, "3 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >> endobj\n", win);
        for (int i = 0; i < pages.size(); i++) {
            int pageObj = 4 + i * 2;
            offsets.add(out.size());
            write(out, pageObj + " 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >>"
                    + " /Contents " + (pageObj + 1) + " 0 R >> endobj\n", win);
            byte[] c = pages.get(i).getBytes(win);
            offsets.add(out.size());
            write(out, (pageObj + 1) + " 0 obj << /Length " + c.length + " >> stream\n", win);
            out.write(c, 0, c.length);
            write(out, "endstream endobj\n", win);
        }
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (n + 1) + "\n0000000000 65535 f \n");
        for (int o : offsets) {
            x.append(String.format("%010d 00000 n \n", o));
        }
        x.append("trailer << /Size ").append(n + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        write(out, x.toString(), win);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s, Charset cs) {
        byte[] b = s.getBytes(cs);
        out.write(b, 0, b.length);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private static String pad(String s, int n) {
        if (s.length() > n - 1) {
            s = s.substring(0, n - 2) + ".";
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String padLeft(String s, int n) {
        if (s.length() > n - 1) {
            s = s.substring(0, n - 1);
        }
        StringBuilder sb = new StringBuilder();
        while (sb.length() + s.length() < n) {
            sb.append(' ');
        }
        return sb.append(s).toString();
    }

    /** Mise en forme des montants dans la devise et la langue de la caisse. */
    public static final class Money {
        final String code;
        private final NumberFormat nf;

        public Money(String currency, String locale) {
            Locale l = locale == null || locale.isEmpty() ? Locale.FRANCE : Locale.forLanguageTag(locale);
            String c = currency == null || currency.isEmpty() ? "EUR" : currency;
            this.code = c;
            NumberFormat f = NumberFormat.getCurrencyInstance(l);
            try {
                f.setCurrency(Currency.getInstance(c));
            } catch (IllegalArgumentException e) {
                // devise inconnue : format de la langue
            }
            this.nf = f;
        }

        public String f(Object v) {
            double d = v instanceof Number ? ((Number) v).doubleValue() : 0;
            return nf.format(d).replace(' ', ' ').replace(' ', ' ');
        }
    }
}
