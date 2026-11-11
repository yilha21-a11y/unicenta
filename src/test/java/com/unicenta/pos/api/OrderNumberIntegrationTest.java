package com.unicenta.pos.api;

import static com.unicenta.pos.api.ApiTestClient.add;
import static com.unicenta.pos.api.ApiTestClient.line;
import static com.unicenta.pos.api.ApiTestClient.setQuantity;
import static com.unicenta.pos.api.ApiTestClient.version;
import com.unicenta.data.loader.Session;
import com.unicenta.pos.forms.AppConfig;
import com.unicenta.pos.forms.AppViewConnection;
import com.unicenta.pos.forms.DataLogicSales;
import com.unicenta.pos.forms.DataLogicSystem;
import com.unicenta.pos.payment.PaymentInfo;
import com.unicenta.pos.payment.PaymentInfoCash;
import com.unicenta.pos.printer.DeviceTicket;
import com.unicenta.pos.printer.TicketParser;
import com.unicenta.pos.printer.escpos.PrinterWritterSocketTest;
import com.unicenta.pos.sales.DataLogicReceipts;
import com.unicenta.pos.sales.OrderNumberTemplates;
import com.unicenta.pos.sales.OrderNumbers;
import com.unicenta.pos.sales.TaxesLogic;
import com.unicenta.pos.sales.restaurant.OrderConflictException;
import com.unicenta.pos.sales.restaurant.TableLocks;
import com.unicenta.pos.sales.restaurant.TableTicketStore;
import com.unicenta.pos.testenv.TestEnvironment;
import com.unicenta.pos.ticket.TaxInfo;
import com.unicenta.pos.ticket.TicketInfo;
import com.unicenta.pos.ticket.TicketLineInfo;
import com.unicenta.pos.ticket.UserInfo;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import static org.junit.Assert.fail;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Numero de commande 1 a 100 commun au ticket client et au bon cuisine.
 * Base Derby de TEST ; la caisse et l'API partagent le meme compteur en base.
 */
@SuppressWarnings("unchecked")
public class OrderNumberIntegrationTest {

    private static final String PASSWORD = "num-" + System.nanoTime();
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
    private static TaxInfo tax10;

