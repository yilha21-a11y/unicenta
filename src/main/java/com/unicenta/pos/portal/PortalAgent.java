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
import java.sql.Connection;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connexion SORTANTE de la caisse vers le portail (HTTPS) :
 * <ul>
 *   <li>exporte les nouvelles ventes, clotures et commandes en cours dans des
 *       lots places dans une FILE PERSISTANTE ({@code portail\file\}) ; le
 *       curseur avance une fois le lot ecrit sur disque ;</li>
 *   <li>envoie les lots dans l'ordre ; un lot n'est retire de la file qu'apres
 *       accord du portail ; coupure => nouvel essai plus tard (le portail ignore
 *       un lot deja recu et remplace un ticket deja connu : pas de doublon) ;</li>
 *   <li>attend les demandes de connexion du patron (attente longue) et les fait
 *       verifier par {@link PortalAuthenticator}.</li>
 * </ul>
 * Rien n'est ouvert sur Internet cote caisse ; sans Internet, la caisse
 * fonctionne normalement et les lots attendent.
 */
public final class PortalAgent {

    private static final Logger LOG = Logger.getLogger(PortalAgent.class.getName());
    static final long SYNC_EVERY_MS = 60_000L;

    private final PortalLink link;
    private final PortalExporter exporter;
    private final PortalAuthenticator auth;
    private final Map<String, Object> meta;
    private final File queue;
    private final File cursorFile;
    private final HttpClient http;
    private volatile boolean running;
    private volatile long lastContact;
    private volatile String lastError;
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
        this.link = link;
        this.exporter = new PortalExporter(connection);
        this.auth = new PortalAuthenticator(link, dl, System::currentTimeMillis);
        this.meta = meta;
        this.queue = new File(link.dir(), "file");
        this.cursorFile = new File(link.dir(), "curseur.properties");
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
        link.savePairing(base, (String) body.get("restaurantId"), (String) body.get("slug"), (String) body.get("name"),
                (String) body.get("deviceToken"));
        return body;
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
        String[] f = queue.list((d, n) -> n.endsWith(".json"));
        return f == null ? 0 : f.length;
    }

    // ================================================================== synchronisation

    private void syncLoop() {
        while (running) {
            try {
                syncOnce();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                lastError = e.getMessage();
                LOG.log(Level.FINE, "Portail : synchronisation reportee", e);
            }
            try {
                Thread.sleep(SYNC_EVERY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Un cycle : nouveaux lots dans la file, puis envoi de la file. */
    public synchronized void syncOnce() throws Exception {
        enqueueNewData();
        flushQueue();
    }

    @SuppressWarnings("unchecked")
    void enqueueNewData() throws Exception {
        Properties cur = cursor();
        long ts = Long.parseLong(cur.getProperty("ticketTs", "0"));
        String id = cur.getProperty("ticketId", "");
        long closureEnd = Long.parseLong(cur.getProperty("closureEnd", "0"));
        List<Object> tickets = exporter.ticketsAfter(ts, id);
        List<Object> closures = exporter.closures(closureEnd);
        Map<String, Object> batch = Json.obj("batchId", UUID.randomUUID().toString(), "meta", meta,
                "tickets", tickets, "closures", closures, "openOrders", exporter.openOrders(), "users", auth.usersState());
        write(new File(queue, String.format("%015d-%s.json", System.currentTimeMillis(), batch.get("batchId"))),
                Json.write(batch));
        // curseur avance APRES l'ecriture du lot sur disque
        if (!tickets.isEmpty()) {
            Map<String, Object> last = (Map<String, Object>) tickets.get(tickets.size() - 1);
            cur.setProperty("ticketTs", String.valueOf(last.get("ts")));
            cur.setProperty("ticketId", (String) last.get("id"));
        }
        for (Object o : closures) {
            Object end = ((Map<String, Object>) o).get("end");
            if (end instanceof Number && ((Number) end).longValue() > closureEnd) {
                closureEnd = ((Number) end).longValue();
            }
        }
        cur.setProperty("closureEnd", Long.toString(closureEnd));
        saveCursor(cur);
    }

    /** Envoie les lots dans l'ordre ; s'arrete a la premiere erreur (nouvel essai plus tard). */
    void flushQueue() throws Exception {
        File[] files = queue.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) {
            return;
        }
        Arrays.sort(files);
        for (File f : files) {
            HttpResponse<String> r = http.send(request("/api/device/sync").timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(Files.readAllBytes(f.toPath()))).build(),
                    HttpResponse.BodyHandlers.ofString());
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

    private Properties cursor() throws IOException {
        Properties p = new Properties();
        if (cursorFile.isFile()) {
            try (InputStream in = new FileInputStream(cursorFile)) {
                p.load(in);
            }
        }
        return p;
    }

    private void saveCursor(Properties p) throws IOException {
        File tmp = new File(cursorFile.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            p.store(out, "OLPOS - position de synchronisation");
        }
        Files.move(tmp.toPath(), cursorFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static void write(File f, String text) throws IOException {
        File dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Dossier inaccessible : " + dir);
        }
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
