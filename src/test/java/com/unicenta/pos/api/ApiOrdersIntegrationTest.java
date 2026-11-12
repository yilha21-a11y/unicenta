package com.unicenta.pos.api;

import static com.unicenta.pos.api.ApiTestClient.add;
import static com.unicenta.pos.api.ApiTestClient.line;
import static com.unicenta.pos.api.ApiTestClient.lines;
import static com.unicenta.pos.api.ApiTestClient.num;
import static com.unicenta.pos.api.ApiTestClient.remove;
import static com.unicenta.pos.api.ApiTestClient.setNote;
import static com.unicenta.pos.api.ApiTestClient.setQuantity;
import static com.unicenta.pos.api.ApiTestClient.version;
import com.unicenta.data.loader.Session;
import com.unicenta.pos.forms.AppConfig;
import com.unicenta.pos.forms.AppViewConnection;
import com.unicenta.pos.sales.restaurant.TableLocks;
import com.unicenta.pos.sales.restaurant.TableTicketStore;
import com.unicenta.pos.testenv.TestEnvironment;
import com.unicenta.pos.ticket.TicketInfo;
import com.unicenta.pos.ticket.TicketLineInfo;
import com.unicenta.pos.util.Hashcypher;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.AfterClass;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Etape 4 : commandes depuis l'API. Base Derby de test, vraie API HTTP, et
 * une seconde connexion qui joue le role de la caisse (verrou + ecritures).
 */
@SuppressWarnings("unchecked")
public class ApiOrdersIntegrationTest {

    private static final String PASSWORD = "serveur-" + System.nanoTime();
    private static final String MIN_PASSWORD = "minimal-" + System.nanoTime();
    private static TestEnvironment.Result env;
    private static AppConfig config;
    private static Session tillSession;
    private static Session apiSession;
    private static TableTicketStore tillStore;
    private static TableLocks locks;
    private static ApiServer server;
    private static ApiTestClient api;
    private static String token;       // serveur avec sales.EditLines (role Employee)
    private static String minToken;    // serveur avec seulement mobile.Access
    private static com.unicenta.pos.ticket.TaxInfo taxStandard;   // taxe 10 % de la base de test

    @BeforeClass
    public static void demarrer() throws Exception {
        File root = new File(".").getCanonicalFile();
        env = TestEnvironment.create(new File(root, "build/test-work/api-orders"), true, 0, PASSWORD, root);
        config = new AppConfig(env.configFile);
        config.load();
        config.setProperty("api.bind", "127.0.0.1");
        config.setProperty("api.port", "0");

        tillSession = AppViewConnection.createSession(config);
        apiSession = AppViewConnection.createAdditionalSession(config);
        tillStore = new TableTicketStore(tillSession);
        com.unicenta.pos.forms.DataLogicSales dls = new com.unicenta.pos.forms.DataLogicSales();
        dls.init(tillSession);
        taxStandard = new com.unicenta.pos.sales.TaxesLogic(dls.getTaxList().list()).getTaxInfo("001");

        // Donnees supplementaires : tables, produit a prix variable, role minimal
        exec("INSERT INTO places(ID, NAME, X, Y, FLOOR, WIDTH, HEIGHT) VALUES ('x1', 'Terrasse 1', 0, 0, '0', 100, 50)");
        exec("INSERT INTO places(ID, NAME, X, Y, FLOOR, WIDTH, HEIGHT) VALUES ('x2', 'Terrasse 2', 0, 0, '0', 100, 50)");
        exec("INSERT INTO places(ID, NAME, X, Y, FLOOR, WIDTH, HEIGHT) VALUES ('x3', 'Terrasse 3', 0, 0, '0', 100, 50)");
        exec("INSERT INTO PRODUCTS(ID, REFERENCE, CODE, NAME, PRICEBUY, PRICESELL, CATEGORY, TAXCAT, ISVPRICE) "
                + "VALUES ('p-vprice', 'V001', 'V001', 'Plat du marche', 0, 0, 'cat-plats', '001', 1)");
        try (PreparedStatement ps = tillSession.getConnection().prepareStatement(
                "INSERT INTO ROLES(ID, NAME, PERMISSIONS) VALUES ('r-min', 'Mobile minimal', ?)")) {
            ps.setBytes(1, "<permissions><class name=\"mobile.Access\"/></permissions>".getBytes(StandardCharsets.UTF_8));
            ps.executeUpdate();
        }
        try (PreparedStatement ps = tillSession.getConnection().prepareStatement(
                "INSERT INTO PEOPLE(ID, NAME, APPPASSWORD, ROLE, VISIBLE) VALUES ('u-min', 'Commis', ?, 'r-min', 1)")) {
            ps.setString(1, Hashcypher.hashString(MIN_PASSWORD));
            ps.executeUpdate();
        }

        locks = TableLocks.newIsolatedInstance();
        server = startServer();
        api = new ApiTestClient(server.getPort());
        token = api.login(env.waiterId, PASSWORD);
        minToken = api.login("u-min", MIN_PASSWORD);
    }

