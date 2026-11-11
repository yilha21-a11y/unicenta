//    OLPOS (derive d'uniCenta oPOS) - API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.api;

import com.unicenta.basic.BasicException;
import com.unicenta.pos.forms.AppLocal;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * API REST (JSON) pour l'application mobile, integree au programme de caisse.
 *
 * <p>Le telephone ne voit jamais la base de donnees : il passe uniquement par
 * ces adresses, qui appliquent les regles de la caisse (voir {@link MobileDataService}).</p>
 *
 * <pre>
 * Public :
 *   GET  /api/v1/health                 etat du serveur
 *   GET  /api/v1/users                  serveurs autorises sur mobile (si api.users.list=true)
 *   POST /api/v1/auth/login             {"userId": "...", "password": "..."} -> jeton
 * Avec en-tete "Authorization: Bearer &lt;jeton&gt;" et la permission mobile.Access :
 *   POST /api/v1/auth/logout
 *   GET  /api/v1/me
 *   GET  /api/v1/catalog                categories + produits
 *   GET  /api/v1/catalog/categories
 *   GET  /api/v1/catalog/products?category=ID
 *   GET  /api/v1/floors                 salles, tables, occupation
 *   GET  /api/v1/tables/{id}/order      commande en cours de la table (version, lignes, totaux)
 *   POST /api/v1/tables/{id}/order/changes
 *        {"requestId": "...", "baseVersion": N, "operations": [...]}  (voir MobileOrderService)
 *   POST /api/v1/tables/{id}/kitchen/send            {"sendId": "..."}  (voir KitchenService)
 *   POST /api/v1/tables/{id}/kitchen/{sendId}/resolve {"action": "printed" | "reprint"}
 *   POST /api/v1/tables/{id}/bill/print              {"printId": "..."}  addition provisoire (voir MobileBillService)
 * </pre>
 *
 * Pas d'encaissement ni d'impression depuis le telephone a ce stade.
 */
public final class ApiServer {

