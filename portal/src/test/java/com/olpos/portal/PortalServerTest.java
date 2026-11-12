package com.olpos.portal;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Portail de bout en bout en HTTP sur 127.0.0.1 avec deux caisses simulees :
 * association, lots, connexion deleguee, isolation, lecture seule,
 * limitation des essais, expiration et revocation des sessions.
 */
@SuppressWarnings("unchecked")
public class PortalServerTest {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    final long[] skew = {0};
    PortalDb db;
    PortalService portal;
    PortalServer server;
    String base;
    FakeTill a;
    FakeTill b;
    final HttpClient http = HttpClient.newHttpClient();
    long today;

    @Before
    public void demarrer() throws Exception {
        File dir = new File("target/test-portal/" + UUID.randomUUID());
        assertTrue(dir.mkdirs());
        db = new PortalDb(dir);
        portal = new PortalService(db, () -> System.currentTimeMillis() + skew[0]);
        server = new PortalServer(portal, false, false);
        server.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + server.port();
        today = LocalDate.now(PARIS).atStartOfDay(PARIS).toInstant().toEpochMilli();

        a = till("Chez Alice", "chez-alice");
        b = till("Bistrot Bob", "bistrot-bob");
        // les deux restaurants ont un compte "Administrator" (meme identifiant interne "0")
        a.user("0", "Administrator", "mdp-alice", true);
        a.user("2", "Employee", "", true);          // pas de mot de passe
        a.user("5", "Serveur", "mdp-serveur", false);
        b.user("0", "Administrator", "mdp-bob", true);
        a.start();
        b.start();
        Map<String, Object> ba = batch(FakeTill.ticket("t-a1", today + 12 * 3600_000L, 1, 0, "Alice", "Steak", "Plats",
                2, 14.0, "cash"), FakeTill.ticket("t-a2", today + 13 * 3600_000L + 60_000L, 2, 0, "Paul", "Cafe",
                "Boissons", 3, 2.0, "magcard"), FakeTill.ticket("t-a3", today + 14 * 3600_000L, 3, 1, "Alice", "Cafe",
                "Boissons", -1, 2.0, "cashrefund"));
        assertEquals(200, a.sync(ba).statusCode());
        assertEquals(200, b.sync(batch(FakeTill.ticket("t-b1", today + 12 * 3600_000L, 1, 0, "Bob", "Pizza", "Plats",
                1, 100.0, "cash"))).statusCode());
    }

    @After
    public void arreter() throws Exception {
        a.stop();
        b.stop();
        server.stop();
        db.close();
    }

    FakeTill till(String name, String slug) throws Exception {
        Map<String, Object> r = portal.createRestaurant(name, slug);
        FakeTill t = new FakeTill(base, () -> System.currentTimeMillis() + skew[0]);
        assertEquals(200, t.pair((String) r.get("pairingCode")));
        return t;
    }

    static Map<String, Object> batch(Map<String, Object>... tickets) {
        List<Object> l = new ArrayList<>(List.of((Object[]) tickets));
        return Json.obj("batchId", UUID.randomUUID().toString(), "meta", Json.obj("currency", "EUR", "locale", "fr-FR",
                "timezone", "Europe/Paris", "version", "5.0.4"), "tickets", l, "closures", new ArrayList<Object>(),
                "openOrders", new ArrayList<Object>());
    }

    // ------------------------------------------------------------------ client "navigateur"

    static final class Res {
        int status;
        String body;
        String cookie;
        HttpResponse<String> raw;

        Map<String, Object> json() throws Exception {
            return (Map<String, Object>) Json.parse(body);
        }
    }

