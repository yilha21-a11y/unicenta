package com.unicenta.pos.api;

import static com.unicenta.pos.api.ApiTestClient.add;
import static com.unicenta.pos.api.ApiTestClient.line;
import static com.unicenta.pos.api.ApiTestClient.lines;
import static com.unicenta.pos.api.ApiTestClient.setQuantity;
import static com.unicenta.pos.api.ApiTestClient.version;
import com.unicenta.data.loader.Session;
import com.unicenta.pos.forms.AppConfig;
import com.unicenta.pos.forms.AppViewConnection;
import com.unicenta.pos.printer.DeviceTicket;
import com.unicenta.pos.printer.TicketParser;
import com.unicenta.pos.sales.restaurant.TableLocks;
import com.unicenta.pos.sales.restaurant.TableTicketStore;
import com.unicenta.pos.testenv.TestEnvironment;
import com.unicenta.pos.ticket.TicketInfo;
import com.unicenta.pos.ticket.TicketLineInfo;
import com.unicenta.pos.util.Hashcypher;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.AfterClass;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Etape 5 : envoi en cuisine sans double impression, statuts certains / incertains. */
@SuppressWarnings("unchecked")
public class ApiKitchenIntegrationTest {

    /** Imprimante simulee. */
    static final class FakePrinter implements MobilePrinter {
        volatile String mode = "envoye";   // envoye | confirme | erreur | bloquee | pas_prete
        final List<TicketInfo> printed = Collections.synchronizedList(new ArrayList<TicketInfo>());
        volatile String lastTemplate;
        volatile java.util.Map<String, Object> lastVariables;

        @Override
        public Prepared prepare(final TicketInfo ticket, String tableName, String template,
                java.util.Map<String, Object> variables) throws NotPrintedException {
            lastTemplate = template;
            lastVariables = variables;
            if ("pas_prete".equals(mode)) {
                throw new NotPrintedException("Imprimante cuisine n 2 non configuree. Rien n'a ete imprime.", null);
            }
            final String m = mode;
            return new Prepared() {
                @Override
                public boolean confirmsPrinting() {
                    return "confirme".equals(m);
                }

                @Override
                public String printers() {
                    return "2";
                }

                @Override
                public void print() throws Exception {
                    if ("bloquee".equals(m)) {
                        Thread.sleep(3000);
                    }
                    printed.add(ticket);        // le bon a pu partir...
                    if ("erreur".equals(m)) {
                        throw new IOException("liaison imprimante coupee");   // ... mais on ne le sait pas
                    }
                }
            };
        }

        /** Noms imprimes (lignes sendstatus=No) du dernier bon. */
        List<String> lastPrintedNames() {
            List<String> names = new ArrayList<>();
            TicketInfo t = printed.get(printed.size() - 1);
            for (TicketLineInfo l : t.getLines()) {
                if ("No".equals(l.getProperty("sendstatus"))) {
                    names.add(l.getProductName());
                }
            }
            return names;
        }
    }

    private static final String PASSWORD = "cuisine-" + System.nanoTime();
    private static final String MIN_PASSWORD = "min-" + System.nanoTime();
    private static TestEnvironment.Result env;
    private static AppConfig config;
    private static Session tillSession;
    private static Session apiSession;
    private static TableTicketStore tillStore;
    private static TableLocks locks;
    private static FakePrinter printer;
    private static ApiServer server;
    private static ApiTestClient api;
    private static String token;
    private static String minToken;

