package com.unicenta.pos.portal;

import com.sun.net.httpserver.HttpServer;
import com.unicenta.data.loader.Session;
import com.unicenta.pos.api.Json;
import com.unicenta.pos.forms.AppConfig;
import com.unicenta.pos.forms.AppViewConnection;
import com.unicenta.pos.forms.DataLogicSystem;
import com.unicenta.pos.testenv.TestEnvironment;
import com.unicenta.pos.util.Hashcypher;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Cote caisse du portail Patron, sur une base Derby de TEST : lecture des
 * ventes, verification deleguee du mot de passe et de la double
 * authentification, file d'envoi persistante, association.
 */
@SuppressWarnings("unchecked")
public class PortalTillIntegrationTest {

    private static final String ADMIN_PASSWORD = "admin-test-" + System.nanoTime();
    private static TestEnvironment.Result env;
    private static Session session;
    private static Connection c;
    private static DataLogicSystem dl;
    private static File work;
    private static long day;

    @BeforeClass
    public static void preparer() throws Exception {
        File root = new File(".").getCanonicalFile();
        work = new File(root, "build/test-work/portal-till");
        env = TestDatabases.create(new File(work, "env-" + TestDatabases.kind()), root);
        AppConfig config = new AppConfig(env.configFile);
        config.load();
        session = AppViewConnection.createSession(config);
        c = session.getConnection();
        dl = new DataLogicSystem();
        dl.init(session);
        try (PreparedStatement ps = c.prepareStatement("UPDATE people SET apppassword = ? WHERE id = '0'")) {
            ps.setString(1, Hashcypher.hashString(ADMIN_PASSWORD));
            ps.executeUpdate();
        }
        day = java.time.LocalDate.of(2026, 11, 10).atStartOfDay(java.time.ZoneId.of("Europe/Paris")).toInstant()
                .toEpochMilli();
        String money = PortalTestData.openCash(c, "TEST-POS", 1, day + 8 * 3600_000L, day + 23 * 3600_000L);
        PortalTestData.sale(c, money, day + 12 * 3600_000L, 1, 0, "0", "cash", 7, "Table 3",
                PortalTestData.line("p-steak", 2.0, 14.0, "001", 0.10),
                PortalTestData.line("p-eau", 1.0, 2.0, "000", 0.0));
        PortalTestData.sale(c, money, day + 13 * 3600_000L, 2, 0, env.waiterId, "magcard", null, null,
                PortalTestData.line("p-cafe", 3.0, 1.80, "001", 0.10));
        PortalTestData.sale(c, money, day + 14 * 3600_000L, 3, 1, "0", "cashrefund", null, null,
                PortalTestData.line("p-cafe", 1.0, 1.80, "001", 0.10));
        if (!c.getAutoCommit()) {
            c.commit();
        }
    }

    @AfterClass
    public static void fermer() {
        if (session != null) {
            session.close();
        }
    }

    // ================================================================== TOTP

    @Test
    public void totpVecteurRfc6238() {
        // secret ASCII "12345678901234567890" ; RFC 6238 annexe B (T = 59 s) : 94287082 -> 6 chiffres 287082
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        assertEquals("287082", Totp.code(secret, 59_000L));
        assertTrue(Totp.verify(secret, "287082", 59_000L));
        assertTrue("une periode de decalage toleree", Totp.verify(secret, "287082", 89_000L));
        assertFalse(Totp.verify(secret, "287082", 200_000L));
        assertFalse(Totp.verify(secret, "abc", 59_000L));
    }

    // ================================================================== export