    Res call(String method, String path, String cookie, Object body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(base + path));
        if (body != null) {
            r.header("Content-Type", "application/json");
        }
        r.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (cookie != null) {
            r.header("Cookie", PortalServer.COOKIE + "=" + cookie);
        }
        HttpResponse<String> h = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
        Res x = new Res();
        x.status = h.statusCode();
        x.body = h.body();
        x.raw = h;
        for (String s : h.headers().allValues("Set-Cookie")) {
            if (s.startsWith(PortalServer.COOKIE + "=")) {
                x.cookie = s.substring(PortalServer.COOKIE.length() + 1, s.indexOf(';'));
            }
        }
        return x;
    }

    /** Connexion comme le fait app.js : defi, chiffrement pour la caisse, envoi. */
    Res login(String slug, String user, String password, String code) throws Exception {
        Res c = call("GET", "/api/patron/challenge?restaurant=" + slug, null, null);
        if (c.status != 200) {
            return c;
        }
        Map<String, Object> ch = c.json();
        String ct = FakeTill.browserEncrypt((String) ch.get("encKey"), Json.obj("cid", ch.get("challengeId"),
                "nonce", ch.get("nonce"), "user", user, "password", password, "totp", code));
        return call("POST", "/api/patron/login", null, Json.obj("restaurant", slug, "user", user,
                "challengeId", ch.get("challengeId"), "ciphertext", ct));
    }

    String session(FakeTill t, String password) throws Exception {
        Res r = login(t.slug, "Administrator", password, FakeTill.CODE);
        assertEquals(r.body, 200, r.status);
        assertNotNull(r.cookie);
        return r.cookie;
    }

    // ================================================================== association

    @Test
    public void codeAssociationAUsageUniqueEtExpirant() throws Exception {
        String rid = portal.ridForSlug("chez-alice");
        String code = portal.newPairingCode(rid);
        FakeTill t = new FakeTill(base, System::currentTimeMillis);
        assertEquals(200, t.pair(code));
        FakeTill t2 = new FakeTill(base, System::currentTimeMillis);
        assertEquals("deja utilise", 403, t2.pair(code));
        assertEquals("inconnu", 403, t2.pair("AAAA-BBBB-CCCC"));
        String old = portal.newPairingCode(rid);
        skew[0] = PortalService.PAIRING_TTL + 60_000L;
        assertEquals("expire", 403, t2.pair(old));
        // la nouvelle association remplace l'ancienne caisse : son jeton n'est plus accepte
        skew[0] = 0;
        a.stop();
        assertEquals(401, a.sync(batch()).statusCode());
    }

    @Test
    public void caisseSansJetonRefusee() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/device/sync"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, r.statusCode());
    }

    // ================================================================== lots

    @Test
    public void lotRejoueEtTicketRenvoyeSansDoublon() throws Exception {
        Map<String, Object> l = batch(FakeTill.ticket("t-a9", today + 15 * 3600_000L, 9, 0, "Alice", "The", "Boissons",
                1, 3.0, "cash"));
        assertTrue(a.sync(l).body().contains("\"ok\""));
        assertTrue("meme lot renvoye apres coupure", a.sync(l).body().contains("\"duplicate\""));
        // meme ticket dans un nouveau lot (curseur perdu) : remplace, pas ajoute
        assertEquals(200, a.sync(batch(FakeTill.ticket("t-a9", today + 15 * 3600_000L, 9, 0, "Alice", "The",
                "Boissons", 1, 3.0, "cash"))).statusCode());
        String c = session(a, "mdp-alice");
        Map<String, Object> s = call("GET", "/api/patron/summary?period=day", c, null).json();
        assertEquals(3L, s.get("ticketCount"));
    }

    // ================================================================== statistiques

    @Test
    public void statistiquesDuJour() throws Exception {
        String c = session(a, "mdp-alice");
        Map<String, Object> s = call("GET", "/api/patron/summary?period=day", c, null).json();
        Map<String, Object> sales = (Map<String, Object>) s.get("sales");
        assertEquals(34.0, d(sales.get("base")), 0.001);          // 28 + 6
        assertEquals(3.4, d(sales.get("tax")), 0.001);
        assertEquals(37.4, d(sales.get("total")), 0.001);
        assertEquals(-2.2, d(((Map<String, Object>) s.get("refunds")).get("total")), 0.001);
        assertEquals(35.2, d(((Map<String, Object>) s.get("net")).get("total")), 0.001);
        assertEquals(2L, s.get("ticketCount"));
        assertEquals(1L, s.get("refundCount"));
        assertEquals(18.7, d(s.get("averageBasket")), 0.001);
        List<Object> pays = (List<Object>) s.get("payments");
        assertEquals(3, pays.size());
        List<Object> hours = (List<Object>) s.get("hours");
        assertEquals("heures dans le fuseau du restaurant", 12L, ((Map<String, Object>) hours.get(0)).get("hour"));
        Map<String, Object> top = (Map<String, Object>) ((List<Object>) s.get("products")).get(0);
        assertEquals("Steak", top.get("name"));
        // periode personnalisee invalide : 400, pas d'erreur interne
        assertEquals(400, call("GET", "/api/patron/summary?period=custom&from=x&to=y", c, null).status);
        assertEquals(400, call("GET", "/api/patron/summary?period=custom&from=2026-01-02&to=2026-01-01", c, null).status);
        // historique et detail
        List<Object> tickets = (List<Object>) call("GET", "/api/patron/tickets?period=day", c, null).json().get("tickets");
        assertEquals(3, tickets.size());
        Res t = call("GET", "/api/patron/tickets/t-a1", c, null);
        assertEquals(200, t.status);
        assertEquals("Steak", ((Map<String, Object>) ((List<Object>) ((Map<String, Object>) t.json().get("ticket"))
                .get("lines")).get(0)).get("name"));
    }

    @Test
    public void exportsCsvEtPdf() throws Exception {
        String c = session(a, "mdp-alice");
        Res csv = call("GET", "/api/patron/export.csv?period=day", c, null);
        assertEquals(200, csv.status);
        assertTrue(csv.raw.headers().firstValue("Content-Type").get().startsWith("text/csv"));
        assertTrue(csv.body.contains("Steak"));
        HttpResponse<byte[]> pdf = http.send(HttpRequest.newBuilder(URI.create(base + "/api/patron/export.pdf?period=day"))
                .header("Cookie", PortalServer.COOKIE + "=" + c).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, pdf.statusCode());
        assertTrue(new String(pdf.body(), 0, 5, StandardCharsets.US_ASCII).startsWith("%PDF"));
        // protection contre les formules dans le tableur
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"=HYPERLINK(\"x\")", "-1,50 €", "@cmd", "-2+3"});
        String out = new String(Exports.csv(rows), StandardCharsets.UTF_8);
        assertTrue(out.contains("'=HYPERLINK"));
        assertTrue(out.contains(";-1,50 €;"));
        assertTrue(out.contains("'@cmd"));
        assertTrue(out.contains("'-2+3"));
    }

    // ================================================================== connexion et isolation

    @Test
    public void connexionDelegueeEtIsolationEntreRestaurants() throws Exception {
        String ca = session(a, "mdp-alice");
        String cb = session(b, "mdp-bob");
        Map<String, Object> meA = call("GET", "/api/patron/me", ca, null).json();
        assertEquals("Chez Alice", ((Map<String, Object>) meA.get("restaurant")).get("name"));
        assertEquals(true, meA.get("readOnly"));
        // le patron A ne voit que A, meme avec le meme identifiant "Administrator"/"0"
        Map<String, Object> sa = call("GET", "/api/patron/summary?period=day", ca, null).json();
        Map<String, Object> sb = call("GET", "/api/patron/summary?period=day", cb, null).json();
        assertEquals(37.4, d(((Map<String, Object>) sa.get("sales")).get("total")), 0.001);
        assertEquals(110.0, d(((Map<String, Object>) sb.get("sales")).get("total")), 0.001);
        assertEquals(404, call("GET", "/api/patron/tickets/t-b1", ca, null).status);
        assertEquals(404, call("GET", "/api/patron/tickets/t-a1", cb, null).status);
        assertFalse(call("GET", "/api/patron/tickets?period=month", ca, null).body.contains("t-b1"));
        assertFalse(call("GET", "/api/patron/export.csv?period=month", ca, null).body.contains("Pizza"));
        // le mot de passe de A ne fonctionne pas pour B
        assertEquals(401, login(b.slug, "Administrator", "mdp-alice", FakeTill.CODE).status);
        // un bloc chiffre pour la caisse A, presente au defi de B : refuse
        Res chA = call("GET", "/api/patron/challenge?restaurant=" + a.slug, null, null);
        Res chB = call("GET", "/api/patron/challenge?restaurant=" + b.slug, null, null);
        String ct = FakeTill.browserEncrypt((String) chA.json().get("encKey"), Json.obj("cid",
                chA.json().get("challengeId"), "nonce", chA.json().get("nonce"), "user", "Administrator",
                "password", "mdp-alice", "totp", FakeTill.CODE));
        Res cross = call("POST", "/api/patron/login", null, Json.obj("restaurant", b.slug, "user", "Administrator",
                "challengeId", chA.json().get("challengeId"), "ciphertext", ct));
        assertEquals("defi d'un autre restaurant", 400, cross.status);
        Res cross2 = call("POST", "/api/patron/login", null, Json.obj("restaurant", b.slug, "user", "Administrator",
                "challengeId", chB.json().get("challengeId"), "ciphertext", ct));
        assertEquals("bloc illisible pour la caisse B", 401, cross2.status);
        // le mot de passe n'est jamais stocke par le portail
        assertFalse(dump().contains("mdp-alice"));
        assertFalse(dump().contains("mdp-bob"));
    }

    static double d(Object o) {
        return ((Number) o).doubleValue();
    }

    String dump() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String t : new String[] {"sessions", "auth_requests", "users_state", "devices", "login_failures"}) {
            sb.append(db.query("SELECT * FROM " + t)).append('\n');
        }
        return sb.toString();
    }

    @Test
    public void refusMotDePasseCodeCompteSansMotDePasseEtNonAutorise() throws Exception {
        assertEquals(401, login(a.slug, "Administrator", "faux", FakeTill.CODE).status);
        assertEquals(401, login(a.slug, "Administrator", "mdp-alice", "000000").status);
        Res np = login(a.slug, "Employee", "", FakeTill.CODE);
        assertEquals(401, np.status);
        assertTrue(np.body.contains("pas de mot de passe"));
        Res ne = login(a.slug, "Serveur", "mdp-serveur", FakeTill.CODE);
        assertEquals(401, ne.status);
        assertTrue(ne.body.contains("pas autorise"));
        assertEquals(404, call("GET", "/api/patron/challenge?restaurant=inconnu", null, null).status);
    }

    @Test
    public void limitationDesEssais() throws Exception {
        for (int i = 0; i < PortalService.MAX_FAILURES; i++) {
            assertEquals(401, login(a.slug, "Administrator", "faux" + i, FakeTill.CODE).status);
        }
        Res r = login(a.slug, "Administrator", "mdp-alice", FakeTill.CODE);
        assertEquals("bloque meme avec le bon mot de passe", 429, r.status);
        skew[0] = PortalService.LOCK_MS + 60_000L;
        a.pollOnce(0);   // la caisse reste en ligne apres le saut d'horloge
        b.pollOnce(0);
        assertEquals(200, login(a.slug, "Administrator", "mdp-alice", FakeTill.CODE).status);
    }

    @Test
    public void preuveSigneeParUneAutreCleRefusee() throws Exception {
        a.forgeWith = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Res r = login(a.slug, "Administrator", "mdp-alice", FakeTill.CODE);
        assertEquals(502, r.status);
        assertNull(r.cookie);
    }

    @Test
    public void caisseHorsLigne() throws Exception {
        a.stop();
        skew[0] = PortalService.DEVICE_OFFLINE_AFTER + 5_000L;
        Res r = call("GET", "/api/patron/challenge?restaurant=" + a.slug, null, null);
        assertEquals(503, r.status);
        assertTrue(r.body.contains("hors ligne"));
    }

    // ================================================================== lecture seule et sessions

    @Test
    public void lectureSeule() throws Exception {
        String c = session(a, "mdp-alice");
        for (String m : new String[] {"POST", "PUT", "DELETE", "PATCH"}) {
            for (String p : new String[] {"summary", "tickets", "tickets/t-a1", "me", "closures", "open-orders"}) {
                Res r = call(m, "/api/patron/" + p, c, Json.obj());
                assertEquals(m + " " + p, 405, r.status);
            }
        }
        assertEquals(401, call("GET", "/api/patron/summary", null, null).status);
        assertEquals(401, call("GET", "/api/patron/summary", "jeton-invente", null).status);
    }

    @Test
    public void expirationRevocationEtDeconnexion() throws Exception {
        String c = session(a, "mdp-alice");
        assertEquals(200, call("GET", "/api/patron/me", c, null).status);
        // inactivite de 31 minutes : session expiree
        skew[0] = PortalService.SESSION_IDLE + 60_000L;
        assertEquals(401, call("GET", "/api/patron/me", c, null).status);
        skew[0] = 0;
        // changement de mot de passe a la caisse : nouvelle empreinte => session fermee
        a.pollOnce(0);
        c = session(a, "mdp-alice");
        assertEquals(200, call("GET", "/api/patron/me", c, null).status);
        a.users.get("0")[3] = UUID.randomUUID().toString();
        a.pollOnce(0);
        assertEquals(401, call("GET", "/api/patron/me", c, null).status);
        // droit retire a la caisse : plus de connexion
        a.users.get("0")[2] = "false";
        a.pollOnce(0);
        assertEquals(401, login(a.slug, "Administrator", "mdp-alice", FakeTill.CODE).status);
        // deconnexion
        String cb = session(b, "mdp-bob");
        assertEquals(200, call("POST", "/api/patron/logout", cb, Json.obj()).status);
        assertEquals(401, call("GET", "/api/patron/me", cb, null).status);
        // duree maximale de 12 h meme avec activite
        String cb2 = session(b, "mdp-bob");
        for (long t = 0; t <= PortalService.SESSION_MAX; t += 20 * 60_000L) {
            skew[0] = t;
            b.pollOnce(0);
            call("GET", "/api/patron/me", cb2, null);
        }
        skew[0] = PortalService.SESSION_MAX + 60_000L;
        b.pollOnce(0);
        assertEquals(401, call("GET", "/api/patron/me", cb2, null).status);
    }

    @Test
    public void entetesDeSecuriteEtCookie() throws Exception {
        Res page = call("GET", "/", null, null);
        assertEquals(200, page.status);
        assertTrue(page.raw.headers().firstValue("Content-Security-Policy").get().contains("default-src 'self'"));
        assertEquals("DENY", page.raw.headers().firstValue("X-Frame-Options").get());
        assertEquals(200, call("GET", "/app.js", null, null).status);
        assertEquals(404, call("GET", "/../pom.xml", null, null).status);
        Res r = login(a.slug, "Administrator", "mdp-alice", FakeTill.CODE);
        String set = r.raw.headers().allValues("Set-Cookie").get(0);
        assertTrue(set.contains("HttpOnly"));
        assertTrue(set.contains("SameSite=Strict"));
        assertFalse("le jeton de session n'est pas dans la reponse JSON", r.body.contains(r.cookie));
    }
}
