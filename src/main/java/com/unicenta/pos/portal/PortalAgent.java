//    OLPOS (derive d'uniCenta oPOS) - synchronisation avec le portail Patron
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.portal;

import com.unicenta.pos.api.Json;
import com.unicenta.pos.forms.DataLogicSystem;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connexion SORTANTE de la caisse vers le portail (HTTPS) :
 * <ul>
 *   <li>exporte les ventes, clotures et commandes en cours dans des lots places
 *       dans une FILE PERSISTANTE propre au restaurant associe
 *       ({@code portail\sync\<restaurant>\file\}) ;</li>
 *   <li>chemin rapide (toutes les 60 s) : nouveaux tickets apres le curseur ;
 *       rapprochement (toutes les 10 min et au demarrage) : empreinte de chaque
 *       ticket, pour envoyer aussi les ventes ANTIDATEES, les tickets CORRIGES
 *       et les tickets SUPPRIMES ;</li>
 *   <li>chaque lot porte un numero de version croissant ({@code seq}) : le
 *       portail n'applique jamais un lot plus ancien sur une version plus
 *       recente ;</li>
 *   <li>chaque lot porte l'identifiant du restaurant : apres une nouvelle
 *       association, les lots d'un autre restaurant sont supprimes, jamais
 *       envoyes ;</li>
 *   <li>attend les demandes de connexion du patron et les fait verifier par
 *       {@link PortalAuthenticator}.</li>
 * </ul>
 * Rien n'est ouvert sur Internet cote caisse ; sans Internet, la caisse
 * fonctionne normalement et les lots attendent.
 */
public final class PortalAgent {

    private static final Logger LOG = Logger.getLogger(PortalAgent.class.getName());
    static final long SYNC_EVERY_MS = 60_000L;
    static final long RECONCILE_EVERY_MS = 10 * 60_000L;
    static final int MAX_PER_BATCH = PortalExporter.MAX_TICKETS;

    private final PortalLink link;
    private final PortalExporter exporter;
    private final PortalAuthenticator auth;
    private final Map<String, Object> meta;
    private final HttpClient http;
    private final LongSupplier clock;
    private volatile boolean running;
    private volatile long lastContact;
    private volatile String lastError;
    private long lastReconcile;
    private Thread syncThread;
    private Thread pollThread;

    public PortalAgent(PortalLink link, Connection connection, DataLogicSystem dl, Map<String, Object> meta) {
        this(link, () -> connection, dl, meta);
    }

    /**
     * Connexion redemandee a chaque cycle : avec MySQL, une connexion coupee par
     * le serveur (redemarrage, reseau, wait_timeout) est rouverte automatiquement.
     */
    public PortalAgent(PortalLink link, PortalExporter.ConnectionSource connection, DataLogicSystem dl,
            Map<String, Object> meta) {
        this(link, connection, dl, meta, System::currentTimeMillis);
    }

    PortalAgent(PortalLink link, PortalExporter.ConnectionSource connection, DataLogicSystem dl,
            Map<String, Object> meta, LongSupplier clock) {
        this.link = link;
        this.exporter = new PortalExporter(connection);
        this.auth = new PortalAuthenticator(link, dl, System::currentTimeMillis);
        this.meta = meta;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();   // certificats verifies
    }

    // ================================================================== association