    @BeforeClass
    public static void demarrer() throws Exception {
        File root = new File(".").getCanonicalFile();
        env = TestEnvironment.create(new File(root, "build/test-work/api-kitchen"), true, 0, PASSWORD, root);
        config = new AppConfig(env.configFile);
        config.load();
        config.setProperty("api.bind", "127.0.0.1");
        config.setProperty("api.port", "0");
        tillSession = AppViewConnection.createSession(config);
        apiSession = AppViewConnection.createAdditionalSession(config);
        tillStore = new TableTicketStore(tillSession);
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
        printer = new FakePrinter();
        ApiConfig apiConfig = new ApiConfig(config);
        server = new ApiServer(apiConfig, new MobileDataService(apiSession), new ApiSessions(apiConfig), locks, printer, 800);
        server.start();
        api = new ApiTestClient(server.getPort());
        token = api.login(env.waiterId, PASSWORD);
        minToken = api.login("u-min", MIN_PASSWORD);
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

    @Before
    public void imprimanteNormale() {
        printer.mode = "envoye";
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static ApiTestClient.Response send(String table, String sendId) throws Exception {
        return api.call("POST", "/tables/" + table + "/kitchen/send", token, Json.write(Json.obj("sendId", sendId)));
    }

    private static ApiTestClient.Response resolve(String table, String sendId, String action) throws Exception {
        return api.call("POST", "/tables/" + table + "/kitchen/" + sendId + "/resolve", token,
                Json.write(Json.obj("action", action)));
    }

    private static Map<String, Object> order(String table) throws Exception {
        return api.getOrder(token, table).order();
    }

    private static String statusOf(ApiTestClient.Response r) {
        return (String) ((Map<String, Object>) r.json().get("send")).get("status");
    }

    private static List<String> kitchenStatuses(Map<String, Object> o) {
        List<String> l = new ArrayList<>();
        for (Object x : lines(o)) {
            l.add((String) ((Map<String, Object>) x).get("kitchenStatus"));
        }
        return l;
    }

    // ================================================================

    @Test
    public void seulesLesNouvellesLignesCuisineSontImprimees() throws Exception {
        assertEquals(200, api.changes(token, "1", id(), 0, add("p-steak", 2, "saignant"), add("p-eau", 1, null),
                add("p-salade", 1, null)).status);
        assertEquals(java.util.Arrays.asList("a_envoyer", null, "a_envoyer"), kitchenStatuses(order("1")));

        ApiTestClient.Response r = send("1", id());
        assertEquals(r.body, 200, r.status);
        assertEquals("envoye", statusOf(r));      // transmis, sans confirmation materielle
        assertEquals(java.util.Arrays.asList("Steak frites", "Salade cesar"), printer.lastPrintedNames());
        assertEquals(java.util.Arrays.asList("envoye", null, "envoye"), kitchenStatuses(r.order()));
        assertNotNull("le modele de la base est utilise", printer.lastTemplate);

        // rien de nouveau : pas de bon
        int before = printer.printed.size();
        ApiTestClient.Response again = send("1", id());
        assertEquals(409, again.status);
        assertEquals("nothing_to_send", again.errorCode());
        assertEquals(before, printer.printed.size());

        // une nouvelle ligne : seule elle part
        long v = version(order("1"));
        assertEquals(200, api.changes(token, "1", id(), v, add("p-soupe", 1, null)).status);
        printer.mode = "confirme";
        ApiTestClient.Response r2 = send("1", id());
        assertEquals("confirme", statusOf(r2));
        assertEquals(Collections.singletonList("Soupe du jour"), printer.lastPrintedNames());
        // historique visible, le plus recent d'abord
        List<Object> sends = (List<Object>) r2.order().get("kitchenSends");
        assertEquals(2, sends.size());
        assertEquals("confirme", ((Map<String, Object>) sends.get(0)).get("status"));
    }

    @Test
    public void memeEnvoiRenvoyeApresCoupureWifiNImprimeRien() throws Exception {
        assertEquals(200, api.changes(token, "2", id(), 0, add("p-steak", 1, null)).status);
        String sendId = id();
        assertEquals(200, send("2", sendId).status);
        int count = printer.printed.size();
        ApiTestClient.Response r = send("2", sendId);
        assertEquals(200, r.status);
        assertEquals(Boolean.TRUE, r.json().get("duplicate"));
        assertEquals("aucune reimpression", count, printer.printed.size());
    }

    @Test
    public void erreurPendantImpressionAVerifierSansReimpressionAutomatique() throws Exception {
        assertEquals(200, api.changes(token, "3", id(), 0, add("p-poisson", 1, null)).status);
        printer.mode = "erreur";
        String sendId = id();
        ApiTestClient.Response r = send("3", sendId);
        assertEquals(r.body, 200, r.status);
        assertEquals("a_verifier", statusOf(r));
        assertTrue(r.body.contains("Rien ne sera reimprime automatiquement"));
        assertEquals("a_verifier", line(r.order(), 0).get("kitchenStatus"));
        int count = printer.printed.size();

        // un nouvel envoi n'inclut PAS la ligne incertaine
        printer.mode = "envoye";
        ApiTestClient.Response again = send("3", id());
        assertEquals(409, again.status);
        assertTrue(again.errorMessage(), again.errorMessage().contains("a verifier"));
        assertEquals(count, printer.printed.size());
        // et elle n'est plus modifiable depuis le telephone
        String lineId = (String) line(r.order(), 0).get("lineId");
        assertEquals(409, api.changes(token, "3", id(), version(r.order()), setQuantity(lineId, 2)).status);

        // le serveur verifie : le bon est sorti
        ApiTestClient.Response ok = resolve("3", sendId, "printed");
        assertEquals(ok.body, 200, ok.status);
        assertEquals("confirme_manuellement", statusOf(ok));
        assertEquals("envoye", line(ok.order(), 0).get("kitchenStatus"));
        assertEquals(count, printer.printed.size());
    }

    @Test
    public void reimpressionSeulementSurDemande() throws Exception {
        assertEquals(200, api.changes(token, "4", id(), 0, add("p-steak", 1, null)).status);
        printer.mode = "erreur";
        String sendId = id();
        assertEquals("a_verifier", statusOf(send("4", sendId)));
        printer.mode = "envoye";
        ApiTestClient.Response rp = resolve("4", sendId, "reprint");
        assertEquals("reimpression_demandee", statusOf(rp));
        assertEquals("a_envoyer", line(rp.order(), 0).get("kitchenStatus"));
        // la reimpression demandee part avec un NOUVEL envoi
        int count = printer.printed.size();
        assertEquals("envoye", statusOf(send("4", id())));
        assertEquals(count + 1, printer.printed.size());
        // un envoi deja verifie ne se resout plus
        assertEquals(409, resolve("4", sendId, "printed").status);
    }

    @Test
    public void imprimanteQuiNeRepondPas() throws Exception {
        assertEquals(200, api.changes(token, "5", id(), 0, add("p-salade", 1, null)).status);
        printer.mode = "bloquee";
        ApiTestClient.Response r = send("5", id());
        assertEquals(r.body, 200, r.status);
        assertEquals("a_verifier", statusOf(r));
        assertTrue(r.body.contains("pas repondu a temps"));

        // Un 2e bon arrive pendant que l'imprimante est bloquee : il n'a jamais
        // commence -> certitude "non imprime", lignes toujours a envoyer.
        printer.mode = "envoye";
        assertEquals(200, api.changes(token, "10", id(), 0, add("p-soupe", 1, null)).status);
        int count = printer.printed.size();
        ApiTestClient.Response q = send("10", id());
        assertEquals(q.body, 200, q.status);
        assertEquals("non_imprime", statusOf(q));
        assertTrue(q.body.contains("n'a PAS ete imprime"));
        assertEquals("a_envoyer", line(q.order(), 0).get("kitchenStatus"));

        Thread.sleep(2800);   // l'imprimante bloquee se libere
        assertEquals("le bon annule n'est jamais sorti", count + 1, printer.printed.size());   // +1 = le bon bloque de la table 5
        assertEquals("renvoi possible", "envoye", statusOf(send("10", id())));
    }

    @Test
    public void imprimanteNonConfigureeRienNeChange() throws Exception {
        assertEquals(200, api.changes(token, "6", id(), 0, add("p-steak", 1, null)).status);
        long v = version(order("6"));
        printer.mode = "pas_prete";
        int count = printer.printed.size();
        ApiTestClient.Response r = send("6", id());
        assertEquals(503, r.status);
        assertEquals("kitchen_printer_not_ready", r.errorCode());
        assertTrue(r.errorMessage().contains("Rien n'a ete imprime"));
        Map<String, Object> o = order("6");
        assertEquals("a_envoyer", line(o, 0).get("kitchenStatus"));
        assertEquals("commande inchangee", v, version(o));
        assertEquals(count, printer.printed.size());
    }

    @Test
    public void tableOuverteALaCaisseEtPermission() throws Exception {
        assertEquals(200, api.changes(token, "7", id(), 0, add("p-steak", 1, null)).status);
        int count = printer.printed.size();
        assertTrue(locks.acquireTill("7", "Marie", 0));
        try {
            ApiTestClient.Response r = send("7", id());
            assertEquals(423, r.status);
            assertTrue(r.errorMessage().contains("Rien n'a ete imprime"));
        } finally {
            locks.releaseTill("7");
        }
        ApiTestClient.Response f = api.call("POST", "/tables/7/kitchen/send", minToken, Json.write(Json.obj("sendId", id())));
        assertEquals(403, f.status);
        assertTrue(f.errorMessage().contains("sales.PrintKitchen"));
        assertEquals(count, printer.printed.size());
        assertEquals(400, api.call("POST", "/tables/7/kitchen/send", token, "{\"sendId\":\"x\"}").status);
    }

    @Test
    public void textesProtegesSurLeBon() throws Exception {
        assertEquals(200, api.changes(token, "8", id(), 0, add("p-steak", 1, "<b>sans sel & $x #1</b>")).status);
        assertEquals(200, send("8", id()).status);
        TicketInfo printedCopy = printer.printed.get(printer.printed.size() - 1);
        assertEquals("&lt;b&gt;sans sel &amp; &#36;x &#35;1&lt;/b&gt;", printedCopy.getLine(0).getProperty("notes"));
        // la commande garde la note d'origine
        assertEquals("<b>sans sel & $x #1</b>", line(order("8"), 0).get("note"));
    }

    @Test
    public void caisseArreteePendantUneImpression() throws Exception {
        assertEquals(200, api.changes(token, "9", id(), 0, add("p-steak", 1, null)).status);
        // etat laisse par une caisse arretee au milieu d'une impression
        TableTicketStore.Snapshot s = tillStore.load("9");
        s.getTicket().getLine(0).setProperty("sendstatus", "Verify");
        s.getTicket().getLine(0).setProperty("kitchen.sendId", "envoi-interrompu-01");
        s.getTicket().setProperty("kitchen.sends",
                "[{\"id\":\"envoi-interrompu-01\",\"status\":\"en_cours\",\"at\":1,\"by\":\"Serveur Test\",\"items\":[]}]");
        tillStore.update("9", s.getTicket(), s.getVersion(), "test", "test");

        Map<String, Object> o = order("9");
        assertEquals("a_verifier", line(o, 0).get("kitchenStatus"));
        assertEquals("a_verifier", ((Map<String, Object>) ((List<Object>) o.get("kitchenSends")).get(0)).get("status"));
        assertEquals(409, send("9", id()).status);    // pas de reimpression automatique
        assertEquals("confirme_manuellement", statusOf(resolve("9", "envoi-interrompu-01", "printed")));
    }

    // ================================================================ vrai modele + vraie imprimante ecran

    @Test
    public void vraiModeleEtImprimanteEcranDeLaCaisse() throws Exception {
        AppConfig c = new AppConfig(env.configFile);
        c.load();   // machine.printer.2=screen dans l'environnement de test
        DeviceTicket devices = new DeviceTicket(null, c);   // meme constructeur que la caisse
        com.unicenta.pos.forms.DataLogicSystem dls = new com.unicenta.pos.forms.DataLogicSystem();
        dls.init(tillSession);
        TillPrinter real = new TillPrinter(devices, new TicketParser(devices, dls), c);
        String template = dls.getResourceAsXML(TillPrinter.KITCHEN_RESOURCE);

        TicketInfo t = new TicketInfo();
        TicketLineInfo l = new TicketLineInfo("Steak frites", "001", "0", 1.0, 14.0, null);
        l.setProperty("product.printer", "2");
        l.setProperty("sendstatus", "No");
        l.setProperty("notes", MobileKitchenService.xml("<saignant> & sans sel"));
        t.addLine(l);
        MobilePrinter.Prepared p = real.prepare(t, "Table 1", template, null);
        assertTrue("l'ecran confirme l'impression", p.confirmsPrinting());
        assertEquals("2 (ecran)", p.printers());
        p.print();   // ne doit pas lever d'erreur (XML du bon valide malgre les caracteres speciaux)

        // imprimante cuisine non definie : refus AVANT impression
        c.setProperty("machine.printer.2", "Not defined");
        DeviceTicket none = new DeviceTicket(null, c);
        try {
            new TillPrinter(none, new TicketParser(none, dls), c).prepare(t, "Table 1", template, null);
            throw new AssertionError("imprimante non configuree acceptee");
        } catch (MobilePrinter.NotPrintedException expected) {
            assertTrue(expected.getMessage().contains("non configuree"));
        }
    }
}
