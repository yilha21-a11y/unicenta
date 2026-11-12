package com.unicenta.pos.portal;

import com.sun.net.httpserver.HttpServer;
import com.unicenta.data.loader.Session;
import com.unicenta.pos.api.Json;
import com.unicenta.pos.forms.AppConfig;
import com.unicenta.pos.forms.AppViewConnection;
import com.unicenta.pos.forms.DataLogicSystem;
import com.unicenta.pos.testenv.TestEnvironment;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regressions de l'audit du portail, cote caisse (base de test ; Derby par
 * defaut, MySQL si -Dolpos.test.mysql.url est fourni via {@link TestDatabases}).
 * <ol>
 *   <li>deux taxes 5 % et 7 % sur une base de 100 : 112, pas 212 ;</li>
 *   <li>une nouvelle association n'envoie jamais les anciens lots d'un autre restaurant ;</li>
 *   <li>ventes antidatees, corrections et suppressions d'anciens tickets synchronisees ;</li>
 *   <li>chaque lot porte une version croissante.</li>
 * </ol>
 */
@SuppressWarnings("unchecked")
public class PortalSyncRegressionTest {

    static TestEnvironment.Result env;
    static Session session;
    static Connection c;
    static DataLogicSystem dl;
    static File work;
    static HttpServer srv;
    static String url;
    static final List<Map<String, Object>> received = new ArrayList<>();
    static final LinkedList<String> pairRids = new LinkedList<>();
    static final AtomicInteger failNext = new AtomicInteger();

