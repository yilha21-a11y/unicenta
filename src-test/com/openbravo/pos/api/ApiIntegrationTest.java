package com.openbravo.pos.api;

import com.openbravo.data.loader.Session;
import com.openbravo.pos.forms.AppConfig;
import com.openbravo.pos.forms.AppViewConnection;
import com.openbravo.pos.testenv.TestEnvironment;
import com.openbravo.pos.util.Hashcypher;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.AfterClass;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test de bout en bout : base Derby de test creee avec le script officiel de la
 * caisse (via TestEnvironment), API demarree comme dans JRootApp (connexion
 * supplementaire), appels HTTP reels sur 127.0.0.1.
 */
@SuppressWarnings("unchecked")
public class ApiIntegrationTest {

    private static final String PASSWORD = "mdp-test-" + System.nanoTime();
    private static TestEnvironment.Result env;
    private static AppConfig config;
    private static Session tillSession;   // joue le role de la session de la caisse
    private static Session apiSession;
    private static ApiServer server;
    private static String base;

    @BeforeClass
    public static void demarrer() throws Exception {
        File root = new File(".").getCanonicalFile();
        File dir = new File(root, "build/test-work/api-integration");
        env = TestEnvironment.create(dir, true, 0, PASSWORD, root);

        config = new AppConfig(env.configFile);
        config.load();
        config.setProperty("api.bind", "127.0.0.1");
        config.setProperty("api.port", "0");            // port libre choisi par le systeme
        config.setProperty("api.login.maxattempts", "3");

        // Meme enchainement que la caisse : session principale puis session de l'API
        tillSession = AppViewConnection.createSession(config);
        apiSession = AppViewConnection.createAdditionalSession(config);
        server = new ApiServer(new ApiConfig(config), new MobileDataService(apiSession));
        server.start();
        base = "http://127.0.0.1:" + server.getPort() + "/api/v1";
    }

    @AfterClass
    public static void arreter() {
        if (server != null) {
            server.stop();
        }
        if (apiSession != null) {
            apiSession.close();
        }
        if (tillSession != null) {
            tillSession.close();
        }
    }

    // ------------------------------------------------------------------ public

    @Test
    public void sante() throws Exception {
        Response r = call("GET", "/health", null, null);
        assertEquals(200, r.status);
        assertEquals("ok", r.json().get("status"));
        assertEquals("3.81", r.json().get("version"));
        assertEquals(1L, r.json().get("apiVersion"));
        assertTrue(r.contentType.startsWith("application/json"));
    }

    @Test
    public void listeDesServeursSeulementAvecMotDePasseEtPermission() throws Exception {
        Response r = call("GET", "/users", null, null);
        assertEquals(200, r.status);
        List<String> names = names((List<Object>) r.json().get("users"));
        assertTrue(names.contains("Serveur Test"));
        // Les comptes par defaut n'ont pas de mot de passe : jamais proposes au telephone
        assertFalse(names.contains("Administrator"));
        assertFalse(names.contains("Guest"));
        // La reponse ne contient ni mot de passe ni role
        assertFalse(r.body.contains("sha1:"));
        assertFalse(r.body.toLowerCase().contains("password"));
    }

    // ------------------------------------------------------------------ connexion

    @Test
    public void connexionReussieEtPermissions() throws Exception {
        Response r = login(env.waiterId, PASSWORD);
        assertEquals(r.body, 200, r.status);
        String token = (String) r.json().get("token");
        assertNotNull(token);
        assertEquals("Serveur Test", ((Map<String, Object>) r.json().get("user")).get("name"));
        List<Object> perms = (List<Object>) r.json().get("permissions");
        assertTrue(perms.contains("mobile.Access"));      // role Employee (modele Role.Employee.xml)
        assertTrue(perms.contains("sales.PrintKitchen"));

        Response me = call("GET", "/me", token, null);
        assertEquals(200, me.status);
        assertEquals(env.waiterId, ((Map<String, Object>) me.json().get("user")).get("id"));
    }

    @Test
    public void mauvaisMotDePasse() throws Exception {
        Response r = login(env.waiterId, "faux");
        assertEquals(401, r.status);
        assertEquals("invalid_credentials", error(r));
        // le compteur d'echecs est remis a zero par une connexion reussie
        assertEquals(200, login(env.waiterId, PASSWORD).status);
    }

    @Test
    public void compteInconnuMemeMessageQueMauvaisMotDePasse() throws Exception {
        Response r = login("inconnu-" + System.nanoTime(), "x");
        assertEquals(401, r.status);
        assertEquals("invalid_credentials", error(r));
    }

    @Test
    public void compteSansMotDePasseRefuse() throws Exception {
        Response r = login("0", "");   // Administrator, sans mot de passe dans la base neuve
        assertEquals(403, r.status);
        assertEquals("password_required", error(r));
    }

