package com.unicenta.pos.portal;

import com.unicenta.data.loader.Datas;
import com.unicenta.data.loader.SerializerWriteBasic;
import com.unicenta.data.loader.Session;
import com.unicenta.data.loader.StaticSentence;
import com.unicenta.pos.api.Json;
import com.unicenta.pos.forms.AppConfig;
import com.unicenta.pos.forms.AppViewConnection;
import com.unicenta.pos.forms.DataLogicSales;
import com.unicenta.pos.forms.DataLogicSystem;
import com.unicenta.pos.payment.PaymentInfo;
import com.unicenta.pos.payment.PaymentInfoCash;
import com.unicenta.pos.payment.PaymentInfoTicket;
import com.unicenta.pos.sales.TaxesLogic;
import com.unicenta.pos.sales.TicketSave;
import com.unicenta.pos.testenv.TestEnvironment;
import com.unicenta.pos.ticket.TicketInfo;
import com.unicenta.pos.ticket.TicketLineInfo;
import com.unicenta.pos.ticket.UserInfo;
import com.unicenta.pos.util.Hashcypher;
import com.unicenta.pos.ticket.TaxInfo;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Verification de bout en bout, base de TEST (Derby ou MySQL isole) + vrai
 * serveur portail : vente, paiement, remboursement, cloture enregistres avec
 * le code de la caisse (DataLogicSales.saveTicket, requetes de cloture),
 * synchronisation, connexion du patron, concordance des montants et des
 * dates ; avec MySQL : arret puis redemarrage du serveur MySQL.
 *
 * <pre>
 * PortalEndToEnd DOSSIER URL_PORTAIL CODE_ASSOCIATION [FICHIER_ACCES_MYSQL MY_INI MYSQL_BIN]
 * </pre>
 * Affiche "OK ..." / "ECHEC ..." et se termine avec le code 0 seulement si tout est concordant.
 */
@SuppressWarnings("unchecked")
public final class PortalEndToEnd {

    static int failures = 0;