    public static final int API_VERSION = 1;
    private static final String PREFIX = "/api/v1";
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());

    private final ApiConfig config;
    private final MobileDataService data;
    private final ApiSessions sessions;
    private final MobileOrderService orders;
    private final KitchenService kitchen;
    private final MobileBillService bills;
    private final PrintRunner printRunner;
    private HttpServer server;
    private ExecutorService executor;

    public ApiServer(ApiConfig config, MobileDataService data) {
        this(config, data, new ApiSessions(config), com.unicenta.pos.sales.restaurant.TableLocks.get());
    }

    /** Avec impression des bons cuisine (caisse). */
    public ApiServer(ApiConfig config, MobileDataService data, MobilePrinter printer) {
        this(config, data, new ApiSessions(config), com.unicenta.pos.sales.restaurant.TableLocks.get(), printer, 20000);
    }

    /**
     * @param locks verrous de table partages avec l'ecran de caisse
     *              ({@code TableLocks.get()} en production)
     */
    public ApiServer(ApiConfig config, MobileDataService data, ApiSessions sessions,
            com.unicenta.pos.sales.restaurant.TableLocks locks) {
        this(config, data, sessions, locks, null, 20000);
    }

    /**
     * @param printer            impression des bons cuisine (null : envoi cuisine indisponible)
     * @param printTimeoutMillis au-dela, une impression sans reponse est "a verifier"
     */
    public ApiServer(ApiConfig config, MobileDataService data, ApiSessions sessions,
            com.unicenta.pos.sales.restaurant.TableLocks locks, MobilePrinter printer, long printTimeoutMillis) {
        this.config = config;
        this.data = data;
        this.sessions = sessions;
        this.orders = new MobileOrderService(data, locks, 2000);
        this.printRunner = new PrintRunner(printTimeoutMillis);   // une seule file pour tous les bons
        this.kitchen = new KitchenService(data, orders, locks, printer, printRunner);
        KitchenService.install(kitchen);   // meme service pour le bouton Cuisine de la caisse
        this.bills = new MobileBillService(data, orders, locks, printer, printRunner);
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        HttpServer s = HttpServer.create(new InetSocketAddress(config.getBind(), config.getPort()), 0);
        s.createContext(PREFIX + "/", new Router());
        s.createContext("/", new WebPage());   // page de prise de commande (telephone)
        executor = Executors.newFixedThreadPool(config.getThreads(), new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "api-mobile-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        s.setExecutor(executor);
        s.start();
        server = s;
        LOG.log(Level.INFO, "API mobile demarree sur {0}:{1}", new Object[] {config.getBind(), Integer.toString(getPort())});
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
            printRunner.shutdown();
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            LOG.info("API mobile arretee");
        }
    }

    /** Port reellement utilise (utile si api.port=0 dans les tests). */
    public synchronized int getPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public ApiSessions getSessions() {
        return sessions;
    }

    // ================================================================= routage

    /** Erreur fonctionnelle renvoyee au telephone en JSON. */
    public static final class ApiError extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;
        final String code;
        final transient Map<String, Object> extra;   // donnees ajoutees a la reponse (ex. commande a jour)

        ApiError(int status, String code, String message) {
            this(status, code, message, null);
        }

        /** Code d'erreur (ex. "nothing_to_send"). */
        public String getCode() {
            return code;
        }

        public int getStatus() {
            return status;
        }

        ApiError(int status, String code, String message, Map<String, Object> extra) {
            super(message);
            this.status = status;
            this.code = code;
            this.extra = extra;
        }
    }

    private final class Router implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                Object body = route(ex);
                send(ex, 200, body);
            } catch (ApiError e) {
                Map<String, Object> body = error(e.code, e.getMessage());
                if (e.extra != null) {
                    body.putAll(e.extra);
                }
                send(ex, e.status, body);
            } catch (BasicException e) {
                LOG.log(Level.WARNING, "API mobile : erreur base de donnees", e);
                send(ex, 500, error("database_error", "Erreur de base de donnees sur la caisse."));
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "API mobile : erreur interne", e);
                send(ex, 500, error("internal_error", "Erreur interne de la caisse."));
            } finally {
                ex.close();
            }
        }
    }

    /**
     * Sert la page web de prise de commande. Liste fermee de fichiers (pas
     * d'acces a d'autres ressources), lus dans le jar de la caisse.
     */
    private static final class WebPage implements HttpHandler {
        private static final String BASE = "/com/unicenta/pos/api/web/";
        private static final Map<String, String[]> FILES = new LinkedHashMap<>();

        static {
            FILES.put("/", new String[] {"index.html", "text/html; charset=utf-8"});
            FILES.put("/index.html", new String[] {"index.html", "text/html; charset=utf-8"});
            FILES.put("/app.js", new String[] {"app.js", "application/javascript; charset=utf-8"});
            FILES.put("/app.css", new String[] {"app.css", "text/css; charset=utf-8"});
            // identite OLPOS : logo, icones (navigateur, iPhone, Android), manifeste
            FILES.put("/logo.png", new String[] {"logo.png", "image/png"});
            FILES.put("/favicon-32.png", new String[] {"favicon-32.png", "image/png"});
            FILES.put("/favicon.ico", new String[] {"favicon-32.png", "image/png"});
            FILES.put("/icon-192.png", new String[] {"icon-192.png", "image/png"});
            FILES.put("/icon-512.png", new String[] {"icon-512.png", "image/png"});
            FILES.put("/apple-touch-icon.png", new String[] {"apple-touch-icon.png", "image/png"});
            FILES.put("/manifest.webmanifest", new String[] {"manifest.webmanifest", "application/manifest+json; charset=utf-8"});
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String[] f = FILES.get(ex.getRequestURI().getPath());
                boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
                if (f == null || !(head || "GET".equalsIgnoreCase(ex.getRequestMethod()))) {
                    byte[] nf = "Page introuvable".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    ex.sendResponseHeaders(404, nf.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(nf);
                    }
                    return;
                }
                byte[] bytes;
                try (InputStream in = ApiServer.class.getResourceAsStream(BASE + f[0])) {
                    if (in == null) {
                        throw new IOException("Ressource absente du jar : " + f[0]);
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    bytes = out.toByteArray();
                }
                ex.getResponseHeaders().set("Content-Type", f[1]);
                ex.getResponseHeaders().set("Cache-Control", "no-cache");
                ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                ex.getResponseHeaders().set("X-Frame-Options", "DENY");
                ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; "
                        + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; "
                        + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
                if (head) {
                    ex.sendResponseHeaders(200, -1);
                } else {
                    ex.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(bytes);
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Page web mobile : erreur", e);
                try {
                    ex.sendResponseHeaders(500, -1);
                } catch (IOException ignored) {
                    // en-tetes deja envoyes
                }
            } finally {
                ex.close();
            }
        }
    }

    private Object route(HttpExchange ex) throws ApiError, BasicException, IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        if (path.length() > PREFIX.length() + 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String route = path.substring(PREFIX.length());

        // ---------- adresses publiques
        switch (route) {
            case "/health":
                requireMethod(method, "GET");
                return Json.obj("status", "ok", "app", AppLocal.APP_NAME, "version", com.unicenta.pos.forms.Branding.VERSION,
                        "basedOn", com.unicenta.pos.forms.Branding.DERIVED_FROM,
                        "apiVersion", API_VERSION, "serverTime", System.currentTimeMillis());
            case "/users":
                requireMethod(method, "GET");
                if (!config.isListUsers()) {
                    throw new ApiError(404, "not_found", "Liste des serveurs desactivee (api.users.list=false).");
                }
                return Json.obj("users", data.listMobileUsers());
            case "/auth/login":
                requireMethod(method, "POST");
                return login(ex);
            default:
                break;
        }

        // ---------- adresses protegees
        ApiSessions.ApiSession session = authenticate(ex);
        switch (route) {
            case "/auth/logout":
                requireMethod(method, "POST");
                sessions.invalidate(session.getToken());
                return Json.obj("status", "ok");
            case "/me":
                requireMethod(method, "GET");
                return userJson(session);
            case "/catalog":
                requireMethod(method, "GET");
                return data.catalog();
            case "/catalog/categories":
                requireMethod(method, "GET");
                return Json.obj("categories", data.listCategories());
            case "/catalog/products": {
                requireMethod(method, "GET");
                String category = queryParam(ex, "category");
                if (category == null || category.isEmpty()) {
                    throw new ApiError(400, "missing_parameter", "Parametre 'category' obligatoire.");
                }
                return Json.obj("products", data.listProducts(category));
            }
            case "/floors":
                requireMethod(method, "GET");
                return Json.obj("floors", data.listFloors());
            default:
                break;
        }

        // ---------- commandes d'une table : /tables/{id}/order[/changes], /tables/{id}/kitchen/..., /tables/{id}/bill/print
        String[] parts = route.split("/");   // "", "tables", id, "order", ["changes"]
        if (parts.length == 5 && "tables".equals(parts[1]) && "bill".equals(parts[3]) && "print".equals(parts[4])) {
            if (!config.isRestaurantMode()) {
                throw new ApiError(409, "not_restaurant_mode",
                        "La caisse n'est pas en mode restaurant (machine.ticketsbag=restaurant).");
            }
            requireMethod(method, "POST");
            return bills.print(parts[2], readJsonObject(ex), session);
        }
        if (parts.length >= 5 && "tables".equals(parts[1]) && "kitchen".equals(parts[3])) {
            if (!config.isRestaurantMode()) {
                throw new ApiError(409, "not_restaurant_mode",
                        "La caisse n'est pas en mode restaurant (machine.ticketsbag=restaurant).");
            }
            requireMethod(method, "POST");
            if (parts.length == 5 && "send".equals(parts[4])) {
                return kitchen.send(parts[2], readJsonObject(ex), session);
            }
            if (parts.length == 6 && "resolve".equals(parts[5])) {
                return kitchen.resolve(parts[2], parts[4], readJsonObject(ex), session);
            }
        }
        if (parts.length >= 4 && "tables".equals(parts[1]) && "order".equals(parts[3]) && parts.length <= 5) {
            if (!config.isRestaurantMode()) {
                throw new ApiError(409, "not_restaurant_mode",
                        "La caisse n'est pas en mode restaurant (machine.ticketsbag=restaurant) : pas de commandes par table.");
            }
            String tableId = parts[2];   // getPath() est deja decode
            if (parts.length == 4) {
                requireMethod(method, "GET");
                return orders.getOrder(tableId);
            }
            if ("changes".equals(parts[4])) {
                requireMethod(method, "POST");
                return orders.applyChanges(tableId, readJsonObject(ex), session);
            }
        }
        throw new ApiError(404, "not_found", "Adresse inconnue : " + path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonObject(HttpExchange ex) throws ApiError, IOException {
        Object parsed;
        try {
            parsed = Json.parse(readBody(ex));
        } catch (Json.ParseException e) {
            throw new ApiError(400, "invalid_json", "Corps JSON invalide : " + e.getMessage());
        }
        if (!(parsed instanceof Map)) {
            throw new ApiError(400, "invalid_json", "Objet JSON attendu.");
        }
        return (Map<String, Object>) parsed;
    }

    // ================================================================= authentification

    private Object login(HttpExchange ex) throws ApiError, BasicException, IOException {
        Map<String, Object> body = readJsonObject(ex);
        Object userId = body.get("userId");
        Object password = body.get("password");
        if (!(userId instanceof String) || ((String) userId).isEmpty()) {
            throw new ApiError(400, "missing_parameter", "Champ 'userId' obligatoire.");
        }
        if (!(password instanceof String)) {
            throw new ApiError(400, "missing_parameter", "Champ 'password' obligatoire.");
        }

        String uid = (String) userId;
        long locked = sessions.lockRemainingSeconds(uid);
        if (locked > 0) {
            throw new ApiError(429, "too_many_attempts",
                    "Trop d'essais. Reessayez dans " + locked + " seconde(s).");
        }

        MobileDataService.LoginResult r = data.login(uid, (String) password);
        switch (r.status) {
            case OK: {
                ApiSessions.ApiSession s = sessions.create(r.user.getId(), r.user.getName(), r.user.getRole(), r.permissions,
                        r.user.getPassword());
                LOG.log(Level.INFO, "API mobile : connexion de {0} depuis {1}",
                        new Object[] {r.user.getName(), ex.getRemoteAddress().getAddress().getHostAddress()});
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("token", s.getToken());
                res.put("expiresInSeconds", sessions.getTtlMillis() / 1000);
                res.putAll(userJson(s));
                return res;
            }
            case NO_PASSWORD:
                throw new ApiError(403, "password_required",
                        "Ce compte n'a pas de mot de passe : definissez-en un a la caisse pour l'utiliser sur mobile.");
            case NO_ACCESS:
                throw new ApiError(403, "forbidden", "Ce compte n'a pas la permission " + ApiConfig.PERMISSION_ACCESS + ".");
            case UNKNOWN_USER:
            case BAD_PASSWORD:
            default:
                // Meme message dans les deux cas : on ne revele pas quels comptes existent.
                boolean nowLocked = sessions.recordFailure(uid);
                LOG.log(Level.INFO, "API mobile : echec de connexion depuis {0}",
                        ex.getRemoteAddress().getAddress().getHostAddress());
                throw new ApiError(nowLocked ? 429 : 401, nowLocked ? "too_many_attempts" : "invalid_credentials",
                        nowLocked ? "Trop d'essais. Reessayez dans " + config.getLockSeconds() + " seconde(s)."
                                  : "Identifiant ou mot de passe incorrect.");
        }
    }

    /** Delai maximal avant de relire les droits d'un serveur connecte (ms). */
    static final long RECHECK_MILLIS = 2000;

    private ApiSessions.ApiSession authenticate(HttpExchange ex) throws ApiError, BasicException {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        String token = null;
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = header.substring(7).trim();
        }
        ApiSessions.ApiSession s = sessions.validate(token);
        if (s == null) {
            throw new ApiError(401, "unauthorized", "Connexion requise ou session expiree.");
        }
        // OLPOS : les droits sont relus dans la base (compte supprime, masque, mot de
        // passe change ou role sans mobile.Access => session fermee immediatement).
        long now = sessions.now();
        if (now - s.getCheckedAt() >= RECHECK_MILLIS) {
            java.util.Set<String> current = data.currentPermissions(s.getUserId(), s.getPasswordStamp());
            if (current == null) {
                sessions.invalidateUser(s.getUserId());
                LOG.log(Level.INFO, "API mobile : session de {0} fermee (droits retires)", s.getUserName());
                throw new ApiError(401, "access_revoked",
                        "Votre acces depuis le telephone a ete retire ou votre mot de passe a change. Reconnectez-vous.");
            }
            s.refresh(current, now);
        }
        if (!s.hasPermission(ApiConfig.PERMISSION_ACCESS)) {
            throw new ApiError(403, "forbidden", "Permission " + ApiConfig.PERMISSION_ACCESS + " requise.");
        }
        return s;
    }

    private static Map<String, Object> userJson(ApiSessions.ApiSession s) {
        List<String> perms = new ArrayList<>(s.getPermissions());
        return Json.obj("user", Json.obj("id", s.getUserId(), "name", s.getUserName(), "roleId", s.getRoleId()),
                "permissions", perms);
    }

    // ================================================================= outils HTTP

    private static void requireMethod(String actual, String expected) throws ApiError {
        if (!expected.equalsIgnoreCase(actual)) {
            throw new ApiError(405, "method_not_allowed", "Methode " + actual + " non autorisee (attendu : " + expected + ").");
        }
    }

    private static String readBody(HttpExchange ex) throws IOException, ApiError {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try (InputStream in = ex.getRequestBody()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY_BYTES) {
                    throw new ApiError(413, "body_too_large", "Requete trop volumineuse.");
                }
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String queryParam(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            if (decode(k).equals(name)) {
                return eq < 0 ? "" : decode(part.substring(eq + 1));
            }
        }
        return null;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static Map<String, Object> error(String code, String message) {
        return Json.obj("error", Json.obj("code", code, "message", message));
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
