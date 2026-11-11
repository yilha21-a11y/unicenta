package com.openbravo.pos.api;

import static com.openbravo.pos.api.ApiTestClient.add;
import static com.openbravo.pos.api.ApiTestClient.version;
import com.openbravo.data.loader.Session;
import com.openbravo.pos.forms.AppConfig;
import com.openbravo.pos.forms.AppViewConnection;
import com.openbravo.pos.forms.DataLogicSystem;
import com.openbravo.pos.printer.DeviceTicket;
import com.openbravo.pos.printer.TicketParser;
import com.openbravo.pos.printer.escpos.PrinterWritterSocketTest;
import com.openbravo.pos.sales.TaxesLogic;
import com.openbravo.pos.sales.restaurant.TableLocks;
import com.openbravo.pos.sales.restaurant.TableTicketStore;
import com.openbravo.pos.testenv.TestEnvironment;
import com.openbravo.pos.ticket.TicketInfo;
import com.openbravo.pos.ticket.TicketLineInfo;
import com.openbravo.pos.ticket.UserInfo;
import com.openbravo.pos.util.Hashcypher;
import java.io.File;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.AfterClass;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Etape 6 : addition provisoire depuis le telephone. */
@SuppressWarnings("unchecked")
public class ApiBillIntegrationTest {

    private static final String PASSWORD = "addition-" + System.nanoTime();
    private static final String MIN_PASSWORD = "min-" + System.nanoTime();
    private static TestEnvironment.Result env;
    private static AppConfig config;
    private static Session tillSession;
    private static Session apiSession;
    private static TableTicketStore tillStore;
    private static TableLocks locks;
    private static ApiKitchenIntegrationTest.FakePrinter printer;
    private static ApiServer server;
    private static ApiTestClient api;
    private static String token;
    private static String minToken;