    @BeforeClass
    public static void preparer() throws Exception {
        File root = new File(".").getCanonicalFile();
        work = new File(root, "build/test-work/portal-sync-" + TestDatabases.kind());
        env = TestDatabases.create(new File(work, "env"), root);
        AppConfig config = new AppConfig(env.configFile);
        config.load();
        session = AppViewConnection.createSession(config);
        c = session.getConnection();
        dl = new DataLogicSystem();
        dl.init(session);

        srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/api/device/pair", ex -> {
            ex.getRequestBody().readAllBytes();
            String rid;
            synchronized (pairRids) {
                rid = pairRids.removeFirst();
            }
            reply(ex, 200, Json.obj("restaurantId", rid, "slug", "resto-" + rid, "name", "Resto " + rid,
                    "deviceToken", "jeton-" + rid));
        });
        srv.createContext("/api/device/sync", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            if (failNext.getAndDecrement() > 0) {
                reply(ex, 500, Json.obj());
                return;
            }
            try {
                Map<String, Object> b = (Map<String, Object>) Json.parse(new String(body, StandardCharsets.UTF_8));
                b.put("_auth", ex.getRequestHeaders().getFirst("Authorization"));
                synchronized (received) {
                    received.add(b);
                }
            } catch (Json.ParseException e) {
                throw new java.io.IOException(e);
            }
            reply(ex, 200, Json.obj("status", "ok"));
        });
        srv.start();
        url = "http://127.0.0.1:" + srv.getAddress().getPort();
    }

    @AfterClass
    public static void fermer() {
        if (srv != null) {
            srv.stop(0);
        }
        if (session != null) {
            session.close();
        }
    }

    @Before
    public void vider() {
        synchronized (received) {
            received.clear();
        }
        failNext.set(0);
    }

    // ================================================================== 2. taxes multiples

    @Test
    public void deuxTaxesSurLaMemeBaseDonnent112() throws Exception {
        String id = UUID.randomUUID().toString();
        String money = PortalTestData.openCash(c, "TEST-POS", 50, System.currentTimeMillis(), null);
        exec("INSERT INTO receipts (id, money, datenew, attributes, person) VALUES (?, ?, ?, NULL, '0')", id, money,
                new Timestamp(System.currentTimeMillis()));
        exec("INSERT INTO tickets (id, tickettype, ticketid, person, customer, status) VALUES (?, 0, 9001, '0', NULL, 0)",
                id);
        exec("INSERT INTO ticketlines (ticket, line, product, units, price, taxid) VALUES (?, 0, 'p-steak', 1, 100, '001')",
                id);
        exec("INSERT INTO taxlines (id, receipt, taxid, base, amount) VALUES (?, ?, '001', 100, 5)",
                UUID.randomUUID().toString(), id);
        exec("INSERT INTO taxlines (id, receipt, taxid, base, amount) VALUES (?, ?, '000', 100, 7)",
                UUID.randomUUID().toString(), id);
        exec("INSERT INTO payments (id, receipt, payment, total, tip) VALUES (?, ?, 'cash', 112, 0)",
                UUID.randomUUID().toString(), id);
        commit();
        Map<String, Object> t = new PortalExporter(c).ticket(id);
        assertEquals(100.0, (Double) t.get("base"), 0.001);
        assertEquals(12.0, (Double) t.get("tax"), 0.001);
        assertEquals("pas 212", 112.0, (Double) t.get("total"), 0.001);
        assertEquals(2, ((List<Object>) t.get("taxes")).size());
    }

    // ================================================================== 3. nouvelle association

    @Test
    public void nouvelleAssociationNEnvoieJamaisLesLotsDUnAutreRestaurant() throws Exception {
        PortalLink l = new PortalLink(dir("association"));
        synchronized (pairRids) {
            pairRids.add("restoA");
            pairRids.add("restoB");
        }
        PortalAgent.pair(l, url, "CODE", "Caisse", Json.obj());
        long[] now = {System.currentTimeMillis()};
        PortalAgent a = new PortalAgent(l, () -> c, dl, Json.obj(), () -> now[0]);
        failNext.set(1);   // portail injoignable : le lot du restaurant A reste dans la file
        try {
            a.syncOnce();
            throw new AssertionError("coupure attendue");
        } catch (java.io.IOException e) {
            assertEquals(1, a.pendingBatches());
        }
        // ancienne disposition (version precedente) : un lot sans restaurant dans portail\file
        File legacy = new File(l.dir(), "file");
        assertTrue(legacy.mkdirs());
        Files.write(new File(legacy, "000000000000001-x.json").toPath(), "{\"batchId\":\"x\"}".getBytes());

        // nouvelle association, vers le restaurant B
        PortalAgent.pair(l, url, "CODE", "Caisse", Json.obj());
        assertFalse("ancienne file supprimee", legacy.exists());
        assertFalse(new File(new File(l.dir(), "sync"), "restoA").exists());
        // un lot du restaurant A glisse dans la file de B : supprime, jamais envoye
        File qB = new File(new File(new File(l.dir(), "sync"), "restoB"), "file");
        assertTrue(qB.mkdirs());
        Files.write(new File(qB, "000000000000002-y.json").toPath(),
                Json.write(Json.obj("batchId", "y", "rid", "restoA", "seq", 2L, "tickets", new ArrayList<Object>()))
                        .getBytes(StandardCharsets.UTF_8));
        PortalAgent b = new PortalAgent(l, () -> c, dl, Json.obj(), () -> now[0]);
        b.syncOnce();
        assertFalse(received.isEmpty());
        for (Map<String, Object> m : received) {
            assertEquals("restoB", m.get("rid"));
            assertEquals("Bearer jeton-restoB", m.get("_auth"));
        }
        assertEquals(0, b.pendingBatches());
    }

    // ================================================================== 4/5. antidatees, corrections, versions

    @Test
    public void venteAntidateeCorrectionEtSuppressionSynchronisees() throws Exception {
        PortalLink l = new PortalLink(dir("rapprochement"));
        synchronized (pairRids) {
            pairRids.add("restoC");
        }
        PortalAgent.pair(l, url, "CODE", "Caisse", Json.obj());
        long[] now = {System.currentTimeMillis()};
        PortalAgent a = new PortalAgent(l, () -> c, dl, Json.obj(), () -> now[0]);
        String money = PortalTestData.openCash(c, "TEST-POS", 60, now[0] - 3600_000L, null);
        String recent = PortalTestData.sale(c, money, now[0] - 60_000L, 7001, 0, "0", "cash", null, null,
                PortalTestData.line("p-cafe", 2.0, 2.0, "001", 0.10));
        commit();
        a.syncOnce();
        assertTrue(ids(last()).contains(recent));
        long seq1 = ((Number) last().get("seq")).longValue();

        // vente ANTIDATEE (hier), saisie apres le curseur
        String old = PortalTestData.sale(c, money, now[0] - 86_400_000L, 7002, 0, "0", "cash", null, null,
                PortalTestData.line("p-the", 1.0, 3.0, "001", 0.10));
        commit();
        a.syncOnce();
        assertFalse("pas avant le rapprochement", ids(last()).contains(old));
        now[0] += PortalAgent.RECONCILE_EVERY_MS + 1000;
        a.syncOnce();
        assertTrue("vente antidatee envoyee", ids(last()).contains(old));

        // CORRECTION d'un ancien ticket (quantite et paiement)
        exec("UPDATE ticketlines SET units = 5 WHERE ticket = ?", recent);
        exec("UPDATE payments SET total = 11 WHERE receipt = ?", recent);
        exec("UPDATE taxlines SET base = 10, amount = 1 WHERE receipt = ?", recent);
        commit();
        now[0] += PortalAgent.RECONCILE_EVERY_MS + 1000;
        a.syncOnce();
        Map<String, Object> corrected = find(last(), recent);
        assertNotNull("ticket corrige renvoye", corrected);
        assertEquals(11.0, ((Number) corrected.get("total")).doubleValue(), 0.001);
        assertTrue("version plus recente", ((Number) last().get("seq")).longValue() > seq1);

        // SUPPRESSION
        exec("DELETE FROM payments WHERE receipt = ?", old);
        exec("DELETE FROM taxlines WHERE receipt = ?", old);
        exec("DELETE FROM ticketlines WHERE ticket = ?", old);
        exec("DELETE FROM tickets WHERE id = ?", old);
        exec("DELETE FROM receipts WHERE id = ?", old);
        commit();
        now[0] += PortalAgent.RECONCILE_EVERY_MS + 1000;
        a.syncOnce();
        assertTrue(((List<Object>) last().get("deletedTickets")).contains(old));
        assertFalse(a.knownTickets().contains(old));

        // rien de change : rien de renvoye
        now[0] += PortalAgent.RECONCILE_EVERY_MS + 1000;
        a.syncOnce();
        assertTrue(((List<Object>) last().get("tickets")).isEmpty());
        assertTrue(((List<Object>) last().get("deletedTickets")).isEmpty());
        // versions strictement croissantes
        long prev = 0;
        for (Map<String, Object> m : received) {
            long s = ((Number) m.get("seq")).longValue();
            assertTrue(s > prev);
            prev = s;
        }
    }

    // ================================================================== outils

    static Map<String, Object> last() {
        synchronized (received) {
            return received.get(received.size() - 1);
        }
    }

    static List<String> ids(Map<String, Object> batch) {
        List<String> out = new ArrayList<>();
        for (Object o : (List<Object>) batch.get("tickets")) {
            out.add((String) ((Map<String, Object>) o).get("id"));
        }
        return out;
    }

    static Map<String, Object> find(Map<String, Object> batch, String id) {
        for (Object o : (List<Object>) batch.get("tickets")) {
            if (id.equals(((Map<String, Object>) o).get("id"))) {
                return (Map<String, Object>) o;
            }
        }
        return null;
    }

    static File dir(String name) throws Exception {
        File d = new File(work, name);
        PortalAgent.deleteTree(d);
        return d;
    }

    static void exec(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    static void commit() throws Exception {
        if (!c.getAutoCommit()) {
            c.commit();
        }
    }

    static void reply(com.sun.net.httpserver.HttpExchange ex, int status, Object body) throws java.io.IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