    @Test
    public void roleSansPermissionMobileRefuse() throws Exception {
        String id = "guest-" + System.nanoTime();
        try (PreparedStatement ps = tillSession.getConnection().prepareStatement(
                "INSERT INTO PEOPLE(ID, NAME, APPPASSWORD, ROLE, VISIBLE) VALUES (?, ?, ?, '3', 1)")) {
            ps.setString(1, id);
            ps.setString(2, "Invite " + id);
            ps.setString(3, Hashcypher.hashString("abc"));
            ps.executeUpdate();
        }
        Response r = login(id, "abc");
        assertEquals(403, r.status);
        assertEquals("forbidden", error(r));
        assertFalse(names((List<Object>) call("GET", "/users", null, null).json().get("users")).contains("Invite " + id));
    }

    @Test
    public void blocageApresTroisEchecs() throws Exception {
        String id = "bloque-" + System.nanoTime();
        try (PreparedStatement ps = tillSession.getConnection().prepareStatement(
                "INSERT INTO PEOPLE(ID, NAME, APPPASSWORD, ROLE, VISIBLE) VALUES (?, ?, ?, '2', 1)")) {
            ps.setString(1, id);
            ps.setString(2, "Bloque " + id);
            ps.setString(3, Hashcypher.hashString("bon"));
            ps.executeUpdate();
        }
        assertEquals(401, login(id, "x").status);
        assertEquals(401, login(id, "x").status);
        Response third = login(id, "x");
        assertEquals(429, third.status);
        assertEquals("too_many_attempts", error(third));
        // meme le bon mot de passe est refuse pendant le blocage
        assertEquals(429, login(id, "bon").status);
        // les autres comptes ne sont pas touches
        assertEquals(200, login(env.waiterId, PASSWORD).status);
    }

    @Test
    public void deconnexionInvalideLeJeton() throws Exception {
        String token = token();
        assertEquals(200, call("POST", "/auth/logout", token, "").status);
        assertEquals(401, call("GET", "/me", token, null).status);
    }

    @Test
    public void accesSansJetonOuJetonInvalide() throws Exception {
        assertEquals(401, call("GET", "/floors", null, null).status);
        assertEquals(401, call("GET", "/catalog", "jeton-invente", null).status);
    }

    @Test
    public void requetesInvalides() throws Exception {
        String token = token();
        assertEquals(405, call("POST", "/floors", token, "{}").status);
        assertEquals(404, call("GET", "/inexistant", token, null).status);
        assertEquals(400, call("POST", "/auth/login", null, "pas du json").status);
        assertEquals(400, call("POST", "/auth/login", null, "{\"userId\":\"x\"}").status);
        assertEquals(400, call("GET", "/catalog/products", token, null).status);
    }

    // ------------------------------------------------------------------ catalogue

    @Test
    public void categories() throws Exception {
        Response r = call("GET", "/catalog/categories", token(), null);
        assertEquals(200, r.status);
        List<String> names = names((List<Object>) r.json().get("categories"));
        assertTrue(names.toString(), names.containsAll(java.util.Arrays.asList("Entrees", "Plats", "Boissons")));
    }

    @Test
    public void produitsEtPrixTTCCalculesParLesTaxesDeLaCaisse() throws Exception {
        Response r = call("GET", "/catalog/products?category=cat-plats", token(), null);
        assertEquals(200, r.status);
        Map<String, Object> steak = byName((List<Object>) r.json().get("products"), "Steak frites");
        assertEquals(14.0, number(steak.get("priceSell")), 1e-9);       // HT en base
        assertEquals(0.10, number(steak.get("taxRate")), 1e-9);         // "Tax Standard" 10 %
        assertEquals(15.40, number(steak.get("priceSellTaxed")), 1e-9);
        assertEquals(Boolean.TRUE, steak.get("kitchen"));
        assertNotNull(steak.get("priceDisplay"));

        Response b = call("GET", "/catalog/products?category=cat-boissons", token(), null);
        Map<String, Object> eau = byName((List<Object>) b.json().get("products"), "Eau minerale");
        assertEquals(0.0, number(eau.get("taxRate")), 1e-9);            // "Tax Exempt"
        assertEquals(2.0, number(eau.get("priceSellTaxed")), 1e-9);
        assertEquals(Boolean.FALSE, eau.get("kitchen"));
        Map<String, Object> cafe = byName((List<Object>) b.json().get("products"), "Cafe");
        assertEquals(1.98, number(cafe.get("priceSellTaxed")), 1e-9);
    }

    @Test
    public void catalogueComplet() throws Exception {
        Response r = call("GET", "/catalog", token(), null);
        assertEquals(200, r.status);
        List<String> products = names((List<Object>) r.json().get("products"));
        for (String p : new String[] {"Salade cesar", "Soupe du jour", "Steak frites", "Poisson grille",
            "Eau minerale", "Cafe", "The a la menthe"}) {
            assertTrue(p, products.contains(p));
        }
    }

    // ------------------------------------------------------------------ tables