    @Test
    public void exportDesVentesSansDonneesClient() throws Exception {
        PortalExporter ex = new PortalExporter(c);
        List<Object> t = ex.ticketsAfter(0, "");
        assertEquals(3, t.size());
        Map<String, Object> first = (Map<String, Object>) t.get(0);
        assertEquals(1L, first.get("ticketNo"));
        assertEquals(30.0, (Double) first.get("base"), 0.001);      // 2 x 14 + 2
        assertEquals(2.8, (Double) first.get("tax"), 0.001);        // 10 % de 28
        assertEquals(32.8, (Double) first.get("total"), 0.001);
        assertEquals(7L, first.get("orderNumber"));
        assertEquals("Table 3", first.get("place"));
        assertEquals("Administrator", first.get("personName"));
        assertEquals(2, ((List<Object>) first.get("lines")).size());
        Map<String, Object> refund = (Map<String, Object>) t.get(2);
        assertEquals(1L, refund.get("type"));
        assertEquals(-1.98, (Double) refund.get("total"), 0.001);
        String json = Json.write(t);
        assertFalse("notes de paiement jamais envoyees", json.contains("NOTE-PRIVEE"));
        assertFalse(json.contains("customer"));
        assertFalse(json.contains("sha1:"));
        // curseur : rien apres le dernier ticket
        assertEquals(0, ex.ticketsAfter((Long) refund.get("ts"), (String) refund.get("id")).size());
        assertEquals(2, ex.ticketsAfter((Long) first.get("ts"), (String) first.get("id")).size());
        List<Object> cl = ex.closures(0);
        assertTrue(cl.size() >= 1);
    }

    // ================================================================== authentification deleguee

    private static PortalLink link(String name) throws Exception {
        File d = new File(work, name);
        deleteTree(d);
        PortalLink l = new PortalLink(d);
        l.ensureKeys();
        l.savePairing("https://portail.example", "rid-1", "resto", "Resto", "jeton");
        return l;
    }

