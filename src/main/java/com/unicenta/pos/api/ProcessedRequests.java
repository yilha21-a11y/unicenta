//    OLPOS (derive d'uniCenta oPOS) - API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.api;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Memoire des requetes de modification deja traitees (identifiant choisi par le
 * telephone : {@code requestId}). Si le Wi-Fi coupe apres l'envoi, le telephone
 * renvoie la MEME requete : elle est reconnue et n'est pas appliquee deux fois.
 *
 * <p>Cette memoire (en RAM, 24 h, 5000 requetes) est completee par la liste des
 * derniers requestId enregistree dans la commande elle-meme (voir
 * MobileOrderService), qui survit a un redemarrage de la caisse.</p>
 */
final class ProcessedRequests {

    static final class Entry {
        final String tableId;
        final String fingerprint;
        final long version;
        final long at;

        Entry(String tableId, String fingerprint, long version, long at) {
            this.tableId = tableId;
            this.fingerprint = fingerprint;
            this.version = version;
            this.at = at;
        }
    }

    private final int maxEntries;
    private final long ttlMillis;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(256, 0.75f, false);

    ProcessedRequests(int maxEntries, long ttlMillis) {
        this.maxEntries = maxEntries;
        this.ttlMillis = ttlMillis;
    }

    synchronized Entry get(String requestId) {
        purge();
        return entries.get(requestId);
    }

    synchronized void put(String requestId, String tableId, String fingerprint, long version) {
        entries.remove(requestId);
        entries.put(requestId, new Entry(tableId, fingerprint, version, System.currentTimeMillis()));
        purge();
    }

    private void purge() {
        long limit = System.currentTimeMillis() - ttlMillis;
        for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Entry> e = it.next();
            if (entries.size() > maxEntries || e.getValue().at < limit) {
                it.remove();
            } else {
                break; // ordre d'insertion : les suivants sont plus recents
            }
        }
    }
}
