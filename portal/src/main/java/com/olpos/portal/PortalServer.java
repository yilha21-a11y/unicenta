//    OLPOS Portail Patron - serveur HTTP
//    Licence GPL v3 (voir ../LICENSE.md).
package com.olpos.portal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serveur du portail. En production il est place DERRIERE un proxy HTTPS
 * (Caddy, nginx...) : il ecoute par defaut sur 127.0.0.1 uniquement.
 *
 * <pre>
 * Caisses (jeton d'appareil, connexion SORTANTE de la caisse) :
 *   POST /api/device/pair            association par code a usage unique
 *   POST /api/device/sync            lot de donnees (identifiant de lot, sans doublon)
 *   GET  /api/device/poll?wait=25    demandes de connexion a verifier (attente longue)
 *   POST /api/device/auth/{id}       preuve signee ou refus
 * Patron (cookie de session, LECTURE SEULE) :
 *   GET  /api/patron/challenge?restaurant=...   cle de la caisse + defi
 *   POST /api/patron/login                      connexion deleguee a la caisse
 *   POST /api/patron/logout
 *   GET  /api/patron/me | summary | tickets | tickets/{id} | closures | open-orders
 *   GET  /api/patron/export.csv | export.pdf
 * </pre>
 */
public final class PortalServer {

    private static final Logger LOG = Logger.getLogger(PortalServer.class.getName());
    static final String COOKIE = "olpos_patron";
    private static final Map<String, String[]> FILES = new LinkedHashMap<>();

    static {
        FILES.put("/", new String[] {"index.html", "text/html; charset=utf-8"});
        FILES.put("/index.html", new String[] {"index.html", "text/html; charset=utf-8"});
        FILES.put("/app.js", new String[] {"app.js", "application/javascript; charset=utf-8"});
        FILES.put("/app.css", new String[] {"app.css", "text/css; charset=utf-8"});
        FILES.put("/logo.png", new String[] {"logo.png", "image/png"});
        FILES.put("/favicon.ico", new String[] {"favicon-32.png", "image/png"});
        FILES.put("/favicon-32.png", new String[] {"favicon-32.png", "image/png"});
        FILES.put("/icon-192.png", new String[] {"icon-192.png", "image/png"});
        FILES.put("/apple-touch-icon.png", new String[] {"apple-touch-icon.png", "image/png"});
    }

    private final PortalService portal;
    private final StatsService stats;
    private final boolean secureCookies;
    private final boolean trustProxy;
    private HttpServer server;

    public PortalServer(PortalService portal, boolean secureCookies, boolean trustProxy) {
        this.portal = portal;
        this.stats = new StatsService(portal.db());
        this.secureCookies = secureCookies;
        this.trustProxy = trustProxy;
    }

    public void start(String bind, int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(bind, port), 0);
        s.createContext("/", this::handle);
        s.setExecutor(Executors.newFixedThreadPool(48, r -> {
            Thread t = new Thread(r, "portail");
            t.setDaemon(true);
            return t;
        }));
        s.start();
        server = s;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ================================================================== routage

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            security(ex);
            if (path.startsWith("/api/device/")) {
                send(ex, 200, device(ex, path.substring("/api/device/".length())));
            } else if (path.startsWith("/api/patron/")) {
                patron(ex, path.substring("/api/patron/".length()));
            } else {
                file(ex, path);
            }
        } catch (PortalService.PortalError e) {
            send(ex, e.status, Json.obj("error", Json.obj("code", e.code, "message", e.getMessage())));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Erreur du portail", e);
            send(ex, 500, Json.obj("error", Json.obj("code", "internal_error", "message", "Erreur interne du portail.")));
        } finally {
            ex.close();
        }
    }

    @SuppressWarnings("unchecked")
    private Object device(HttpExchange ex, String route) throws Exception {
        String method = ex.getRequestMethod();
        if ("pair".equals(route)) {
            requirePost(method);
            return portal.pair(jsonBody(ex, 64 * 1024));
        }
        String rid = portal.device(bearer(ex));
        if ("sync".equals(route)) {
            requirePost(method);
            return portal.sync(rid, jsonBody(ex, 16 * 1024 * 1024));
        }
        if ("poll".equals(route)) {
            long wait = Math.min(25_000, Math.max(0, parseLong(query(ex, "wait"), 0) * 1000));
            Map<String, Object> body = "POST".equals(method) ? jsonBody(ex, 1024 * 1024) : null;
            if (body != null && body.get("users") instanceof List) {
                portal.updateUsers(rid, (List<Object>) body.get("users"));
            }
            return Json.obj("authRequests", portal.poll(rid, wait));
        }
        if (route.startsWith("auth/")) {
            requirePost(method);
            portal.authResponse(rid, route.substring(5), jsonBody(ex, 64 * 1024));
            return Json.obj("status", "ok");
        }
        throw new PortalService.PortalError(404, "not_found", "Adresse inconnue.");
    }

    @SuppressWarnings("unchecked")
    private void patron(HttpExchange ex, String route) throws Exception {
        String method = ex.getRequestMethod();
        switch (route) {
            case "challenge":
                requireGet(method);
                send(ex, 200, portal.challenge(query(ex, "restaurant")));
                return;
            case "login": {
                requirePost(method);
                Map<String, Object> b = jsonBody(ex, 16 * 1024);
                Map<String, Object> r = portal.login((String) b.get("restaurant"), (String) b.get("user"),
                        (String) b.get("challengeId"), (String) b.get("ciphertext"), clientIp(ex));
                String token = (String) r.remove("token");
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict"
                        + (secureCookies ? "; Secure" : "") + "; Max-Age=" + PortalService.SESSION_MAX / 1000);
                send(ex, 200, r);
                return;
            }
            case "logout":
                requirePost(method);
                portal.logout(cookie(ex));
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                send(ex, 200, Json.obj("status", "ok"));
                return;
            default:
                break;
        }
        // ---- tout le reste : session obligatoire, GET seulement (lecture seule)
        requireGet(method);
        PortalService.Session s = portal.session(cookie(ex));
        if (s == null) {
            throw new PortalService.PortalError(401, "unauthorized", "Session expiree ou revoquee : reconnectez-vous.");
        }
        Map<String, Object> info = portal.restaurantInfo(s.rid);
        ZoneId zone = zone(info);
        long[] p;
        try {
            p = StatsService.period(query(ex, "period"), query(ex, "from"), query(ex, "to"), zone, portal.now());
        } catch (RuntimeException e) {
            throw new PortalService.PortalError(400, "invalid_period", "Periode invalide (dates au format AAAA-MM-JJ).");
        }
        if (p[1] <= p[0] || p[1] - p[0] > 400L * 86_400_000L) {
            throw new PortalService.PortalError(400, "invalid_period", "Periode invalide (400 jours au plus).");
        }
        switch (route) {
            case "me":
                info.put("lastSyncText", text(info.get("last_sync"), zone));
                send(ex, 200, Json.obj("user", s.name, "restaurant", info, "readOnly", true));
                return;
            case "summary":
                send(ex, 200, stats.summary(s.rid, p[0], p[1], zone));
                return;
            case "tickets":
                List<Object> list = stats.ticketList(s.rid, p[0], p[1], 500);
                for (Object o : list) {
                    ((Map<String, Object>) o).put("when", text(((Map<String, Object>) o).get("ts"), zone));
                }
                send(ex, 200, Json.obj("tickets", list));
                return;
            case "closures":
                List<Object> cl = stats.closures(s.rid, p[0], p[1]);
                for (Object o : cl) {
                    Map<String, Object> m = (Map<String, Object>) o;
                    m.put("startText", text(m.get("start"), zone));
                    m.put("endText", text(m.get("end"), zone));
                }
                send(ex, 200, Json.obj("closures", cl));
                return;
            case "open-orders":
                Map<String, Object> oo = stats.openOrders(s.rid);
                oo.put("tsText", text(oo.get("ts"), zone));
                send(ex, 200, oo);
                return;
            case "export.csv":
            case "export.pdf": {
                Exports.Money money = new Exports.Money((String) info.get("currency"), (String) info.get("locale"));
                List<String[]> rows = Exports.reportRows(info, stats.summary(s.rid, p[0], p[1], zone), zone, money);
                boolean csv = route.endsWith("csv");
                byte[] b = csv ? Exports.csv(rows) : Exports.pdf(rows);
                ex.getResponseHeaders().set("Content-Type", csv ? "text/csv; charset=utf-8" : "application/pdf");
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"olpos-" + info.get("slug")
                        + (csv ? ".csv" : ".pdf") + "\"");
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
                return;
            }
            default:
                break;
        }
        if (route.startsWith("tickets/")) {
            Object t = stats.ticket(s.rid, URLDecoder.decode(route.substring(8), "UTF-8"));
            if (t == null) {
                // meme reponse qu'un ticket inexistant : on ne revele rien d'un autre restaurant
                throw new PortalService.PortalError(404, "not_found", "Ticket introuvable.");
            }
            ((Map<String, Object>) t).put("when", text(((Map<String, Object>) t).get("ts"), zone));
            send(ex, 200, Json.obj("ticket", t));
            return;
        }
        throw new PortalService.PortalError(404, "not_found", "Adresse inconnue.");
    }

    /** Date lisible dans le fuseau du restaurant (meme base de fuseaux que les statistiques). */
    static String text(Object millis, ZoneId zone) {
        if (!(millis instanceof Number) || ((Number) millis).longValue() <= 0) {
            return null;
        }
        return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zone)
                .format(java.time.Instant.ofEpochMilli(((Number) millis).longValue()));
    }

    static ZoneId zone(Map<String, Object> info) {
        try {
            return info != null && info.get("timezone") != null ? ZoneId.of((String) info.get("timezone")) : ZoneId.of("UTC");
        } catch (RuntimeException e) {
            return ZoneId.of("UTC");
        }
    }

    // ================================================================== fichiers de l'interface

    private void file(HttpExchange ex, String path) throws IOException {
        String[] f = FILES.get(path);
        if (f == null || !"GET".equals(ex.getRequestMethod())) {
            send(ex, 404, Json.obj("error", Json.obj("code", "not_found", "message", "Page introuvable.")));
            return;
        }
        byte[] b;
        try (InputStream in = PortalServer.class.getResourceAsStream("/com/olpos/portal/web/" + f[0])) {
            b = readAll(in, Integer.MAX_VALUE);
        }
        ex.getResponseHeaders().set("Content-Type", f[1]);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private void security(HttpExchange ex) {
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self';"
                + " img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        if (secureCookies) {
            ex.getResponseHeaders().set("Strict-Transport-Security", "max-age=31536000");
        }
    }

    // ================================================================== outils HTTP

    private static void requirePost(String m) throws PortalService.PortalError {
        if (!"POST".equals(m)) {
            throw new PortalService.PortalError(405, "method_not_allowed", "Methode non autorisee.");
        }
    }

    private static void requireGet(String m) throws PortalService.PortalError {
        if (!"GET".equals(m)) {
            throw new PortalService.PortalError(405, "read_only", "Le portail est en lecture seule.");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonBody(HttpExchange ex, int max) throws IOException, PortalService.PortalError {
        byte[] b;
        try (InputStream in = ex.getRequestBody()) {
            b = readAll(in, max);
        } catch (IllegalStateException e) {
            throw new PortalService.PortalError(413, "too_large", "Requete trop volumineuse.");
        }
        try {
            Object o = Json.parse(new String(b, StandardCharsets.UTF_8));
            if (o instanceof Map) {
                return (Map<String, Object>) o;
            }
        } catch (Json.ParseException e) {
            // ci-dessous
        }
        throw new PortalService.PortalError(400, "invalid_json", "JSON invalide.");
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) {
                throw new IllegalStateException("trop volumineux");
            }
        }
        return out.toByteArray();
    }

    private static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.regionMatches(true, 0, "Bearer ", 0, 7) ? h.substring(7).trim() : null;
    }

    private static String cookie(HttpExchange ex) {
        List<String> all = ex.getRequestHeaders().get("Cookie");
        if (all == null) {
            return null;
        }
        for (String h : all) {
            for (String part : h.split(";")) {
                String p = part.trim();
                if (p.startsWith(COOKIE + "=")) {
                    return p.substring(COOKIE.length() + 1);
                }
            }
        }
        return null;
    }

    private String clientIp(HttpExchange ex) {
        String f = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (trustProxy && f != null && !f.isEmpty()) {
            return f.split(",")[0].trim();
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private static String query(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            if (k.equals(name)) {
                try {
                    return eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
