package com.unicenta.pos.sales.restaurant;

import com.unicenta.data.loader.Session;
import com.unicenta.pos.forms.AppConfig;
import com.unicenta.pos.forms.AppViewConnection;
import com.unicenta.pos.sales.DataLogicReceipts;
import com.unicenta.pos.testenv.TestEnvironment;
import com.unicenta.pos.ticket.TicketInfo;
import com.unicenta.pos.ticket.TicketLineInfo;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.AfterClass;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.BeforeClass;
import org.junit.Test;

/** Deux connexions sur la meme base = deux postes (ou caisse + API). */
public class TableTicketStoreTest {

    private static Session posteA;
    private static Session posteB;
    private static TableTicketStore storeA;
    private static TableTicketStore storeB;

    @BeforeClass
    public static void demarrer() throws Exception {
        File root = new File(".").getCanonicalFile();
        TestEnvironment.Result env = TestEnvironment.create(new File(root, "build/test-work/store"), true, 0, "x-" + System.nanoTime(), root);
        AppConfig config = new AppConfig(env.configFile);
        config.load();
        posteA = AppViewConnection.createSession(config);
        posteB = AppViewConnection.createAdditionalSession(config);
        storeA = new TableTicketStore(posteA);
        storeB = new TableTicketStore(posteB);
    }

    @AfterClass
    public static void arreter() {
        if (posteB != null) {
            posteB.close();
        }
        if (posteA != null) {
            posteA.close();
        }
    }

    private static TicketInfo ticketAvec(String nom) {
        TicketInfo t = new TicketInfo();
        t.addLine(new TicketLineInfo(nom, "001", "0", 1.0, 10.0, null));
        return t;
    }

    @Test
    public void creationPuisModificationsSuccessives() throws Exception {
        assertNull(storeA.load("1"));
        assertEquals(1, storeA.create("1", ticketAvec("Soupe"), "Marie", "caisse"));
        TableTicketStore.Snapshot s = storeB.load("1");
        assertEquals(1, s.getVersion());
        assertEquals("Marie", s.getUpdatedBy());
        s.getTicket().addLine(new TicketLineInfo("Cafe", "001", "0", 1.0, 2.0, null));
        assertEquals(2, storeB.update("1", s.getTicket(), 1, "Paul", "mobile"));
        TableTicketStore.Snapshot s2 = storeA.load("1");
        assertEquals(2, s2.getVersion());
        assertEquals(2, s2.getTicket().getLinesCount());
        assertEquals("mobile", s2.getUpdatedSource());
        storeA.delete("1", 2);
        assertNull(storeB.load("1"));
    }

    @Test
    public void ecritureSurVersionPerimeeRefusee() throws Exception {
        storeA.create("2", ticketAvec("Steak"), "Marie", "caisse");
        TableTicketStore.Snapshot vueA = storeA.load("2");   // A lit la v1
        TableTicketStore.Snapshot vueB = storeB.load("2");   // B lit la v1
        vueB.getTicket().addLine(new TicketLineInfo("Vin", "001", "0", 1.0, 5.0, null));
        storeB.update("2", vueB.getTicket(), 1, "Paul", "mobile");   // B ecrit la v2

        vueA.getTicket().removeLine(0);
        try {
            storeA.update("2", vueA.getTicket(), 1, "Marie", "caisse");
            fail("A ne doit pas ecraser la modification de B");
        } catch (OrderConflictException e) {
            assertEquals(1, e.getExpectedVersion());
            assertEquals(2, e.getCurrent().getVersion());
            assertTrue(e.getMessage(), e.getMessage().contains("Paul"));
        }
        // la version de B est intacte
        TableTicketStore.Snapshot now = storeA.load("2");
        assertEquals(2, now.getVersion());
        assertEquals(2, now.getTicket().getLinesCount());
    }

    @Test
    public void creationSurTableDejaOccupeeRefusee() throws Exception {
        storeA.create("3", ticketAvec("Salade"), "Marie", "caisse");
        try {
            storeB.create("3", ticketAvec("Autre"), "Paul", "mobile");
            fail("double creation acceptee");
        } catch (OrderConflictException e) {
            assertEquals("Salade", e.getCurrent().getTicket().getLine(0).getProductName());
        }
    }

    @Test
    public void suppressionApresModificationAilleursRefusee() throws Exception {
        storeA.create("4", ticketAvec("The"), "Marie", "caisse");
        TableTicketStore.Snapshot s = storeB.load("4");
        storeB.update("4", s.getTicket(), 1, "Paul", "mobile");
        try {
            storeA.delete("4", 1);
            fail("suppression d'une commande modifiee ailleurs");
        } catch (OrderConflictException e) {
            assertNotNull(storeA.load("4"));
        }
    }

    @Test
    public void modificationDUneCommandeSupprimee() throws Exception {
        storeA.create("5", ticketAvec("Eau"), "Marie", "caisse");
        TableTicketStore.Snapshot s = storeB.load("5");
        storeA.delete("5", 1);
        try {
            storeB.update("5", s.getTicket(), 1, "Paul", "mobile");
            fail();
        } catch (OrderConflictException e) {
            assertNull(e.getCurrent());
        }
        assertNull("pas de recreation implicite", storeA.load("5"));
    }

    @Test
    public void ancienneCommandeSansVersionVaut1() throws Exception {
        // commande ecrite comme le faisait l'ancienne caisse (sans propriete de version)
        DataLogicReceipts dl = new DataLogicReceipts();
        dl.init(posteA);
        dl.insertSharedTicket("6", ticketAvec("Ancien"), 0);
        TableTicketStore.Snapshot s = storeB.load("6");
        assertEquals(1, s.getVersion());
        assertEquals(2, storeB.update("6", s.getTicket(), 1, "Paul", "mobile"));
    }

    @Test
    public void deuxEcrituresSimultaneesUneSeuleGagne() throws Exception {
        storeA.create("7", ticketAvec("Plat"), "Marie", "caisse");
        final TableTicketStore.Snapshot a = storeA.load("7");
        final TableTicketStore.Snapshot b = storeB.load("7");
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger conflits = new AtomicInteger();
        final CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[2];
        final TableTicketStore[] stores = {storeA, storeB};
        final TableTicketStore.Snapshot[] snaps = {a, b};
        for (int i = 0; i < 2; i++) {
            final int n = i;
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await();
                        snaps[n].getTicket().addLine(new TicketLineInfo("Ajout" + n, "001", "0", 1.0, 1.0, null));
                        stores[n].update("7", snaps[n].getTicket(), 1, "poste" + n, "test");
                        ok.incrementAndGet();
                    } catch (OrderConflictException e) {
                        conflits.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            threads[i].start();
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(30000);
        }
        assertEquals("une seule ecriture acceptee", 1, ok.get());
        assertEquals(1, conflits.get());
        TableTicketStore.Snapshot fin = storeA.load("7");
        assertEquals(2, fin.getVersion());
        assertEquals(2, fin.getTicket().getLinesCount());
    }
}