    private static ApiServer startServer() throws Exception {
        ApiConfig apiConfig = new ApiConfig(config);
        ApiServer s = new ApiServer(apiConfig, new MobileDataService(apiSession), new ApiSessions(apiConfig), locks);
        s.start();
        return s;
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

    private static void exec(String sql) throws Exception {
        try (PreparedStatement ps = tillSession.getConnection().prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    // ================================================================ creation / modification

    @Test
    public void tableLibrePuisCreationEtModifications() throws Exception {
        ApiTestClient.Response r0 = api.getOrder(token, "1");
        assertEquals(200, r0.status);
        assertEquals(Boolean.FALSE, r0.order().get("exists"));
        assertEquals(0L, version(r0.order()));

        // creation : le telephone envoie un prix bidon, il doit etre ignore
        Map<String, Object> steak = add("p-steak", 2, "Saignant, sans sel");
        steak.put("price", 0.01);
        ApiTestClient.Response r1 = api.changes(token, "1", rid(), 0, steak, add("p-eau", 1, null));
        assertEquals(r1.body, 200, r1.status);
        assertEquals(Boolean.TRUE, r1.json().get("applied"));
        Map<String, Object> o1 = r1.order();
        assertEquals(1L, version(o1));
        assertEquals(2, lines(o1).size());
        Map<String, Object> l0 = line(o1, 0);
        assertEquals("Steak frites", l0.get("name"));
        assertEquals(2.0, num(l0.get("quantity")), 0);
        assertEquals(14.0, num(l0.get("unitPrice")), 1e-9);          // prix de la base, pas celui du telephone
        assertEquals(15.40, num(l0.get("unitPriceTaxed")), 1e-9);
        assertEquals(30.80, num(l0.get("total")), 1e-9);
        assertEquals("Saignant, sans sel", l0.get("note"));
        assertEquals(Boolean.TRUE, l0.get("kitchen"));
        assertEquals(Boolean.FALSE, l0.get("sent"));
        assertEquals("Serveur Test", l0.get("addedBy"));
        assertEquals(30.0, num(o1.get("subtotal")), 1e-9);
        assertEquals(2.80, num(o1.get("taxes")), 1e-9);
        assertEquals(32.80, num(o1.get("total")), 1e-9);
        assertEquals("Serveur Test", o1.get("updatedBy"));
        assertEquals("mobile", o1.get("updatedSource"));

        // modifications : quantite, note, suppression
        String steakId = (String) l0.get("lineId");
        String eauId = (String) line(o1, 1).get("lineId");
        ApiTestClient.Response r2 = api.changes(token, "1", rid(), 1,
                setQuantity(steakId, 3), setNote(steakId, "A point"), remove(eauId), add("p-cafe", 1, null));
        assertEquals(r2.body, 200, r2.status);
        Map<String, Object> o2 = r2.order();
        assertEquals(2L, version(o2));
        assertEquals(2, lines(o2).size());
        assertEquals(steakId, line(o2, 0).get("lineId"));             // identifiant stable
        assertEquals(3.0, num(line(o2, 0).get("quantity")), 0);
        assertEquals("A point", line(o2, 0).get("note"));
        assertEquals("Cafe", line(o2, 1).get("name"));
        assertEquals(46.20 + 1.98, num(o2.get("total")), 1e-9);

        // la caisse lit exactement la meme commande
        TableTicketStore.Snapshot s = tillStore.load("1");
        assertEquals(2, s.getVersion());
        assertEquals(3.0, s.getTicket().getLine(0).getMultiply(), 0);
        assertEquals("A point", s.getTicket().getLine(0).getProperty("notes"));

        // et le plan de salle voit la table occupee avec le serveur
        Map<String, Object> table = findTable("Table 1");
        assertEquals(Boolean.TRUE, table.get("occupied"));
        assertEquals("Serveur Test", table.get("waiter"));
    }

    // ================================================================ conflits

    @Test
    public void versionPerimeeRefuseeAvecCommandeAJour() throws Exception {
        Map<String, Object> o1 = api.changes(token, "2", rid(), 0, add("p-soupe", 1, null)).order();
        String soupe = (String) line(o1, 0).get("lineId");
        // un 2e telephone ajoute un cafe (v2)
        assertEquals(200, api.changes(token, "2", rid(), 1, add("p-cafe", 1, null)).status);

        // le 1er telephone, toujours sur la v1, veut supprimer la soupe
        ApiTestClient.Response r = api.changes(token, "2", rid(), 1, remove(soupe));
        assertEquals(409, r.status);
        assertEquals("version_conflict", r.errorCode());
        assertTrue(r.errorMessage(), r.errorMessage().contains("Table 2"));
        assertTrue(r.errorMessage(), r.errorMessage().contains("Serveur Test"));
        assertTrue(r.errorMessage(), r.errorMessage().contains("Rien n'a ete enregistre"));
        // la reponse contient la commande a jour pour que le telephone se resynchronise
        assertEquals(2L, version(r.order()));
        assertEquals(2, lines(r.order()).size());
        // rien n'a change
        assertEquals(2, tillStore.load("2").getTicket().getLinesCount());
    }

    @Test
    public void creationSurTableDejaOccupee() throws Exception {
        TicketInfo t = new TicketInfo();
        t.addLine(new TicketLineInfo("Saisi a la caisse", "001", "0", 1.0, 3.0, taxStandard));
        tillStore.create("3", t, "Marie", "caisse");
        ApiTestClient.Response r = api.changes(token, "3", rid(), 0, add("p-cafe", 1, null));
        assertEquals(409, r.status);
        assertEquals("version_conflict", r.errorCode());
        assertTrue(r.errorMessage(), r.errorMessage().contains("existe deja"));
        assertTrue(r.errorMessage(), r.errorMessage().contains("Marie"));
        assertEquals(1, tillStore.load("3").getTicket().getLinesCount());
    }

    @Test
    public void tableOuverteALaCaisse() throws Exception {
        assertEquals(200, api.changes(token, "6", rid(), 0, add("p-the", 1, null)).status);
        assertTrue(locks.acquireTill("6", "Marie", 0));
        try {
            ApiTestClient.Response g = api.getOrder(token, "6");
            Map<String, Object> lockedBy = (Map<String, Object>) g.order().get("lockedBy");
            assertEquals("caisse", lockedBy.get("source"));
            assertEquals("Marie", lockedBy.get("who"));

            ApiTestClient.Response r = api.changes(token, "6", rid(), 1, add("p-cafe", 1, null));
            assertEquals(423, r.status);
            assertEquals("table_locked", r.errorCode());
            assertTrue(r.errorMessage(), r.errorMessage().contains("ouverte a la caisse par Marie"));
            assertEquals(1, tillStore.load("6").getTicket().getLinesCount());
        } finally {
            locks.releaseTill("6");
        }
        assertNull(api.getOrder(token, "6").order().get("lockedBy"));
        assertEquals(200, api.changes(token, "6", rid(), 1, add("p-cafe", 1, null)).status);
    }

    @Test
    public void commandeEncaisseeEntreTemps() throws Exception {
        assertEquals(200, api.changes(token, "12", rid(), 0, add("p-cafe", 1, null)).status);
        String lue = api.currentOrderId(token, "12");                     // commande affichee sur le telephone
        tillStore.delete("12", tillStore.load("12").getOrderId(), 1);   // la caisse encaisse la table
        ApiTestClient.Response r = api.changesOn(token, "12", lue, rid(), 1, add("p-cafe", 1, null));
        assertEquals(409, r.status);
        assertEquals("order_closed", r.errorCode());
        assertNull("aucune commande recreee", tillStore.load("12"));
    }

    @Test
    public void modificationsSimultaneesDepuisPlusieursTelephones() throws Exception {
        final int n = 8;
        final List<Integer> statuses = Collections.synchronizedList(new ArrayList<Integer>());
        final CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await();
                        statuses.add(api.changes(token, "7", rid(), 0, add("p-cafe", 1, null)).status);
                    } catch (Exception e) {
                        statuses.add(-1);
                    }
                }
            });
            threads.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(60000);
        }
        assertEquals(n, statuses.size());
        assertEquals("une seule creation acceptee : " + statuses, 1, Collections.frequency(statuses, 200));
        assertEquals("les autres recoivent un conflit : " + statuses, n - 1, Collections.frequency(statuses, 409));
        assertEquals(1, tillStore.load("7").getTicket().getLinesCount());
    }

    // ================================================================ doublons

    @Test
    public void requeteRepeteeApresCoupureWifi() throws Exception {
        String id = rid();
        ApiTestClient.Response r1 = api.changes(token, "4", id, 0, add("p-steak", 1, null));
        assertEquals(200, r1.status);
        assertEquals(Boolean.TRUE, r1.json().get("applied"));
        // le telephone n'a pas recu la reponse et renvoie EXACTEMENT la meme requete
        ApiTestClient.Response r2 = api.changes(token, "4", id, 0, add("p-steak", 1, null));
        assertEquals(r2.body, 200, r2.status);
        assertEquals(Boolean.FALSE, r2.json().get("applied"));
        assertEquals(Boolean.TRUE, r2.json().get("duplicate"));
        assertEquals(1, lines(r2.order()).size());
        assertEquals(1L, version(r2.order()));

        // meme apres un redemarrage de la caisse (memoire vide) : reconnue grace a la commande
        ApiServer restarted = startServer();
        try {
            ApiTestClient api2 = new ApiTestClient(restarted.getPort());
            String t2 = api2.login(env.waiterId, PASSWORD);
            ApiTestClient.Response r3 = api2.changes(t2, "4", id, 0, add("p-steak", 1, null));
            assertEquals(r3.body, 200, r3.status);
            assertEquals(Boolean.TRUE, r3.json().get("duplicate"));
        } finally {
            restarted.stop();
        }
        assertEquals(1, tillStore.load("4").getTicket().getLinesCount());
    }

    @Test
    public void memeRequeteEnvoyeePlusieursFoisEnParallele() throws Exception {
        final String id = rid();
        final int n = 6;
        final List<Boolean> applied = Collections.synchronizedList(new ArrayList<Boolean>());
        final CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await();
                        ApiTestClient.Response r = api.changes(token, "8", id, 0, add("p-soupe", 1, null));
                        applied.add(r.status == 200 ? (Boolean) r.json().get("applied") : null);
                    } catch (Exception e) {
                        applied.add(null);
                    }
                }
            });
            threads.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(60000);
        }
        assertEquals(applied.toString(), 1, Collections.frequency(applied, Boolean.TRUE));
        assertEquals(applied.toString(), n - 1, Collections.frequency(applied, Boolean.FALSE));
        assertEquals(1, tillStore.load("8").getTicket().getLinesCount());
    }

    @Test
    public void requestIdReutilisePourUneAutreDemande() throws Exception {
        String id = rid();
        assertEquals(200, api.changes(token, "5", id, 0, add("p-cafe", 1, null)).status);
        ApiTestClient.Response r = api.changes(token, "5", id, 1, add("p-the", 1, null));
        assertEquals(409, r.status);
        assertEquals("request_id_reused", r.errorCode());
        assertEquals(1, tillStore.load("5").getTicket().getLinesCount());
    }

    // ================================================================ permissions et validation

    @Test
    public void permissions() throws Exception {
        ApiTestClient.Response r = api.changes(minToken, "9", rid(), 0, add("p-cafe", 1, null));
        assertEquals("ajouter un produit : mobile.Access suffit", 200, r.status);
        String lineId = (String) line(r.order(), 0).get("lineId");
        ApiTestClient.Response r2 = api.changes(minToken, "9", rid(), 1, setQuantity(lineId, 5));
        assertEquals(403, r2.status);
        assertTrue(r2.errorMessage().contains("sales.EditLines"));
        assertEquals(403, api.changes(minToken, "9", rid(), 1, remove(lineId)).status);
        assertEquals(1.0, tillStore.load("9").getTicket().getLine(0).getMultiply(), 0);
        // sans connexion
        assertEquals(401, api.changes(null, "9", rid(), 1, add("p-cafe", 1, null)).status);
        assertEquals(401, api.getOrder(null, "9").status);
    }

    @Test
    public void ligneDejaEnvoyeeEnCuisine() throws Exception {
        Map<String, Object> o = api.changes(token, "10", rid(), 0, add("p-steak", 1, null)).order();
        String lineId = (String) line(o, 0).get("lineId");
        // la caisse envoie la commande en cuisine (script.SendOrder : sendstatus=OK)
        TableTicketStore.Snapshot s = tillStore.load("10");
        s.getTicket().getLine(0).setProperty("sendstatus", "OK");
        tillStore.update("10", s.getTicket(), s.getTicket().getId(), s.getVersion(), "Marie", "caisse");

        ApiTestClient.Response r = api.changes(token, "10", rid(), 2, setQuantity(lineId, 2));
        assertEquals(409, r.status);
        assertEquals("line_already_sent", r.errorCode());
        assertEquals(409, api.changes(token, "10", rid(), 2, remove(lineId)).status);
        assertEquals(Boolean.TRUE, line(api.getOrder(token, "10").order(), 0).get("sent"));
        // ajouter une nouvelle ligne reste possible
        assertEquals(200, api.changes(token, "10", rid(), 2, add("p-steak", 1, "en plus")).status);
    }

    @Test
    public void toutOuRienEtValidation() throws Exception {
        // la 2e operation est invalide : la 1re ne doit pas etre enregistree
        ApiTestClient.Response r = api.changes(token, "11", rid(), 0, add("p-steak", 1, null), add("inconnu", 1, null));
        assertEquals(400, r.status);
        assertEquals("unknown_product", r.errorCode());
        assertNull(tillStore.load("11"));

        assertEquals(422, api.changes(token, "11", rid(), 0, add("p-vprice", 1, null)).status);
        assertEquals(400, api.changes(token, "11", rid(), 0, add("p-cafe", 0, null)).status);
        assertEquals(400, api.changes(token, "11", rid(), 0, add("p-cafe", 1000, null)).status);
        assertEquals(400, api.call("POST", "/tables/11/order/changes", token,
                "{\"requestId\":\"" + rid() + "\",\"baseVersion\":0,\"operations\":[{\"op\":\"add\",\"productId\":\"p-cafe\",\"quantity\":2.5}]}").status);
        StringBuilder longue = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            longue.append('x');
        }
        assertEquals(400, api.changes(token, "11", rid(), 0, add("p-cafe", 1, longue.toString())).status);
        assertEquals("invalid_request_id", api.changes(token, "11", "court", 0, add("p-cafe", 1, null)).errorCode());
        assertEquals(400, api.call("POST", "/tables/11/order/changes", token,
                "{\"requestId\":\"" + rid() + "\",\"operations\":[]}").status);
        assertEquals(404, api.changes(token, "inexistante", rid(), 0, add("p-cafe", 1, null)).status);
        assertNull("aucune requete invalide n'a cree de commande", tillStore.load("11"));

        // caracteres de controle retires de la note
        Map<String, Object> o = api.changes(token, "11", rid(), 0, add("p-cafe", 1, "sans\nsucre\t ")).order();
        assertEquals("sans sucre", line(o, 0).get("note"));
    }

    // ================================================================ lignes saisies a la caisse

    @Test
    public void lignesSaisiesALaCaisse() throws Exception {
        TicketInfo t = new TicketInfo();
        t.addLine(new TicketLineInfo("Menu enfant", "001", "0", 1.0, 8.0, taxStandard));
        tillStore.create("x1", t, "Marie", "caisse");
        Map<String, Object> o = api.getOrder(token, "x1").order();
        assertEquals("idx:0", line(o, 0).get("lineId"));
        ApiTestClient.Response r = api.changes(token, "x1", rid(), 1, setNote("idx:0", "Sans frites"));
        assertEquals(r.body, 200, r.status);
        String uid = (String) line(r.order(), 0).get("lineId");
        assertFalse("un identifiant stable a ete attribue", uid.startsWith("idx:"));
        assertEquals("Sans frites", tillStore.load("x1").getTicket().getLine(0).getProperty("notes"));
    }

    @Test
    public void suppressionJourniseeCommeALaCaisse() throws Exception {
        Map<String, Object> o = api.changes(token, "x2", rid(), 0, add("p-poisson", 1, null)).order();
        int before = countLineRemoved();
        assertEquals(200, api.changes(token, "x2", rid(), 1, remove((String) line(o, 0).get("lineId"))).status);
        assertEquals(before + 1, countLineRemoved());
        assertEquals(0, tillStore.load("x2").getTicket().getLinesCount());
    }

    private static int countLineRemoved() throws Exception {
        try (PreparedStatement ps = tillSession.getConnection().prepareStatement(
                "SELECT COUNT(*) FROM LINEREMOVED WHERE PRODUCTNAME = 'Poisson grille'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Map<String, Object> findTable(String name) throws Exception {
        for (Object f : (List<Object>) api.call("GET", "/floors", token, null).json().get("floors")) {
            for (Object t : (List<Object>) ((Map<String, Object>) f).get("tables")) {
                if (name.equals(((Map<String, Object>) t).get("name"))) {
                    return (Map<String, Object>) t;
                }
            }
        }
        throw new AssertionError("table introuvable : " + name);
    }

    @Test
    public void tableInconnue() throws Exception {
        ApiTestClient.Response r = api.getOrder(token, "nope");
        assertEquals(404, r.status);
        assertNotNull(r.errorMessage());
    }

    // ------------------------------------------------------------------ OLPOS 5

    @Test
    public void ancienneDemandeNeModifieJamaisLaNouvelleCommandeDeLaTable() throws Exception {
        assertEquals(200, api.changes(token, "13", rid(), 0, add("p-cafe", 1, null)).status);
        String ancienne = api.currentOrderId(token, "13");
        // la caisse encaisse ; une nouvelle commande (version 1 elle aussi) est ouverte sur la table
        tillStore.delete("13", ancienne, 1);
        assertEquals(200, api.changes(token, "13", rid(), 0, add("p-the", 1, null)).status);
        Map<String, Object> avant = api.getOrder(token, "13").order();
        assertEquals(1L, version(avant));
        // demande preparee sur l'ancienne commande, version 1 : refusee malgre la meme version
        ApiTestClient.Response r = api.changesOn(token, "13", ancienne, rid(), 1, add("p-steak", 3, null));
        assertEquals(409, r.status);
        assertEquals("order_replaced", r.errorCode());
        Map<String, Object> apres = api.getOrder(token, "13").order();
        assertEquals("nouvelle commande intacte", avant.get("lines"), apres.get("lines"));
        assertEquals(1L, version(apres));
        // version > 0 sans identifiant : refusee
        ApiTestClient.Response sansId = api.call("POST", "/tables/13/order/changes", token, Json.write(Json.obj(
                "requestId", rid(), "baseVersion", 1L, "operations", java.util.Arrays.asList(add("p-cafe", 1, null)))));
        assertEquals(400, sansId.status);
        assertEquals("invalid_base_order_id", sansId.errorCode());
    }

    @Test
    public void expirationPuisRenouvellementSansPerteDeDonnees() throws Exception {
        assertEquals(200, api.changes(token, "14", rid(), 0, add("p-cafe", 1, null)).status);   // commande en cours
        com.unicenta.pos.license.LicenseService valid = com.unicenta.pos.license.LicenseService.current();
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> clock =
                new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.now());
        com.unicenta.pos.license.LicenseService s = com.unicenta.pos.license.TestLicenses.service(
                new java.io.File("build/test-work/licence-expiree-" + System.nanoTime()), null, clock::get, "TEST");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        try {
            s.install(com.unicenta.pos.license.TestLicenses.code(com.unicenta.pos.license.TestLicenses.license(
                    "OLP-EXPI-EXPI-EXPI-EXPI-0", 1, today.minusDays(40), today.minusDays(1), 0, null)));
            com.unicenta.pos.license.LicenseService.install(s);
            // nouvelle vente refusee, rien d'enregistre
            ApiTestClient.Response r = api.changes(token, "15", rid(), 0, add("p-cafe", 1, null));
            assertEquals(403, r.status);
            assertEquals("license_new_sales_blocked", r.errorCode());
            assertNull(tillStore.load("15"));
            // la commande deja commencee peut etre terminee
            Map<String, Object> o = api.getOrder(token, "14").order();
            assertEquals(200, api.changes(token, "14", rid(), version(o), add("p-the", 1, null)).status);
            // renouvellement : nouvelles ventes de nouveau possibles, donnees intactes
            s.install(com.unicenta.pos.license.TestLicenses.code(com.unicenta.pos.license.TestLicenses.license(
                    "OLP-EXPI-EXPI-EXPI-EXPI-0", 2, today.minusDays(1), today.plusDays(365), 0, null)));
            assertEquals(200, api.changes(token, "15", rid(), 0, add("p-cafe", 1, null)).status);
            assertEquals(2, lines(api.getOrder(token, "14").order()).size());
        } finally {
            com.unicenta.pos.license.LicenseService.install(valid);
        }
    }
}