    /** Association avec le code a usage unique donne par l'operateur du portail. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> pair(PortalLink link, String url, String code, String deviceName,
            Map<String, Object> meta) throws Exception {
        String base = PortalLink.checkUrl(url);
        String[] k = link.ensureKeys();
        HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/device/pair"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Json.obj("code", code, "deviceName", deviceName,
                        "signKey", k[0], "encKey", k[1], "meta", meta)))).build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body = (Map<String, Object>) Json.parse(r.body());
        if (r.statusCode() != 200) {
            Map<String, Object> err = (Map<String, Object>) body.get("error");
            throw new IOException(err == null ? "Erreur " + r.statusCode() : (String) err.get("message"));
        }
        String rid = (String) body.get("restaurantId");
        // Les lots et curseurs d'un AUTRE restaurant ne doivent jamais partir vers celui-ci
        purgeOtherRestaurants(link, rid);
        link.savePairing(base, rid, (String) body.get("slug"), (String) body.get("name"),
                (String) body.get("deviceToken"));
        return body;
    }

    /** Supprime les donnees de synchronisation de tout restaurant autre que {@code keepRid}. */
    static void purgeOtherRestaurants(PortalLink link, String keepRid) throws IOException {
        // ancienne disposition (avant rangement par restaurant) : jamais reutilisee
        deleteTree(new File(link.dir(), "file"));
        Files.deleteIfExists(new File(link.dir(), "curseur.properties").toPath());
        File[] dirs = new File(link.dir(), "sync").listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.getName().equals(safe(keepRid))) {
                    deleteTree(d);
                }
            }
        }
    }

    // ================================================================== demarrage

    public synchronized void start() {
        if (running || !link.isPaired()) {
            return;
        }
        running = true;
        syncThread = new Thread(this::syncLoop, "portail-synchronisation");
        pollThread = new Thread(this::pollLoop, "portail-connexions");
        syncThread.setDaemon(true);
        pollThread.setDaemon(true);
        syncThread.start();
        pollThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (syncThread != null) {
            syncThread.interrupt();
        }
        if (pollThread != null) {
            pollThread.interrupt();
        }
    }

    public long getLastContact() {
        return lastContact;
    }

    public String getLastError() {
        return lastError;
    }

    public int pendingBatches() {
        File q = queueDir();
        String[] f = q == null ? null : q.list((d, n) -> n.endsWith(".json"));
        return f == null ? 0 : f.length;
    }

    // ================================================================== synchronisation

    private void syncLoop() {
        while (running) {
            boolean more = false;
            try {
                more = syncOnce();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                lastError = e.getMessage();
                LOG.log(Level.FINE, "Portail : synchronisation reportee", e);
            }
            try {
                Thread.sleep(more ? 200 : SYNC_EVERY_MS);   // rattrapage d'un gros historique : sans attendre
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * Un cycle : nouveaux lots dans la file, puis envoi de la file.
     * @return true s'il reste des tickets a envoyer (historique en cours de rattrapage)
     */
    public synchronized boolean syncOnce() throws Exception {
        boolean more = enqueueNewData();
        flushQueue();
        return more;
    }

    private File syncDir() {
        String rid = link.restaurantId();
        return rid == null ? null : new File(new File(link.dir(), "sync"), safe(rid));
    }

    private File queueDir() {
        File d = syncDir();
        return d == null ? null : new File(d, "file");
    }

    @SuppressWarnings("unchecked")
    boolean enqueueNewData() throws Exception {
        File dir = syncDir();
        if (dir == null) {
            return false;
        }
        String rid = link.restaurantId();
        File stateFile = new File(dir, "etat.properties");
        File printsFile = new File(dir, "empreintes.properties");
        Properties state = load(stateFile);
        Properties prints = load(printsFile);

        // --- chemin rapide : tickets apres le curseur
        long ts = Long.parseLong(state.getProperty("ticketTs", "0"));
        String cursorId = state.getProperty("ticketId", "");
        Map<String, Object> tickets = new LinkedHashMap<>();
        List<Object> fresh = exporter.ticketsAfter(ts, cursorId);
        for (Object o : fresh) {
            Map<String, Object> t = (Map<String, Object>) o;
            tickets.put((String) t.get("id"), t);
        }
        List<String> deleted = new ArrayList<>();
        boolean more = fresh.size() >= MAX_PER_BATCH;
        Map<String, String> current = null;

        // --- rapprochement : ventes antidatees, corrections, suppressions
        long now = clock.getAsLong();
        if (more || lastReconcile == 0 || now - lastReconcile >= RECONCILE_EVERY_MS
                || "1".equals(state.getProperty("reconcilePending"))) {
            current = exporter.fingerprints();
            int room = MAX_PER_BATCH - tickets.size();
            boolean pending = false;
            for (Map.Entry<String, String> e : current.entrySet()) {
                if (e.getValue().equals(prints.getProperty(e.getKey())) || tickets.containsKey(e.getKey())) {
                    continue;
                }
                if (room <= 0) {
                    pending = true;
                    break;
                }
                Map<String, Object> t = exporter.ticket(e.getKey());
                if (t != null) {
                    tickets.put(e.getKey(), t);
                    room--;
                }
            }
            for (String id : prints.stringPropertyNames()) {
                if (!current.containsKey(id)) {
                    deleted.add(id);
                }
            }
            state.setProperty("reconcilePending", pending ? "1" : "0");
            more |= pending;
            lastReconcile = now;
        }

        // --- version du lot : croissante, meme apres une remise a zero de l'horloge ou du dossier
        long seq = Math.max(now, Long.parseLong(state.getProperty("seq", "0")) + 1);
        long closureEnd = Long.parseLong(state.getProperty("closureEnd", "0"));
        List<Object> closures = exporter.closures(closureEnd);
        Map<String, Object> batch = Json.obj("batchId", UUID.randomUUID().toString(), "rid", rid, "seq", seq,
                "meta", meta, "tickets", new ArrayList<Object>(tickets.values()), "deletedTickets", deleted,
                "closures", closures, "openOrders", exporter.openOrders(), "users", auth.usersState());
        write(new File(queueDir(), String.format("%015d-%s.json", seq, batch.get("batchId"))), Json.write(batch));

        // --- etat avance APRES l'ecriture du lot sur disque
        if (!fresh.isEmpty()) {
            Map<String, Object> last = (Map<String, Object>) fresh.get(fresh.size() - 1);
            state.setProperty("ticketTs", String.valueOf(last.get("ts")));
            state.setProperty("ticketId", (String) last.get("id"));
        }
        for (Object o : closures) {
            Object end = ((Map<String, Object>) o).get("end");
            if (end instanceof Number && ((Number) end).longValue() > closureEnd) {
                closureEnd = ((Number) end).longValue();
            }
        }
        state.setProperty("closureEnd", Long.toString(closureEnd));
        state.setProperty("seq", Long.toString(seq));
        if (!tickets.isEmpty() || !deleted.isEmpty()) {
            Map<String, String> fp = current != null ? current : exporter.fingerprints();
            for (String id : tickets.keySet()) {
                String f = fp.get(id);
                if (f != null) {
                    prints.setProperty(id, f);
                }
            }
            for (String id : deleted) {
                prints.remove(id);
            }
            save(prints, printsFile, "OLPOS - empreintes des tickets envoyes au portail");
        }
        save(state, stateFile, "OLPOS - position de synchronisation");
        return more;
    }

    /** Envoie les lots dans l'ordre ; s'arrete a la premiere erreur (nouvel essai plus tard). */
    @SuppressWarnings("unchecked")
    void flushQueue() throws Exception {
        File q = queueDir();
        File[] files = q == null ? null : q.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) {
            return;
        }
        Arrays.sort(files);
        String rid = link.restaurantId();
        for (File f : files) {
            byte[] body = Files.readAllBytes(f.toPath());
            Map<String, Object> b;
            try {
                b = (Map<String, Object>) Json.parse(new String(body, StandardCharsets.UTF_8));
            } catch (Json.ParseException e) {
                Files.delete(f.toPath());   // fichier abime : le rapprochement renverra les donnees
                continue;
            }
            if (rid == null || !rid.equals(b.get("rid"))) {
                Files.delete(f.toPath());   // lot d'un autre restaurant : jamais envoye
                continue;
            }
            HttpResponse<String> r = http.send(request("/api/device/sync").timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 409) {
                Files.delete(f.toPath());   // refuse : lot d'un autre restaurant, jamais renvoye
                continue;
            }
            if (r.statusCode() != 200) {
                throw new IOException("Portail : reponse " + r.statusCode() + " a l'envoi d'un lot");
            }
            lastContact = System.currentTimeMillis();
            lastError = null;
            Files.delete(f.toPath());   // recu (ou deja recu) par le portail
        }
    }

    // ================================================================== connexions du patron

    @SuppressWarnings("unchecked")
    private void pollLoop() {
        long backoff = 2_000;
        while (running) {
            try {
                HttpResponse<String> r = http.send(request("/api/device/poll?wait=25").timeout(Duration.ofSeconds(40))
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(Json.obj("users", auth.usersState()))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) {
                    throw new IOException("Portail : reponse " + r.statusCode());
                }
                lastContact = System.currentTimeMillis();
                backoff = 2_000;
                Map<String, Object> body = (Map<String, Object>) Json.parse(r.body());
                for (Object o : (List<Object>) body.get("authRequests")) {
                    Map<String, Object> req = (Map<String, Object>) o;
                    String rid = (String) req.get("id");
                    Map<String, Object> answer = auth.handle(rid, (String) req.get("nonce"), (String) req.get("ciphertext"));
                    http.send(request("/api/device/auth/" + rid).timeout(Duration.ofSeconds(15))
                            .POST(HttpRequest.BodyPublishers.ofString(Json.write(answer))).build(),
                            HttpResponse.BodyHandlers.ofString());
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                lastError = e.getMessage();
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    return;
                }
                backoff = Math.min(60_000, backoff * 2);
            }
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(link.url() + path)).header("Authorization", "Bearer " + link.token())
                .header("Content-Type", "application/json");
    }

    // ================================================================== fichiers

    /** Nom de dossier sur pour un identifiant de restaurant. */
    static String safe(String rid) {
        return rid.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Properties load(File f) throws IOException {
        Properties p = new Properties();
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
            }
        }
        return p;
    }

    private static void save(Properties p, File f, String comment) throws IOException {
        File dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Dossier inaccessible : " + dir);
        }
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            p.store(out, comment);
        }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void write(File f, String text) throws IOException {
        File dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Dossier inaccessible : " + dir);
        }
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE);
    }

    static void deleteTree(File f) throws IOException {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        Files.deleteIfExists(f.toPath());
    }

    /** Pour les tests : identifiants deja envoyes (empreintes connues). */
    Set<String> knownTickets() throws IOException {
        File d = syncDir();
        return d == null ? new HashSet<>() : load(new File(d, "empreintes.properties")).stringPropertyNames();
    }
}
