package com.openbravo.pos.sales.restaurant;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TableLocksTest {

    private final TableLocks locks = TableLocks.newIsolatedInstance();

    @Test
    public void caisseBloqueLeTelephone() throws Exception {
        assertTrue(locks.acquireTill("t1", "Marie", 0));
        TableLocks.Holder h = locks.tryAcquireApi("t1", "Paul", 500);
        assertNotNull("le telephone doit etre refuse", h);
        assertEquals(TableLocks.Source.CAISSE, h.getSource());
        assertEquals("Marie", h.getWho());
        // une autre table reste libre
        assertNull(locks.tryAcquireApi("t2", "Paul", 0));
        locks.releaseApi("t2");
        locks.releaseTill("t1");
        assertNull("libre apres fermeture a la caisse", locks.tryAcquireApi("t1", "Paul", 0));
    }

    @Test
    public void caisseAttendLaFinDUneRequeteMobile() throws Exception {
        assertNull(locks.tryAcquireApi("t1", "Paul", 0));
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean got = new AtomicBoolean();
        Thread till = new Thread(new Runnable() {
            @Override
            public void run() {
                started.countDown();
                try {
                    got.set(locks.acquireTill("t1", "Marie", 5000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        till.start();
        started.await();
        Thread.sleep(200);
        assertFalse("la caisse attend pendant la requete", got.get());
        locks.releaseApi("t1");
        till.join(5000);
        assertTrue("la caisse obtient la table des que la requete est finie", got.get());
        assertEquals(TableLocks.Source.CAISSE, locks.holder("t1").getSource());
    }

    @Test
    public void caisseAbandonneSiLeTelephoneNeLibereJamais() throws Exception {
        assertNull(locks.tryAcquireApi("t1", "Paul", 0));
        long t0 = System.currentTimeMillis();
        assertFalse(locks.acquireTill("t1", "Marie", 300));
        assertTrue(System.currentTimeMillis() - t0 >= 250);
    }

    @Test
    public void requetesMobilesMisesEnFile() throws Exception {
        assertNull(locks.tryAcquireApi("t1", "Paul", 0));
        // une 2e requete attend puis passe quand la 1re se termine
        final List<TableLocks.Holder> result = new ArrayList<>();
        Thread second = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    result.add(locks.tryAcquireApi("t1", "Julie", 5000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        second.start();
        Thread.sleep(150);
        locks.releaseApi("t1");
        second.join(5000);
        assertEquals(1, result.size());
        assertNull("la 2e requete a obtenu le verrou", result.get(0));
        // et si la 1re ne finit pas a temps : refus avec le detenteur
        TableLocks.Holder h = locks.tryAcquireApi("t1", "Luc", 100);
        assertEquals(TableLocks.Source.MOBILE, h.getSource());
    }

    @Test
    public void liberationsCroiseesSansEffet() throws Exception {
        assertTrue(locks.acquireTill("t1", "Marie", 0));
        locks.releaseApi("t1");   // l'API ne peut pas liberer un verrou de la caisse
        assertEquals(TableLocks.Source.CAISSE, locks.holder("t1").getSource());
    }

    @Test
    public void ecouteursPrevenus() throws Exception {
        final CountDownLatch called = new CountDownLatch(1);
        locks.addListener(new TableLocks.Listener() {
            @Override
            public void tableChanged(String tableId) {
                if ("t9".equals(tableId)) {
                    called.countDown();
                }
            }
        });
        locks.fireChanged("t9");
        assertTrue(called.await(1, TimeUnit.SECONDS));
    }
}