    private static String encrypt(String encPubB64, Map<String, Object> creds) throws Exception {
        PublicKey k = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(encPubB64)));
        Cipher ci = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        ci.init(Cipher.ENCRYPT_MODE, k, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT));
        return Base64.getEncoder().encodeToString(ci.doFinal(Json.write(creds).getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> ask(PortalAuthenticator a, PortalLink l, String req, String nonce, String user,
            String pwd, String code) throws Exception {
        return a.handle(req, nonce, encrypt(l.ensureKeys()[1], Json.obj("cid", req, "nonce", nonce, "user", user,
                "password", pwd, "totp", code)));
    }

    @Test
    public void connexionDelegueeReussieEtSignee() throws Exception {
        PortalLink l = link("auth-ok");
        String secret = Totp.newSecret();
        l.enroll("0", secret);
        long now = System.currentTimeMillis();
        PortalAuthenticator a = new PortalAuthenticator(l, dl, () -> now);
        Map<String, Object> r = ask(a, l, "req-1", "n-1", "administrator", ADMIN_PASSWORD, Totp.code(secret, now));
        assertEquals(r.toString(), true, r.get("ok"));
        String assertion = (String) r.get("assertion");
        Map<String, Object> as = (Map<String, Object>) Json.parse(assertion);
        assertEquals("rid-1", as.get("rid"));
        assertEquals("req-1", as.get("req"));
        assertEquals("n-1", as.get("nonce"));
        assertEquals("0", as.get("uid"));
        assertFalse("jamais de mot de passe dans la preuve", assertion.contains(ADMIN_PASSWORD));
        Signature s = Signature.getInstance("Ed25519");
        s.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(l.ensureKeys()[0]))));
        s.update(assertion.getBytes(StandardCharsets.UTF_8));
        assertTrue(s.verify(Base64.getDecoder().decode((String) r.get("signature"))));
        // le meme code ne sert qu'une fois
        Map<String, Object> again = ask(a, l, "req-2", "n-2", "Administrator", ADMIN_PASSWORD, Totp.code(secret, now));
        assertEquals(false, again.get("ok"));
    }

    @Test
    public void compteAutoriseSansCodeMotDePasseSeul() throws Exception {
        PortalLink l = link("auth-sans-code");
        l.enroll("0", PortalLink.NO_CODE);
        long now = System.currentTimeMillis();
        PortalAuthenticator a = new PortalAuthenticator(l, dl, () -> now);
        assertEquals(true, ask(a, l, "r1", "n1", "Administrator", ADMIN_PASSWORD, "").get("ok"));
        assertEquals("le meme acces peut se reconnecter", true,
                ask(a, l, "r2", "n2", "Administrator", ADMIN_PASSWORD, "").get("ok"));
        assertEquals("invalid", ask(a, l, "r3", "n3", "Administrator", "faux", "").get("reason"));
        // compte sans mot de passe : toujours refuse, meme sans code
        l.enroll("1", PortalLink.NO_CODE);
        assertEquals("no_password", ask(a, l, "r4", "n4", "Manager", "", "").get("reason"));
        // passer de "sans code" a "avec code" change l'empreinte (sessions fermees)
        String before = l.stamp("0", "sha1:A", "0", true);
        l.enroll("0", Totp.newSecret());
        assertNotEquals(before, l.stamp("0", "sha1:A", "0", true));
    }

    @Test
    public void refusMauvaisMotDePasseMauvaisCodeEtBlocage() throws Exception {
        PortalLink l = link("auth-ko");
        String secret = Totp.newSecret();
        l.enroll("0", secret);
        long[] now = {System.currentTimeMillis()};
        PortalAuthenticator a = new PortalAuthenticator(l, dl, () -> now[0]);
        assertEquals("invalid", ask(a, l, "r", "n", "Administrator", "faux", Totp.code(secret, now[0])).get("reason"));
        assertEquals("invalid", ask(a, l, "r", "n", "Administrator", ADMIN_PASSWORD, "000000").get("reason"));
        assertEquals("invalid", ask(a, l, "r", "n", "Inconnu", ADMIN_PASSWORD, "000000").get("reason"));
        // bloc destine a une autre demande (rejeu) : refuse
        Map<String, Object> replay = a.handle("autre", "n", encrypt(l.ensureKeys()[1], Json.obj("cid", "r", "nonce",
                "n", "user", "Administrator", "password", ADMIN_PASSWORD, "totp", Totp.code(secret, now[0]))));
        assertEquals("invalid", replay.get("reason"));
        assertEquals("invalid", a.handle("r", "n", "pas-du-base64!").get("reason"));
        for (int i = 0; i < 3; i++) {
            ask(a, l, "r", "n", "Administrator", "faux", "000000");
        }
        assertEquals("locked", ask(a, l, "r", "n", "Administrator", ADMIN_PASSWORD, Totp.code(secret, now[0]))
                .get("reason"));
        now[0] += PortalAuthenticator.LOCK_MS + 60_000L;
        assertEquals(true, ask(a, l, "r", "n", "Administrator", ADMIN_PASSWORD, Totp.code(secret, now[0])).get("ok"));
    }

    @Test
    public void refusSansMotDePasseOuSansDroitOuSansActivation() throws Exception {
        PortalLink l = link("auth-refus");
        long now = System.currentTimeMillis();
        PortalAuthenticator a = new PortalAuthenticator(l, dl, () -> now);
        String secret = Totp.newSecret();
        // Manager : aucun mot de passe dans la base par defaut
        l.enroll("1", secret);
        assertEquals("no_password", ask(a, l, "r", "n", "Manager", "", Totp.code(secret, now)).get("reason"));
        // serveur : mot de passe mais pas de droit d'administration
        l.enroll(env.waiterId, secret);
        assertEquals("not_enrolled", ask(a, l, "r", "n", env.waiterName, env.waiterPassword, Totp.code(secret, now))
                .get("reason"));
        // administrateur non active
        assertEquals("not_enrolled", ask(a, l, "r", "n", "Administrator", ADMIN_PASSWORD, Totp.code(secret, now))
                .get("reason"));
        // etat envoye au portail : seulement les comptes actives, sans mot de passe
        String state = Json.write(a.usersState());
        assertFalse(state.contains("sha1:"));
        assertFalse(state.contains(env.waiterPassword));
    }

    @Test
    public void empreinteChangeAvecLeMotDePasse() throws Exception {
        PortalLink l = link("empreinte");
        String s1 = l.stamp("0", "sha1:AAA", "0", true);
        assertEquals(s1, l.stamp("0", "sha1:AAA", "0", true));
        assertNotEquals(s1, l.stamp("0", "sha1:BBB", "0", true));
        assertNotEquals(s1, l.stamp("0", "sha1:AAA", "1", true));
        assertNotEquals(s1, l.stamp("0", "sha1:AAA", "0", false));
        l.enroll("0", Totp.newSecret());
        assertNotEquals("nouvelle double authentification = nouvelle empreinte", s1, l.stamp("0", "sha1:AAA", "0", true));
    }

    @Test
    public void adresseHttpsObligatoire() {
        assertEquals("https://portail.example", PortalLink.checkUrl("https://portail.example/"));
        assertEquals("http://127.0.0.1:8090", PortalLink.checkUrl("http://127.0.0.1:8090"));
        try {
            PortalLink.checkUrl("http://portail.example");
            throw new AssertionError("http accepte");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("https"));
        }
    }

    // ================================================================== association et file d'envoi

    @Test
    public void associationPuisFileEnvoyeeDansLOrdreMalgreUneCoupure() throws Exception {
        List<Map<String, Object>> received = new ArrayList<>();
        AtomicInteger failNext = new AtomicInteger(1);
        String[] auth = new String[1];
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/api/device/pair", ex -> {
            Map<String, Object> b;
            try {
                b = (Map<String, Object>) Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            } catch (Json.ParseException e) {
                throw new java.io.IOException(e);
            }
            boolean ok = "CODE-1".equals(b.get("code")) && b.get("signKey") != null && b.get("encKey") != null;
            reply(ex, ok ? 200 : 400, ok ? Json.obj("restaurantId", "rid-x", "slug", "chez-test", "name", "Chez Test",
                    "deviceToken", "jeton-secret") : Json.obj("error", Json.obj("message", "Code invalide")));
        });
        srv.createContext("/api/device/sync", ex -> {
            auth[0] = ex.getRequestHeaders().getFirst("Authorization");
            byte[] body = ex.getRequestBody().readAllBytes();
            if (failNext.getAndDecrement() > 0) {
                reply(ex, 500, Json.obj("error", Json.obj("message", "coupure simulee")));
                return;
            }
            try {
                synchronized (received) {
                    received.add((Map<String, Object>) Json.parse(new String(body, StandardCharsets.UTF_8)));
                }
            } catch (Json.ParseException e) {
                throw new java.io.IOException(e);
            }
            reply(ex, 200, Json.obj("status", "ok"));
        });
        srv.start();
        try {
            File d = new File(work, "agent");
            deleteTree(d);
            PortalLink l = new PortalLink(d);
            String url = "http://127.0.0.1:" + srv.getAddress().getPort();
            try {
                PortalAgent.pair(l, url, "MAUVAIS", "Caisse", Json.obj());
                throw new AssertionError("code refuse attendu");
            } catch (java.io.IOException e) {
                assertEquals("Code invalide", e.getMessage());
            }
            assertFalse(l.isPaired());
            PortalAgent.pair(l, url, "CODE-1", "Caisse", Json.obj("currency", "EUR"));
            assertTrue(l.isPaired());
            assertEquals("chez-test", l.slug());

            PortalAgent agent = new PortalAgent(l, c, dl, Json.obj("currency", "EUR", "timezone", "Europe/Paris"));
            try {
                agent.syncOnce();
                throw new AssertionError("coupure attendue");
            } catch (java.io.IOException e) {
                assertEquals(1, agent.pendingBatches());   // lot garde sur disque
            }
            assertTrue(received.isEmpty());
            // nouvel essai : le lot en attente part d'abord, puis le suivant (vide de nouveaux tickets)
            agent.syncOnce();
            assertEquals(0, agent.pendingBatches());
            assertEquals(2, received.size());
            assertEquals(3, ((List<Object>) received.get(0).get("tickets")).size());
            assertEquals(0, ((List<Object>) received.get(1).get("tickets")).size());
            assertNotEquals(received.get(0).get("batchId"), received.get(1).get("batchId"));
            assertEquals("Bearer jeton-secret", auth[0]);
            // un nouvel agent (redemarrage de la caisse) reprend au curseur : pas de doublon
            new PortalAgent(l, c, dl, Json.obj()).syncOnce();
            assertEquals(0, ((List<Object>) received.get(2).get("tickets")).size());
            assertNotNull(received.get(2).get("users"));
        } finally {
            srv.stop(0);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, Object body) throws java.io.IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }
}