    @BeforeClass
    public static void demarrer() throws Exception {
        File root = new File(".").getCanonicalFile();
        env = TestEnvironment.create(new File(root, "build/test-work/api-bill"), true, 0, PASSWORD, root);
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
        printer = new ApiKitchenIntegrationTest.FakePrinter();
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

    private static ApiTestClient.Response bill(String token, String table, String printId) throws Exception {
        return api.call("POST", "/tables/" + table + "/bill/print", token, Json.write(Json.obj("printId", printId)));
    }

    private static Map<String, Object> printOf(ApiTestClient.Response r) {
        return (Map<String, Object>) r.json().get("print");
    }

    @Test
    public void additionAvecTotauxDeLaCaisseSansModifierLaCommande() throws Exception {
        assertEquals(200, api.changes(token, "1", id(), 0, add("p-steak", 2, null), add("p-eau", 1, null)).status);
        Map<String, Object> before = api.getOrder(token, "1").order();

        ApiTestClient.Response r = bill(token, "1", id());
        assertEquals(r.body, 200, r.status);
        assertEquals("envoye", printOf(r).get("status"));
        assertEquals(before.get("totalDisplay"), printOf(r).get("total"));
        assertTrue("modele fourni avec la caisse", printer.lastTemplate.contains("ADDITION PROVISOIRE"));
        List<Object> taxes = (List<Object>) printer.lastVariables.get("billtaxes");
        assertEquals("une seule taxe non nulle (10 %)", 1, taxes.size());
        assertTrue(printer.lastVariables.get("printedat") instanceof String);
        TicketInfo printed = printer.printed.get(printer.printed.size() - 1);
        assertEquals(2, printed.getLinesCount());
        assertEquals("Serveur Test", printed.printUser());
        // l'addition n'ecrit rien : meme version, aucune ligne marquee
        Map<String, Object> after = api.getOrder(token, "1").order();
        assertEquals(version(before), version(after));
    }

    @Test
    public void memeDemandeRenvoyeeNImprimePasDeuxAdditions() throws Exception {
        assertEquals(200, api.changes(token, "2", id(), 0, add("p-cafe", 1, null)).status);
        String printId = id();
        assertEquals(200, bill(token, "2", printId).status);
        int count = printer.printed.size();
        ApiTestClient.Response again = bill(token, "2", printId);
        assertEquals(200, again.status);
        assertEquals(Boolean.TRUE, again.json().get("duplicate"));
        assertEquals(count, printer.printed.size());
        // une nouvelle addition demandee explicitement = nouveau printId
        assertEquals(200, bill(token, "2", id()).status);
        assertEquals(count + 1, printer.printed.size());
        // le meme printId ne peut pas servir pour une autre table
        assertEquals(200, api.changes(token, "3", id(), 0, add("p-cafe", 1, null)).status);
        assertEquals(409, bill(token, "3", printId).status);
    }

    @Test
    public void refus() throws Exception {
        int count = printer.printed.size();
        ApiTestClient.Response none = bill(token, "4", id());
        assertEquals(409, none.status);
        assertEquals("empty_order", none.errorCode());

        assertEquals(200, api.changes(token, "5", id(), 0, add("p-the", 1, null)).status);
        assertTrue(locks.acquireTill("5", "Marie", 0));
        try {
            ApiTestClient.Response locked = bill(token, "5", id());
            assertEquals(423, locked.status);
            assertTrue(locked.errorMessage().contains("incomplete"));
        } finally {
            locks.releaseTill("5");
        }
        ApiTestClient.Response forbidden = bill(minToken, "5", id());
        assertEquals(403, forbidden.status);
        assertTrue(forbidden.errorMessage().contains("sales.PrintTicket"));
        assertEquals(400, bill(token, "5", "court").status);
        assertEquals(count, printer.printed.size());
    }

    @Test
    public void imprimantePasPreteLaMemeDemandePeutEtreRefaite() throws Exception {
        assertEquals(200, api.changes(token, "6", id(), 0, add("p-soupe", 1, null)).status);
        String printId = id();
        printer.mode = "pas_prete";
        ApiTestClient.Response r = bill(token, "6", printId);
        assertEquals(503, r.status);
        assertEquals("printer_not_ready", r.errorCode());
        printer.mode = "envoye";
        ApiTestClient.Response retry = bill(token, "6", printId);
        assertEquals("rien n'avait ete imprime : la meme demande imprime maintenant", 200, retry.status);
        assertEquals(Boolean.FALSE, retry.json().get("duplicate"));
    }

    @Test
    public void erreurPendantImpressionAVerifier() throws Exception {
        assertEquals(200, api.changes(token, "7", id(), 0, add("p-steak", 1, null)).status);
        printer.mode = "erreur";
        ApiTestClient.Response r = bill(token, "7", id());
        assertEquals(200, r.status);
        assertEquals("a_verifier", printOf(r).get("status"));
    }

    // ============================================================ vrai modele vers une imprimante reseau

    @Test
    public void vraieAdditionEnvoyeeAUneImprimanteReseau() throws Exception {
        try (PrinterWritterSocketTest.FakeNetworkPrinter net = new PrinterWritterSocketTest.FakeNetworkPrinter()) {
            AppConfig c = new AppConfig(env.configFile);
            c.load();
            c.setProperty("machine.printer", "epson:socket," + net.address());
            DeviceTicket devices = new DeviceTicket(null, c);
            DataLogicSystem dls = new DataLogicSystem();
            dls.init(tillSession);
            TillPrinter real = new TillPrinter(devices, new TicketParser(devices, dls), c);

            com.openbravo.pos.forms.DataLogicSales dlSales = new com.openbravo.pos.forms.DataLogicSales();
            dlSales.init(tillSession);
            TaxesLogic taxes = new TaxesLogic(dlSales.getTaxList().list());
            TicketInfo t = new TicketInfo();
            t.setUser(new UserInfo("u", "Serveur Test"));
            t.addLine(new TicketLineInfo("Steak frites", "001", 2.0, 14.0, taxes.getTaxInfo("001")));
            taxes.calculateTaxes(t);
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("billtaxes", new ArrayList<Object>());
            vars.put("printedat", "11/11/2026 01:30");
            String template = new String(readResource("/com/openbravo/pos/templates/Printer.TicketBill.xml"), StandardCharsets.UTF_8);

            MobilePrinter.Prepared p = real.prepare(t, "Terrasse 4", template, vars);
            assertTrue(p.printers(), p.printers().contains("reseau " + net.address()));
            assertTrue("une imprimante reseau ne confirme pas", !p.confirmsPrinting());
            p.print();
            String text = net.awaitText();
            assertTrue(text, text.contains("ADDITION PROVISOIRE"));
            assertTrue(text, text.contains("Terrasse 4"));
            assertTrue(text, text.contains("Steak frites"));
            assertTrue(text, text.contains("TOTAL"));
            assertTrue(text, text.contains("30,80") || text.contains("30.80"));

            // imprimante reseau eteinte : refus AVANT impression
            int port;
            try (ServerSocket s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }
            c.setProperty("machine.printer", "epson:socket,127.0.0.1:" + port);
            DeviceTicket off = new DeviceTicket(null, c);
            try {
                new TillPrinter(off, new TicketParser(off, dls), c).prepare(t, "Terrasse 4", template, vars);
                fail("imprimante eteinte acceptee");
            } catch (MobilePrinter.NotPrintedException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("ne repond pas"));
            }
        }
    }

    private static byte[] readResource(String path) throws Exception {
        try (java.io.InputStream in = ApiBillIntegrationTest.class.getResourceAsStream(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