    private PortalEndToEnd() {
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "OK     " : "ECHEC  ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]).getCanonicalFile();
        String portal = args[1];
        String code = args[2];
        TestEnvironment.MySql my = null;
        Properties access = null;
        if (args.length > 3) {
            access = new Properties();
            try (InputStream in = new FileInputStream(args[3])) {
                access.load(in);
            }
            my = new TestEnvironment.MySql(access.getProperty("hote").trim(),
                    Integer.parseInt(access.getProperty("port").trim()), access.getProperty("base").trim(),
                    access.getProperty("utilisateur").trim(), access.getProperty("mot_de_passe").trim());
        }
        String kind = my == null ? "Derby" : "MySQL";
        System.out.println("=== Base : " + kind + " ; fuseau Java : " + ZoneId.systemDefault());

        // ---------------------------------------------------------------- base et caisse
        TestEnvironment.Result env = TestEnvironment.create(new File(dir, "caisse"), true, 0, null,
                new File(".").getCanonicalFile(), my);
        AppConfig config = new AppConfig(env.configFile);
        config.load();
        Session till = AppViewConnection.createSession(config);              // session de la caisse
        Session portalSession = AppViewConnection.createAdditionalSession(config);   // comme JRootApp
        check(true, "base " + kind + " creee avec le script officiel, sessions caisse + portail ouvertes");
        String adminPwd = "e2e-" + UUID.randomUUID();
        exec(till.getConnection(), "UPDATE people SET apppassword = ? WHERE id = '0'", Hashcypher.hashString(adminPwd));

        DataLogicSystem dlTill = new DataLogicSystem();
        dlTill.init(till);
        DataLogicSales sales = new DataLogicSales();
        sales.init(till);
        TaxesLogic taxes = new TaxesLogic(sales.getTaxList().list());
        TaxInfo tax10 = taxes.getTaxInfo("001");
        TaxInfo tax0 = taxes.getTaxInfo("000");

        // caisse ouverte (meme requete que l'ouverture de la caisse)
        String money1 = UUID.randomUUID().toString();
        dlTill.execInsertCash(new Object[] {money1, "TEST-POS", 1, new Date(System.currentTimeMillis() - 3600_000L), null, 0});

        // ---------------------------------------------------------------- association et agent
        PortalLink link = new PortalLink(new File(dir, "donnees-olpos"));
        PortalAgent.purgeOtherRestaurants(link, "");
        Map<String, Object> pr = PortalAgent.pair(link, portal, code, "Caisse " + kind, Json.obj());
        link.enroll("0", PortalLink.NO_CODE);
        DataLogicSystem dlPortal = new DataLogicSystem();
        dlPortal.init(portalSession);
        Map<String, Object> meta = Json.obj("currency", "EUR", "locale", "fr-FR", "timezone",
                ZoneId.systemDefault().getId(), "version", "test");
        PortalAgent agent = new PortalAgent(link, portalSession::getConnection, dlPortal, meta);
        agent.start();
        String slug = (String) pr.get("slug");
        check(link.isPaired(), "caisse associee au portail (" + slug + ")");

        // ---------------------------------------------------------------- ventes
        List<String> saved = new ArrayList<>();
        saved.add(sell(sales, taxes, money1, TicketInfo.RECEIPT_NORMAL, "cash",
                new TicketLineInfo("Steak frites", "001", "0", 2.0, 14.0, tax10),
                new TicketLineInfo("Eau minerale", "000", "0", 1.0, 2.0, tax0)));
        saved.add(sell(sales, taxes, money1, TicketInfo.RECEIPT_NORMAL, "magcard",
                new TicketLineInfo("Cafe", "001", "0", 3.0, 1.8, tax10)));
        saved.add(sell(sales, taxes, money1, TicketInfo.RECEIPT_REFUND, "cashrefund",
                new TicketLineInfo("Cafe", "001", "0", -1.0, 1.8, tax10)));
        check(saved.stream().allMatch(s -> s != null), "vente especes, vente carte et remboursement enregistres (saveTicket)");

        // cloture (memes requetes que l'ecran de cloture)
        Date now = new Date();
        new StaticSentence(till, "UPDATE closedcash SET DATEEND = ?, NOSALES = ? WHERE HOST = ? AND MONEY = ?",
                new SerializerWriteBasic(new Datas[] {Datas.TIMESTAMP, Datas.INT, Datas.STRING, Datas.STRING}))
                .exec(new Object[] {now, 0, "TEST-POS", money1});
        String money2 = UUID.randomUUID().toString();
        dlTill.execInsertCash(new Object[] {money2, "TEST-POS", 2, now, null, 0});
        check(true, "cloture de caisse faite, nouvelle caisse ouverte");

        // ---------------------------------------------------------------- synchronisation
        syncUntilOk(agent, 30);
        HttpClient http = HttpClient.newHttpClient();
        String cookie = login(http, portal, slug, adminPwd);
        check(cookie != null, "connexion du patron (mot de passe verifie par la caisse " + kind + ")");
        compare(http, portal, cookie, till.getConnection(), "apres synchronisation");

        Map<String, Object> cl = get(http, portal, cookie, "closures?period=day");
        boolean closedFound = false;
        for (Object o : (List<Object>) cl.get("closures")) {
            Map<String, Object> c = (Map<String, Object>) o;
            if (money1.equals(c.get("money")) && c.get("end") != null) {
                closedFound = true;
                check(((Number) c.get("tickets")).longValue() == 3, "cloture : 3 tickets rattaches");
                check(Math.abs(((Number) c.get("end")).longValue() - now.getTime()) < 1000,
                        "cloture : heure de fin identique a la caisse (" + c.get("endText") + ")");
            }
        }
        check(closedFound, "cloture visible sur le portail");

        // ---------------------------------------------------------------- arret / redemarrage MySQL
        if (my != null) {
            String bin = args[5];
            String root = access.getProperty("root").trim();
            ProcessBuilder pb = new ProcessBuilder(bin + "\\mysqladmin.exe", "--protocol=TCP", "-h127.0.0.1",
                    "-P" + my.port, "-uroot", "shutdown").redirectErrorStream(true);
            pb.environment().put("MYSQL_PWD", root);   // jamais sur la ligne de commande
            Process stop = pb.start();
            stop.getOutputStream().close();
            stop.getInputStream().readAllBytes();
            stop.waitFor();
            waitPort(my.port, false);
            // le port se ferme avant la fin : attendre l'arret COMPLET (fichier pid supprime par mysqld)
            File pid = new File(new File(args[4]).getParentFile(), "mysqld.pid");
            for (int i = 0; i < 120 && pid.exists(); i++) {
                Thread.sleep(500);
            }
            Thread.sleep(1000);
            check(!pid.exists(), "MySQL completement arrete");
            String during = sell(sales, taxes, money2, TicketInfo.RECEIPT_NORMAL, "cash",
                    new TicketLineInfo("The", "001", "0", 1.0, 2.2, tax10));
            check(during == null, "vente pendant l'arret de MySQL : refusee, rien n'est considere comme encaisse");
            try {
                agent.syncOnce();
                check(false, "synchronisation pendant l'arret (devrait echouer)");
            } catch (Exception e) {
                check(true, "synchronisation reportee pendant l'arret : " + e.getClass().getSimpleName());
            }
            new ProcessBuilder(bin + "\\mysqld.exe", "--defaults-file=" + args[4])
                    .redirectErrorStream(true).redirectOutput(new File(dir, "mysqld-redemarrage.log")).start();
            waitPort(my.port, true);
            check(true, "MySQL redemarre");
            String after = null;
            for (int i = 0; i < 10 && after == null; i++) {
                after = sell(sales, taxes, money2, TicketInfo.RECEIPT_NORMAL, "cash",
                        new TicketLineInfo("The", "001", "0", 1.0, 2.2, tax10));
                if (after == null) {
                    Thread.sleep(1000);
                }
            }
            check(after != null, "vente apres redemarrage enregistree (reconnexion de la caisse)");
            syncUntilOk(agent, 30);
            check(true, "synchronisation reprise apres redemarrage (reconnexion automatique)");
            // renvoi complet volontaire (rapprochement) : toujours aucun doublon
            agent.syncOnce();
            compare(http, portal, cookie, till.getConnection(), "apres redemarrage de MySQL");
        }
        agent.stop();
        portalSession.close();
        till.close();
        System.out.println(failures == 0 ? "=== RESULTAT " + kind + " : TOUT EST CONCORDANT"
                : "=== RESULTAT " + kind + " : " + failures + " ECHEC(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ================================================================== caisse

    /** Vente comme a la caisse : taxes, paiement, saveTicket ; null si l'enregistrement echoue. */
    static String sell(DataLogicSales sales, TaxesLogic taxes, String money, int type, String payment,
            TicketLineInfo... lines) {
        try {
            TicketInfo t = new TicketInfo();
            t.setTicketType(type);
            for (TicketLineInfo l : lines) {
                t.addLine(l);
            }
            taxes.calculateTaxes(t);
            double total = t.getTotal();
            List<PaymentInfo> pays = new ArrayList<>();
            pays.add("cash".equals(payment) ? new PaymentInfoCash(total, total, total)
                    : new PaymentInfoTicket(total, payment));
            t.setPayments(pays);
            t.setUser(new UserInfo("0", "Administrator"));
            t.setActiveCash(money);
            t.setDate(new Date());
            boolean ok = TicketSave.save(() -> sales.saveTicket(t, "0"), e -> { });
            return ok ? t.getId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static void exec(Connection c, String sql, Object... a) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < a.length; i++) {
                ps.setObject(i + 1, a[i]);
            }
            ps.executeUpdate();
        }
        if (!c.getAutoCommit()) {
            c.commit();
        }
    }

    static void syncUntilOk(PortalAgent agent, int tries) throws Exception {
        Exception last = null;
        for (int i = 0; i < tries; i++) {
            try {
                while (agent.syncOnce()) {
                    // historique en cours d'envoi
                }
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(1000);
            }
        }
        throw last;
    }

    // ================================================================== comparaison caisse / portail

    static void compare(HttpClient http, String portal, String cookie, Connection c, String when) throws Exception {
        // caisse : memes tables que les rapports
        Map<String, double[]> till = new java.util.HashMap<>();   // id -> {ts, ht, tva, ttc, type}
        Map<String, String> rawDates = new java.util.HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT r.ID, r.DATENEW, t.TICKETTYPE,"
                + " (SELECT SUM(l.UNITS * l.PRICE) FROM ticketlines l WHERE l.TICKET = r.ID),"
                + " (SELECT SUM(x.AMOUNT) FROM taxlines x WHERE x.RECEIPT = r.ID),"
                + " (SELECT SUM(y.TOTAL) FROM payments y WHERE y.RECEIPT = r.ID),"
                + " CAST(r.DATENEW AS CHAR(30)) FROM receipts r JOIN tickets t ON r.ID = t.ID");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                till.put(rs.getString(1), new double[] {rs.getTimestamp(2).getTime(), rs.getDouble(4), rs.getDouble(5),
                    rs.getDouble(6), rs.getInt(3)});
                rawDates.put(rs.getString(1), rs.getString(7));
            }
        }
        List<Object> list = (List<Object>) get(http, portal, cookie, "tickets?period=month").get("tickets");
        Set<String> ids = new HashSet<>();
        int dup = 0;
        for (Object o : list) {
            if (!ids.add((String) ((Map<String, Object>) o).get("id"))) {
                dup++;
            }
        }
        check(dup == 0, when + " : aucun doublon (" + list.size() + " tickets sur le portail)");
        check(ids.equals(till.keySet()), when + " : memes tickets a la caisse et sur le portail (" + till.size() + ")");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());
        boolean datesOk = true;
        boolean amountsOk = true;
        double salesTtc = 0;
        double refundTtc = 0;
        for (Object o : list) {
            Map<String, Object> p = (Map<String, Object>) o;
            double[] t = till.get(p.get("id"));
            if (t == null) {
                continue;
            }
            long ts = ((Number) p.get("ts")).longValue();
            String shown = (String) p.get("when");
            if (ts != (long) t[0] || !f.format(Instant.ofEpochMilli((long) t[0])).equals(shown)) {
                datesOk = false;
                System.out.println("        date differente : caisse " + rawDates.get(p.get("id")) + " / portail " + shown);
            }
            double total = ((Number) p.get("total")).doubleValue();
            if (Math.abs(total - (t[1] + t[2])) > 0.005 || Math.abs(total - t[3]) > 0.005
                    || Math.abs(((Number) p.get("base")).doubleValue() - t[1]) > 0.005) {
                amountsOk = false;
                System.out.println("        montant different : " + p.get("id") + " portail " + total + " caisse "
                        + (t[1] + t[2]) + " (paye " + t[3] + ")");
            }
            if (t[4] == 1) {
                refundTtc += total;
            } else {
                salesTtc += total;
            }
        }
        String sample = rawDates.values().iterator().next();
        check(datesOk, when + " : dates identiques (ex. valeur en base " + sample + ", fuseau " + ZoneId.systemDefault() + ")");
        check(amountsOk, when + " : montants HT / TVA / TTC / paiements identiques ticket par ticket");
        Map<String, Object> s = get(http, portal, cookie, "summary?period=month");
        double sTtc = ((Number) ((Map<String, Object>) s.get("sales")).get("total")).doubleValue();
        double rTtc = ((Number) ((Map<String, Object>) s.get("refunds")).get("total")).doubleValue();
        check(Math.abs(sTtc - salesTtc) < 0.01 && Math.abs(rTtc - refundTtc) < 0.01,
                when + String.format(" : totaux du mois ventes %.2f / remboursements %.2f", sTtc, rTtc));
    }

    // ================================================================== patron (comme app.js)

    static String login(HttpClient http, String portal, String slug, String pwd) throws Exception {
        for (int i = 0; i < 20; i++) {
            HttpResponse<String> ch = http.send(HttpRequest.newBuilder(URI.create(portal
                    + "/api/patron/challenge?restaurant=" + slug)).build(), HttpResponse.BodyHandlers.ofString());
            if (ch.statusCode() != 200) {
                Thread.sleep(1000);
                continue;
            }
            Map<String, Object> c = (Map<String, Object>) Json.parse(ch.body());
            Cipher ci = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            ci.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode((String) c.get("encKey")))), new OAEPParameterSpec("SHA-256", "MGF1",
                    MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            String ct = Base64.getEncoder().encodeToString(ci.doFinal(Json.write(Json.obj("cid", c.get("challengeId"),
                    "nonce", c.get("nonce"), "user", "Administrator", "password", pwd, "totp", ""))
                    .getBytes(StandardCharsets.UTF_8)));
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(portal + "/api/patron/login"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(
                            Json.obj("restaurant", slug, "user", "Administrator", "challengeId", c.get("challengeId"),
                                    "ciphertext", ct)))).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                String set = r.headers().firstValue("Set-Cookie").orElse("");
                return set.substring(0, set.indexOf(';'));
            }
            Thread.sleep(1000);
        }
        return null;
    }

    static Map<String, Object> get(HttpClient http, String portal, String cookie, String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(portal + "/api/patron/" + path))
                .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
        return (Map<String, Object>) Json.parse(r.body());
    }

    static void waitPort(int port, boolean open) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            boolean up;
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
                up = true;
            } catch (java.io.IOException e) {
                up = false;
            }
            if (up == open) {
                Thread.sleep(open ? 2000 : 500);
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("port " + port + (open ? " jamais ouvert" : " jamais ferme"));
    }
}
