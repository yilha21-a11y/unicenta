package com.unicenta.pos.portal;

import com.unicenta.data.loader.Session;
import com.unicenta.pos.api.Json;
import com.unicenta.pos.forms.AppConfig;
import com.unicenta.pos.forms.AppViewConnection;
import com.unicenta.pos.forms.DataLogicSystem;
import com.unicenta.pos.testenv.TestEnvironment;
import com.unicenta.pos.util.Hashcypher;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * DEMONSTRATION locale du portail Patron avec le VRAI code de la caisse
 * (PortalAgent, PortalAuthenticator, PortalExporter) sur une base Derby de
 * TEST remplie de ventes fictives. Ne touche jamais la base habituelle.
 *
 * <pre>
 * PortalDemo DOSSIER "http://127.0.0.1:8090" CODE-ASSOCIATION facteur
 * </pre>
 * Le mot de passe Administrator (aleatoire) et la cle de double
 * authentification sont ecrits dans DOSSIER\acces-demo.txt (fichier local,
 * hors depot) ; le code a 6 chiffres courant est ecrit chaque seconde dans
 * DOSSIER\code-2fa.txt pour pouvoir se connecter depuis un navigateur.
 * "facteur" multiplie les montants pour distinguer deux restaurants.
 */
public final class PortalDemo {

    private PortalDemo() {
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]).getCanonicalFile();
        String url = args[1];
        String code = args[2];
        double k = args.length > 3 ? Double.parseDouble(args[3]) : 1.0;
        File root = new File(".").getCanonicalFile();
        TestEnvironment.Result env = TestEnvironment.create(new File(dir, "caisse-test"), true, 0, null, root);
        AppConfig config = new AppConfig(env.configFile);
        config.load();
        Session session = AppViewConnection.createSession(config);
        Connection c = session.getConnection();

        byte[] rnd = new byte[9];
        new SecureRandom().nextBytes(rnd);
        String password = "demo-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
        try (PreparedStatement ps = c.prepareStatement("UPDATE people SET apppassword = ? WHERE id = '0'")) {
            ps.setString(1, Hashcypher.hashString(password));
            ps.executeUpdate();
        }
        ZoneId zone = ZoneId.systemDefault();
        long today = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        long h = 3600_000L;
        String money = PortalTestData.openCash(c, "DEMO-POS", 1, today + 8 * h, null);
        int n = 1;
        PortalTestData.sale(c, money, today + 9 * h, n++, 0, env.waiterId, "cash", 1, "Table 1",
                PortalTestData.line("p-cafe", 2.0, 1.80 * k, "001", 0.10));
        PortalTestData.sale(c, money, today + 12 * h + 15 * 60_000L, n++, 0, env.waiterId, "magcard", 2, "Table 4",
                PortalTestData.line("p-steak", 2.0, 14.0 * k, "001", 0.10),
                PortalTestData.line("p-eau", 2.0, 2.0 * k, "000", 0.0));
        PortalTestData.sale(c, money, today + 12 * h + 40 * 60_000L, n++, 0, "0", "cash", 3, "Table 2",
                PortalTestData.line("p-poisson", 1.0, 16.5 * k, "001", 0.10),
                PortalTestData.line("p-salade", 1.0, 7.5 * k, "001", 0.10));
        PortalTestData.sale(c, money, today + 13 * h, n++, 1, "0", "cashrefund", null, null,
                PortalTestData.line("p-cafe", 1.0, 1.80 * k, "001", 0.10));
        // la veille, pour les vues semaine / mois
        PortalTestData.sale(c, money, today - 24 * h + 20 * h, n++, 0, env.waiterId, "cash", 4, "Table 3",
                PortalTestData.line("p-steak", 3.0, 14.0 * k, "001", 0.10));
        if (!c.getAutoCommit()) {
            c.commit();
        }

        DataLogicSystem dl = new DataLogicSystem();
        dl.init(session);
        PortalLink link = new PortalLink(new File(dir, "donnees-olpos"));
        String secret = Totp.newSecret();
        link.enroll("0", secret);
        java.util.Map<String, Object> meta = Json.obj("currency", "EUR", "locale", "fr-FR",
                "timezone", zone.getId(), "version", com.unicenta.pos.forms.Branding.VERSION);
        java.util.Map<String, Object> r = PortalAgent.pair(link, url, code, "Caisse de demonstration", meta);
        Files.write(new File(dir, "acces-demo.txt").toPath(), ("DEMONSTRATION - donnees fictives\r\n"
                + "Code restaurant : " + r.get("slug") + "\r\nIdentifiant : Administrator\r\nMot de passe : " + password
                + "\r\nCle double authentification : " + secret + "\r\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("Caisse de demonstration associee a " + r.get("name") + " (" + r.get("slug") + ")");

        PortalAgent agent = new PortalAgent(link, c, dl, meta);
        agent.syncOnce();
        agent.start();
        File codeFile = new File(dir, "code-2fa.txt");
        while (true) {
            Files.write(codeFile.toPath(), Totp.code(secret, System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII));
            Thread.sleep(1000);
        }
    }
}