    @BeforeClass
    public static void demarrer() throws Exception {
        File root = new File(".").getCanonicalFile();
        env = TestEnvironment.create(new File(root, "build/test-work/order-numbers"), true, 0, PASSWORD, root);
        config = new AppConfig(env.configFile);
        config.load();
        config.setProperty("api.bind", "127.0.0.1");
        config.setProperty("api.port", "0");
        tillSession = AppViewConnection.createSession(config);
        apiSession = AppViewConnection.createAdditionalSession(config);
        tillStore = new TableTicketStore(tillSession);
        for (int i = 1; i <= 8; i++) {
            exec("INSERT INTO places(ID, NAME, X, Y, FLOOR, WIDTH, HEIGHT) VALUES ('c" + i + "', 'Comptoir " + i + "', 0, 0, '0', 100, 50)");
        }
        DataLogicSales dls = new DataLogicSales();
        dls.init(tillSession);
        tax10 = new TaxesLogic(dls.getTaxList().list()).getTaxInfo("001");
        locks = TableLocks.newIsolatedInstance();
        printer = new ApiKitchenIntegrationTest.FakePrinter();
        ApiConfig apiConfig = new ApiConfig(config);
        server = new ApiServer(apiConfig, new MobileDataService(apiSession), new ApiSessions(apiConfig), locks, printer, 800);
        server.start();
        api = new ApiTestClient(server.getPort());
        token = api.login(env.waiterId, PASSWORD);
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

    /** Valeur du compteur en base (0 s'il n'existe pas encore). */
    private static int counter() throws Exception {
        try (PreparedStatement ps = tillSession.getConnection().prepareStatement(
                "SELECT CONTENT FROM RESOURCES WHERE NAME = ?")) {
            ps.setString(1, OrderNumbers.RESOURCE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Integer.parseInt(new String(rs.getBytes(1), StandardCharsets.UTF_8).trim()) : 0;
            }
        }
    }

    private static synchronized void setCounter(int n) throws Exception {
        TicketInfo t = ticketWithLine("init");
        OrderNumbers.ensure(t, tillSession);   // cree le compteur si besoin
        try (PreparedStatement ps = tillSession.getConnection().prepareStatement(
                "UPDATE RESOURCES SET CONTENT = ? WHERE NAME = ?")) {
            ps.setBytes(1, Integer.toString(n).getBytes(StandardCharsets.UTF_8));
            ps.setString(2, OrderNumbers.RESOURCE);
            ps.executeUpdate();
        }
    }

    private static TicketInfo ticketWithLine(String name) {
        TicketInfo t = new TicketInfo();
        TicketLineInfo l = new TicketLineInfo(name, "001", "0", 1.0, 10.0, tax10);
        l.setProperty("product.printer", "2");
        t.addLine(l);
        return t;
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static int orderNumber(Map<String, Object> order) {
        assertNotNull("numero attribue", order.get("orderNumber"));
        return ((Number) order.get("orderNumber")).intValue();
    }

    // ============================================================ sequence

    @Test
    public void sequenceUnDeuxTroisPuisRetourAUnApresCent() throws Exception {
        assertEquals(1, OrderNumbers.next(0));
        assertEquals(2, OrderNumbers.next(1));
        assertEquals(100, OrderNumbers.next(99));
        assertEquals(1, OrderNumbers.next(100));

        synchronized (OrderNumberIntegrationTest.class) {
            setCounter(0);
            List<Integer> got = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                got.add(OrderNumbers.ensure(ticketWithLine("a" + i), tillSession));
            }
            assertEquals(java.util.Arrays.asList(1, 2, 3), got);

            setCounter(98);
            got.clear();
            for (int i = 0; i < 4; i++) {
                got.add(OrderNumbers.ensure(ticketWithLine("b" + i), tillSession));
            }
            assertEquals("98, 99, 100, 1, 2...", java.util.Arrays.asList(99, 100, 1, 2), got);
        }
    }

    @Test
    public void compteurConserveApresRedemarrage() throws Exception {
        synchronized (OrderNumberIntegrationTest.class) {
            setCounter(41);
            assertEquals(Integer.valueOf(42), OrderNumbers.ensure(ticketWithLine("x"), tillSession));
            // "redemarrage" : nouvelle connexion, aucun etat en memoire
            Session restarted = AppViewConnection.createAdditionalSession(config);
            try {
                assertEquals(Integer.valueOf(43), OrderNumbers.ensure(ticketWithLine("y"), restarted));
            } finally {
                restarted.close();
            }
            assertEquals(43, counter());
            // une commande deja numerotee garde son numero
            TicketInfo t = ticketWithLine("z");
            int n = OrderNumbers.ensure(t, tillSession);
            assertEquals(Integer.valueOf(n), OrderNumbers.ensure(t, tillSession));
            assertEquals(n, counter());
        }
    }

    // ============================================================ simultaneite

    @Test
    public void creationsSimultaneesDepuisTelephonesEtCaisse() throws Exception {
        synchronized (OrderNumberIntegrationTest.class) {
            setCounter(10);
            final List<Integer> numbers = Collections.synchronizedList(new ArrayList<Integer>());
            final List<String> errors = Collections.synchronizedList(new ArrayList<String>());
            final CountDownLatch go = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            // 8 telephones (API) sur 8 tables, en meme temps que la caisse sur 6 autres tables
            for (int i = 1; i <= 8; i++) {
                final String table = "c" + i;
                threads.add(new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            go.await();
                            ApiTestClient.Response r = api.changes(token, table, rid(), 0, add("p-cafe", 1, null));
                            if (r.status == 200) {
                                numbers.add(orderNumber(r.order()));
                            } else {
                                errors.add(r.body);
                            }
                        } catch (Exception e) {
                            errors.add(e.toString());
                        }
                    }
                }));
            }
            for (int i = 1; i <= 6; i++) {
                final String table = Integer.toString(i);
                threads.add(new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Session s = null;
                        try {
                            s = AppViewConnection.createAdditionalSession(config);   // autre poste de caisse
                            go.await();
                            TicketInfo t = ticketWithLine("caisse");
                            new TableTicketStore(s).create(table, t, "Marie", "caisse");
                            numbers.add(OrderNumbers.numberOf(t));
                        } catch (Exception e) {
                            errors.add(e.toString());
                        } finally {
                            if (s != null) {
                                s.close();
                            }
                        }
                    }
                }));
            }
            for (Thread t : threads) {
                t.start();
            }
            go.countDown();
            for (Thread t : threads) {
                t.join(60000);
            }
            assertTrue(errors.toString(), errors.isEmpty());
            List<Integer> sorted = new ArrayList<>(numbers);
            Collections.sort(sorted);
            List<Integer> expected = new ArrayList<>();
            for (int n = 11; n <= 24; n++) {
                expected.add(n);
            }
            assertEquals("14 commandes simultanees = 14 numeros successifs, sans doublon", expected, sorted);
            assertEquals(24, counter());
        }
    }

    // ============================================================ requete repetee

    @Test
    public void requeteRepeteeNeCreeNiCommandeNiNumero() throws Exception {
        synchronized (OrderNumberIntegrationTest.class) {
            String id = rid();
            ApiTestClient.Response r1 = api.changes(token, "7", id, 0, add("p-steak", 1, null));
            int n = orderNumber(r1.order());
            int c = counter();
            ApiTestClient.Response r2 = api.changes(token, "7", id, 0, add("p-steak", 1, null));
            assertEquals(Boolean.TRUE, r2.json().get("duplicate"));
            assertEquals(n, orderNumber(r2.order()));
            assertEquals("aucun nouveau numero", c, counter());
            assertEquals(1, tillStore.load("7").getTicket().getLinesCount());
        }
    }

    // ============================================================ conservation du numero

    @Test
    public void numeroConserveApresModificationAjoutCuisineAdditionPaiementEtReimpression() throws Exception {
        ApiTestClient.Response r = api.changes(token, "8", rid(), 0, add("p-steak", 1, "saignant"), add("p-eau", 1, null));
        int n = orderNumber(r.order());
        String steak = (String) line(r.order(), 0).get("lineId");

        // modification + ajout
        Map<String, Object> o = api.changes(token, "8", rid(), 1, setQuantity(steak, 2), add("p-salade", 1, null)).order();
        assertEquals(n, orderNumber(o));

        // envoi cuisine : le bon porte le meme numero
        int printed = printer.printed.size();
        assertEquals(200, api.call("POST", "/tables/8/kitchen/send", token, Json.write(Json.obj("sendId", rid(), "orderId", api.currentOrderId(token, "8")))).status);
        assertEquals(String.valueOf(n), printer.printed.get(printed).getProperty(OrderNumbers.P_NUMBER));
        // ajout puis 2e bon cuisine
        o = api.getOrder(token, "8").order();
        api.changes(token, "8", rid(), version(o), add("p-soupe", 1, null));
        assertEquals(200, api.call("POST", "/tables/8/kitchen/send", token, Json.write(Json.obj("sendId", rid(), "orderId", api.currentOrderId(token, "8")))).status);
        assertEquals(String.valueOf(n), printer.printed.get(printer.printed.size() - 1).getProperty(OrderNumbers.P_NUMBER));
        // addition
        assertEquals(200, api.call("POST", "/tables/8/bill/print", token, Json.write(Json.obj("printId", rid()))).status);
        assertEquals(String.valueOf(n), printer.printed.get(printer.printed.size() - 1).getProperty(OrderNumbers.P_NUMBER));
        assertEquals(n, orderNumber(api.getOrder(token, "8").order()));

        // paiement a la caisse : vente enregistree avec le numero, distinct du numero de ticket
        TableTicketStore.Snapshot snap = tillStore.load("8");
        TicketInfo ticket = snap.getTicket();
        String money = UUID.randomUUID().toString();
        exec("INSERT INTO CLOSEDCASH(MONEY, HOST, HOSTSEQUENCE, DATESTART) VALUES ('" + money + "', 'TEST', 1, CURRENT_TIMESTAMP)");
        DataLogicSales dls = new DataLogicSales();
        dls.init(tillSession);
        new TaxesLogic(dls.getTaxList().list()).calculateTaxes(ticket);
        double total = ticket.getTotal();
        List<PaymentInfo> pays = new ArrayList<>();
        pays.add(new PaymentInfoCash(total, total, total));
        ticket.setPayments(pays);
        ticket.setUser(new UserInfo(env.waiterId, "Serveur Test"));
        ticket.setActiveCash(money);
        ticket.setDate(new Date());
        OrderNumbers.ensure(ticket, tillSession);    // comme JPanelTicket avant saveTicket
        dls.saveTicket(ticket, "0");
        int ticketId = ticket.getTicketId();
        assertTrue("numero de ticket comptable attribue", ticketId > 0);
        TicketInfo reloaded = dls.loadTicket(TicketInfo.RECEIPT_NORMAL, ticketId);
        assertEquals("numero conserve dans la vente enregistree", Integer.valueOf(n), OrderNumbers.numberOf(reloaded));
        assertEquals("prix et taxes intacts", total, reloaded.getTotal(), 1e-9);
        assertEquals("steak, eau, salade, soupe", 4, reloaded.getLinesCount());
    }

    @Test
    public void pasDeNumeroSansArticleEtPasDeTrouSurConflit() throws Exception {
        synchronized (OrderNumberIntegrationTest.class) {
            int c = counter();
            TicketInfo empty = new TicketInfo();
            tillStore.create("9", empty, "Marie", "caisse");           // table ouverte, rien commande
            assertNull(OrderNumbers.numberOf(empty));
            assertEquals(c, counter());
            empty.addLine(new TicketLineInfo("Plat", "001", "0", 1.0, 12.0, tax10));
            tillStore.update("9", empty, empty.getId(), 1, "Marie", "caisse");       // premiere sauvegarde avec article
            int n = OrderNumbers.numberOf(empty);
            assertEquals(OrderNumbers.next(c), n);
            empty.addLine(new TicketLineInfo("Dessert", "001", "0", 1.0, 6.0, tax10));
            tillStore.update("9", empty, empty.getId(), 2, "Marie", "caisse");
            assertEquals("numero conserve", Integer.valueOf(n), OrderNumbers.numberOf(tillStore.load("9").getTicket()));

            // creation refusee (table occupee) : le numero n'est pas consomme
            int before = counter();
            TicketInfo other = ticketWithLine("autre");
            try {
                tillStore.create("9", other, "Paul", "caisse");
                fail("table occupee acceptee");
            } catch (OrderConflictException expected) {
                assertNull(OrderNumbers.numberOf(other));
            }
            assertEquals("pas de trou dans la sequence", before, counter());
        }
    }

    @Test
    public void commandeExistanteSansNumeroLeRecoitSansPerdreSesDonnees() throws Exception {
        // commande ecrite par l'ancienne caisse (sans numero ni version)
        DataLogicReceipts dl = new DataLogicReceipts();
        dl.init(tillSession);
        TicketInfo old = new TicketInfo();
        old.addLine(new TicketLineInfo("Ancien plat", "001", "0", 2.0, 9.5, tax10));
        dl.insertSharedTicket("10", old, 0);
        Map<String, Object> o = api.getOrder(token, "10").order();
        assertNull("pas de numero invente a la lecture", o.get("orderNumber"));
        Map<String, Object> o2 = api.changes(token, "10", rid(), 1, add("p-cafe", 1, null)).order();
        assertNotNull("numero attribue a la premiere modification", o2.get("orderNumber"));
        assertEquals("Ancien plat", line(o2, 0).get("name"));
        assertEquals(2.0, ((Number) line(o2, 0).get("quantity")).doubleValue(), 0);
        assertEquals(10.45, ((Number) line(o2, 0).get("unitPriceTaxed")).doubleValue(), 1e-9);
        // le plan de salle montre le numero
        boolean seen = false;
        for (Object f : (List<Object>) api.call("GET", "/floors", token, null).json().get("floors")) {
            for (Object t : (List<Object>) ((Map<String, Object>) f).get("tables")) {
                Map<String, Object> tm = (Map<String, Object>) t;
                if ("10".equals(tm.get("id"))) {
                    assertEquals(o2.get("orderNumber"), tm.get("orderNumber"));
                    seen = true;
                }
            }
        }
        assertTrue(seen);
    }

    // ============================================================ modeles et sorties imprimees

    @Test
    public void migrationDesModelesExistants() throws Exception {
        String restaurant = "<?xml version=\"1.0\"?>\r\n<output>\r\n<ticket>\r\n"
                + "  <line><text align=\"center\" length=\"42\">Chez Marie - 12 rue du Port</text></line>\r\n"
                + "  <line><text>${ticket.printTotal()}</text></line>\r\n</ticket>\r\n</output>\r\n";
        String migrated = OrderNumberTemplates.addBlock("Printer.Ticket", restaurant);
        assertTrue(migrated.contains(OrderNumberTemplates.MARKER));
        assertTrue("restaurant intact", migrated.contains("Chez Marie - 12 rue du Port"));
        assertEquals("rien de supprime", restaurant.length() + OrderNumberTemplates.RECEIPT_BLOCK.replace("\n", "\r\n").length(),
                migrated.length());
        assertEquals("idempotent", migrated, OrderNumberTemplates.addBlock("Printer.Ticket", migrated));
        assertEquals("modele sans bon : intact", "<output><display/></output>",
                OrderNumberTemplates.addBlock("Printer.Ticket", "<output><display/></output>"));

        DataLogicSystem dls = new DataLogicSystem();
        dls.init(tillSession);
        dls.setResource("Printer.ReprintTicket", 0, restaurant.getBytes(StandardCharsets.UTF_8));
        assertTrue(OrderNumberTemplates.run(dls).contains("Printer.ReprintTicket"));
        DataLogicSystem fresh = new DataLogicSystem();
        fresh.init(tillSession);
        assertTrue(fresh.getResourceAsText("Printer.ReprintTicket").contains("DUPLICATA"));
        assertTrue(OrderNumberTemplates.run(fresh).isEmpty());
        // les modeles d'une base neuve sont deja numerotes
        for (String r : new String[] {"Printer.Ticket", "Printer.TicketKitchen", "Printer.TicketPreview"}) {
            assertTrue(r, fresh.getResourceAsText(r).contains(OrderNumberTemplates.MARKER));
        }
    }

    @Test
    public void ticketClientEtBonCuisineAffichentLeMemeNumero() throws Exception {
        DataLogicSystem dls = new DataLogicSystem();
        dls.init(tillSession);
        TicketInfo t = ticketWithLine("Steak frites");
        t.getLine(0).setProperty("product.name", "Steak frites");
        t.setUser(new UserInfo("u", "Serveur Test"));
        int n = OrderNumbers.ensure(t, tillSession);
        DataLogicSales dlSales = new DataLogicSales();
        dlSales.init(tillSession);
        TaxesLogic taxes = new TaxesLogic(dlSales.getTaxList().list());
        taxes.calculateTaxes(t);
        String expected = "COMMANDE Nø " + n;     // octet 0xF8 = "°" dans la table ESC/POS PC858

        // 1. bon cuisine (premier envoi) : pas de mention AJOUT
        t.getLine(0).setProperty("sendstatus", "No");
        String kitchen1 = render(dls, "Printer.TicketKitchen", t, "Table 3", "2", null);
        assertTrue(kitchen1, kitchen1.contains(expected));
        assertTrue(kitchen1.contains("TABLE : Table 3"));
        assertFalse(kitchen1.contains("AJOUT"));
        assertFalse(kitchen1.contains("DUPLICATA"));

        // 2. ticket client : meme numero
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("taxes", t.getTaxLines());
        vars.put("taxeslogic", taxes);
        String receipt = render(dls, "Printer.Ticket", t, "Table 3", "1", vars);
        assertTrue(receipt, receipt.contains(expected));

        // 3. bon cuisine complementaire : AJOUT + numero initial
        t.getLine(0).setProperty("sendstatus", "OK");
        TicketLineInfo extra = new TicketLineInfo("Cafe", "001", "0", 1.0, 1.8, tax10);
        extra.setProperty("product.printer", "2");
        extra.setProperty("sendstatus", "No");
        t.addLine(extra);
        String kitchen2 = render(dls, "Printer.TicketKitchen", t, "Table 3", "2", null);
        assertTrue(kitchen2.contains("AJOUT"));
        assertTrue(kitchen2.contains(expected));

        // 4. reimpression cuisine demandee : DUPLICATA + numero initial
        extra.setProperty("kitchen.reprint", "1");
        String kitchen3 = render(dls, "Printer.TicketKitchen", t, "Table 3", "2", null);
        assertTrue(kitchen3.contains("DUPLICATA"));
        assertTrue(kitchen3.contains(expected));

        // 5. reimpression du ticket client : DUPLICATA + numero initial ; vente a emporter
        String reprint = render(dls, "Printer.TicketPreview", t, null, "1", vars);
        assertTrue(reprint.contains("DUPLICATA"));
        assertTrue(reprint.contains(expected));
        assertTrue(reprint.contains("A EMPORTER"));
    }

    /** Rend un modele de la base vers une fausse imprimante reseau et renvoie le texte recu. */
    private static String render(DataLogicSystem dls, String resource, TicketInfo t, String place, String printerId,
            Map<String, Object> vars) throws Exception {
        try (PrinterWritterSocketTest.FakeNetworkPrinter net = new PrinterWritterSocketTest.FakeNetworkPrinter()) {
            AppConfig c = new AppConfig(env.configFile);
            c.load();
            c.setProperty("1".equals(printerId) ? "machine.printer" : "machine.printer." + printerId,
                    "epson:socket," + net.address());
            DeviceTicket devices = new DeviceTicket(null, c);
            TillPrinter p = new TillPrinter(devices, new TicketParser(devices, dls), c);
            Map<String, Object> v = vars == null ? new HashMap<String, Object>() : vars;
            MobilePrinter.Prepared prepared = p.prepare(t, place, dls.getResourceAsXML(resource), v);
            prepared.print();
            String text = net.awaitContains("COMMANDE N");
            Thread.sleep(300);   // fin du bon
            return net.awaitContains("COMMANDE N");
        }
    }
}
