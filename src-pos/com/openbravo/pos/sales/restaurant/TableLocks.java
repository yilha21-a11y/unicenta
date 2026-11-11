//    uniCenta oPOS - synchronisation caisse / API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.openbravo.pos.sales.restaurant;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verrou par table, partage entre l'ecran de caisse et l'API mobile (meme
 * programme).
 *
 * <ul>
 *   <li>La CAISSE garde le verrou tant qu'une table est ouverte a l'ecran
 *       (de l'ouverture jusqu'au retour au plan de salle / encaissement).</li>
 *   <li>L'API ne prend le verrou que le temps d'une requete (quelques
 *       millisecondes). Si la caisse a la table ouverte, l'API refuse avec un
 *       message clair au lieu d'attendre ou d'ecraser.</li>
 * </ul>
 *
 * Ce verrou protege les modifications faites dans CE programme. Les
 * modifications venant d'un autre poste (plusieurs caisses sur une meme base
 * MySQL) sont detectees par le numero de version de {@link TableTicketStore}.
 */
public final class TableLocks {

    private static final Logger LOG = Logger.getLogger(TableLocks.class.getName());
    private static final TableLocks INSTANCE = new TableLocks();

    /** Qui tient une table. */
    public enum Source {
        CAISSE("caisse"), MOBILE("mobile");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Detenteur d'un verrou (immuable). */
    public static final class Holder {
        private final Source source;
        private final String who;
        private final long since;

        Holder(Source source, String who, long since) {
            this.source = source;
            this.who = who;
            this.since = since;
        }

        public Source getSource() {
            return source;
        }

        public String getWho() {
            return who;
        }

        public long getSince() {
            return since;
        }
    }

    /** Prevenu apres chaque modification d'une table (pour rafraichir le plan de salle). */
    public interface Listener {
        void tableChanged(String tableId);
    }

    private final Map<String, Holder> holders = new HashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Instance partagee par la caisse et l'API. */
    public static TableLocks get() {
        return INSTANCE;
    }

    /** Pour les tests : une instance independante. */
    public static TableLocks newIsolatedInstance() {
        return new TableLocks();
    }

    TableLocks() {
    }

    /**
     * La caisse ouvre une table. Attend au plus {@code waitMs} si une requete
     * mobile est en cours sur cette table (elle dure normalement quelques ms).
     *
     * @return true si la caisse tient maintenant la table
     */
    public synchronized boolean acquireTill(String tableId, String who, long waitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            Holder h = holders.get(tableId);
            if (h == null || h.source == Source.CAISSE) {
                // libre, ou deja tenue par la caisse (un seul ecran de caisse par programme)
                holders.put(tableId, new Holder(Source.CAISSE, who, h == null ? System.currentTimeMillis() : h.since));
                return true;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }
            wait(remaining);
        }
    }

    public synchronized void releaseTill(String tableId) {
        Holder h = holders.get(tableId);
        if (h != null && h.source == Source.CAISSE) {
            holders.remove(tableId);
            notifyAll();
        }
    }

    /**
     * Une requete mobile veut modifier la table. Si une autre requete mobile est
     * en cours, attend au plus {@code waitMs} (les requetes sont mises en file).
     * Si la caisse tient la table, ne l'attend pas.
     *
     * @return null si le verrou est obtenu, sinon le detenteur qui bloque
     */
    public synchronized Holder tryAcquireApi(String tableId, String who, long waitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            Holder h = holders.get(tableId);
            if (h == null) {
                holders.put(tableId, new Holder(Source.MOBILE, who, System.currentTimeMillis()));
                return null;
            }
            if (h.source == Source.CAISSE) {
                return h;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return h;
            }
            wait(remaining);
        }
    }

    public synchronized void releaseApi(String tableId) {
        Holder h = holders.get(tableId);
        if (h != null && h.source == Source.MOBILE) {
            holders.remove(tableId);
            notifyAll();
        }
    }

    /** Detenteur actuel, ou null si la table est libre. */
    public synchronized Holder holder(String tableId) {
        return holders.get(tableId);
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Signale qu'une commande a change (appele hors verrou). */
    public void fireChanged(String tableId) {
        for (Listener l : listeners) {
            try {
                l.tableChanged(tableId);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Erreur dans un ecouteur de table", e);
            }
        }
    }
}