    @Test
    public void sallesEtTablesAvecOccupation() throws Exception {
        String token = token();
        Response r = call("GET", "/floors", token, null);
        assertEquals(200, r.status);
        List<Object> floors = (List<Object>) r.json().get("floors");
        assertEquals(1, floors.size());
        List<Object> tables = (List<Object>) ((Map<String, Object>) floors.get(0)).get("tables");
        assertEquals(12, tables.size());
        List<String> names = names(tables);
        assertEquals("Table 1", names.get(0));
        assertEquals("Table 2", names.get(1));          // tri naturel (pas "Table 10")
        assertEquals("Table 12", names.get(11));
        assertEquals(Boolean.FALSE, byName(tables, "Table 3").get("occupied"));

        // Une commande en cours sur la table 3 (comme l'ecrit la caisse dans SHAREDTICKETS)
        try (PreparedStatement ps = tillSession.getConnection().prepareStatement(
                "INSERT INTO SHAREDTICKETS(ID, NAME) VALUES ('3', 'Table 3')")) {
            ps.executeUpdate();
        }
        try {
            List<Object> after = (List<Object>) ((Map<String, Object>) ((List<Object>)
                    call("GET", "/floors", token, null).json().get("floors")).get(0)).get("tables");
            assertEquals(Boolean.TRUE, byName(after, "Table 3").get("occupied"));
            assertEquals(Boolean.FALSE, byName(after, "Table 4").get("occupied"));
        } finally {
            try (PreparedStatement ps = tillSession.getConnection().prepareStatement(
                    "DELETE FROM SHAREDTICKETS WHERE ID = '3'")) {
                ps.executeUpdate();
            }
        }
    }

    @Test
    public void pageWebServieParLaCaisse() throws Exception {
        String root = "http://127.0.0.1:" + server.getPort();
        HttpURLConnection c = (HttpURLConnection) new URL(root + "/").openConnection();
        assertEquals(200, c.getResponseCode());
        assertTrue(c.getContentType().startsWith("text/html"));
        assertTrue(c.getHeaderField("Content-Security-Policy").contains("script-src 'self'"));
        assertEquals("nosniff", c.getHeaderField("X-Content-Type-Options"));
        String html = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
        assertTrue(html.contains("<title>Commandes</title>"));
        assertTrue(html.contains("app.js"));
        for (String f : new String[] {"/app.js", "/app.css"}) {
            HttpURLConnection a = (HttpURLConnection) new URL(root + f).openConnection();
            assertEquals(f, 200, a.getResponseCode());
            assertTrue(f, readAll(a.getInputStream()).length > 500);
        }
        // seuls les fichiers de la page sont accessibles
        for (String f : new String[] {"/Json.class", "/../ApiServer.class", "/com/openbravo/pos/api/web/app.js",
            "/unicentaopos.properties"}) {
            HttpURLConnection a = (HttpURLConnection) new URL(root + f).openConnection();
            assertEquals(f, 404, a.getResponseCode());
        }
        // l'API reste prioritaire sur la page
        assertEquals(200, call("GET", "/health", null, null).status);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toByteArray();
    }

    @Test
    public void triNaturel() {
        assertTrue(MobileDataService.naturalCompare("Table 2", "Table 10") < 0);
        assertTrue(MobileDataService.naturalCompare("Table 10", "Table 9") > 0);
        assertTrue(MobileDataService.naturalCompare("Bar", "table 1") < 0);
        assertEquals(0, MobileDataService.naturalCompare("Table 1", "Table 1"));
    }

    // ================================================================== outils

    private static final class Response {
        int status;
        String body;
        String contentType;

        Map<String, Object> json() throws Json.ParseException {
            return (Map<String, Object>) Json.parse(body);
        }
    }

    private static Response login(String userId, String password) throws Exception {
        return call("POST", "/auth/login", null, Json.write(Json.obj("userId", userId, "password", password)));
    }

    private static String token() throws Exception {
        Response r = login(env.waiterId, PASSWORD);
        assertEquals(r.body, 200, r.status);
        return (String) r.json().get("token");
    }

    private static Response call(String method, String path, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        Response r = new Response();
        r.status = c.getResponseCode();
        r.contentType = String.valueOf(c.getContentType());
        InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
        }
        r.body = new String(out.toByteArray(), StandardCharsets.UTF_8);
        return r;
    }

    private static String error(Response r) throws Json.ParseException {
        return (String) ((Map<String, Object>) r.json().get("error")).get("code");
    }

    private static List<String> names(List<Object> items) {
        List<String> l = new ArrayList<>();
        for (Object o : items) {
            l.add((String) ((Map<String, Object>) o).get("name"));
        }
        return l;
    }

    private static Map<String, Object> byName(List<Object> items, String name) {
        for (Object o : items) {
            if (name.equals(((Map<String, Object>) o).get("name"))) {
                return (Map<String, Object>) o;
            }
        }
        throw new AssertionError("Introuvable : " + name);
    }

    private static double number(Object o) {
        return ((Number) o).doubleValue();
    }
}
