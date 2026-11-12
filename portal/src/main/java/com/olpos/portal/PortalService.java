//    OLPOS Portail Patron - logique centrale
//    Licence GPL v3 (voir ../LICENSE.md).
package com.olpos.portal;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Regles du portail :
 * <ul>
 *   <li><b>Association</b> : code temporaire (30 min) a usage unique, une caisse
 *       par restaurant ; la caisse recoit un jeton d'appareil (seule son
 *       empreinte est gardee).</li>
 *   <li><b>Connexion deleguee</b> : le navigateur chiffre identifiant, mot de
 *       passe et code de double authentification avec la cle publique de LA
 *       caisse ; le portail ne fait que transmettre ce bloc illisible. La caisse
 *       verifie (meme controle que la caisse elle-meme) et renvoie une preuve
 *       signee et temporaire. Caisse hors ligne : connexion impossible.</li>
 *   <li><b>Sessions</b> : 30 min d'inactivite, 12 h au plus, liees au
 *       restaurant et a l'etat du compte ; changement de mot de passe, compte
 *       desactive, droit retire => sessions fermees a la synchronisation suivante.</li>
 *   <li><b>Isolation</b> : toute lecture passe par le restaurant de la session.</li>
 *   <li><b>Lecture seule</b> : aucune adresse du portail ne modifie la caisse.</li>
 * </ul>
 */
public final class PortalService {

    public static final long PAIRING_TTL = 30 * 60_000L;
    public static final long SESSION_IDLE = 30 * 60_000L;
    public static final long SESSION_MAX = 12 * 3600_000L;
    /** Caisse consideree hors ligne apres ce delai sans contact. */
    public static final long DEVICE_OFFLINE_AFTER = 75_000L;
    public static final long AUTH_TIMEOUT = 20_000L;
    public static final int MAX_FAILURES = 5;
    public static final long LOCK_MS = 15 * 60_000L;
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{1,58}");

    /** Erreur renvoyee au client (code HTTP + code + message en francais). */
    public static final class PortalError extends Exception {
        private static final long serialVersionUID = 1L;
        public final int status;
        public final String code;

        public PortalError(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    /** Session d'un patron. */
    public static final class Session {
        public final String rid;
        public final String uid;
        public final String name;

        Session(String rid, String uid, String name) {
            this.rid = rid;
            this.uid = uid;
            this.name = name;
        }
    }

    private final PortalDb db;
    private final LongSupplier clock;
    private final Object authSignal = new Object();

    public PortalService(PortalDb db, LongSupplier clock) {
        this.db = db;
        this.clock = clock;
    }

    public PortalDb db() {
        return db;
    }

    long now() {
        return clock.getAsLong();
    }

    // ================================================================== restaurants et association

    /** Cree un restaurant (operateur du portail) ; renvoie {rid, slug, pairingCode}. */
    public Map<String, Object> createRestaurant(String name, String slug) throws SQLException, PortalError {
        if (name == null || name.trim().isEmpty() || slug == null || !SLUG.matcher(slug).matches()) {
            throw new PortalError(400, "invalid", "Nom obligatoire ; identifiant en minuscules, chiffres et tirets.");
        }
        if (db.one("SELECT rid FROM restaurants WHERE slug = ?", slug) != null) {
            throw new PortalError(409, "exists", "Identifiant de restaurant deja utilise.");
        }
        String rid = Crypto.id();
        db.update("INSERT INTO restaurants (rid, slug, name, created) VALUES (?, ?, ?, ?)", rid, slug, name.trim(), now());
        Map<String, Object> r = Json.obj("rid", rid, "slug", slug, "name", name.trim());
        r.put("pairingCode", newPairingCode(rid));
        return r;
    }

    /** Nouveau code d'association a usage unique (30 min). */
    public String newPairingCode(String rid) throws SQLException {
        String code = Crypto.pairingCode();
        db.update("INSERT INTO pairing_codes (code_hash, rid, expires) VALUES (?, ?, ?)",
                Crypto.sha256(code), rid, now() + PAIRING_TTL);
        return code;
    }

    public String ridForSlug(String slug) throws SQLException {
        Map<String, Object> r = db.one("SELECT rid FROM restaurants WHERE slug = ?", slug);
        return r == null ? null : (String) r.get("rid");
    }

    /** Association d'une caisse (code a usage unique). */
    public Map<String, Object> pair(Map<String, Object> body) throws SQLException, PortalError {
        String code = str(body, "code").toUpperCase().trim();
        String signKey = str(body, "signKey");
        String encKey = str(body, "encKey");
        try {
            Crypto.publicKey("Ed25519", signKey);
            Crypto.publicKey("RSA", encKey);
        } catch (Exception e) {
            throw new PortalError(400, "invalid_keys", "Cles de la caisse invalides.");
        }
        String token = Crypto.token();
        try {
            return pairTx(code, signKey, encKey, token, body);
        } catch (SQLException e) {
            if (e.getCause() instanceof PortalError) {
                throw (PortalError) e.getCause();
            }
            throw e;
        }
    }

    private Map<String, Object> pairTx(String code, String signKey, String encKey, String token,
            Map<String, Object> body) throws SQLException {
        return db.tx(() -> {
            Map<String, Object> pc = db.one("SELECT rid, expires, used FROM pairing_codes WHERE code_hash = ?",
                    Crypto.sha256(code));
            if (pc == null || pc.get("used") != null || ((Number) pc.get("expires")).longValue() < now()) {
                throw new SQLException(new PortalError(403, "invalid_code",
                        "Code d'association invalide, expire ou deja utilise."));
            }
            String rid = (String) pc.get("rid");
            db.update("UPDATE pairing_codes SET used = ? WHERE code_hash = ?", now(), Crypto.sha256(code));
            db.update("DELETE FROM devices WHERE rid = ?", rid);
            db.update("DELETE FROM sessions WHERE rid = ?", rid);   // nouvelle caisse : sessions precedentes fermees
            db.update("INSERT INTO devices (rid, token_hash, sign_key, enc_key, name, paired, last_seen, last_sync)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, 0)", rid, Crypto.sha256(token), signKey, encKey,
                    body.get("deviceName") instanceof String ? body.get("deviceName") : "caisse", now(), now());
            Map<String, Object> r = db.one("SELECT slug, name FROM restaurants WHERE rid = ?", rid);
            return Json.obj("restaurantId", rid, "slug", r.get("slug"), "name", r.get("name"), "deviceToken", token);
        });
    }

    /** Caisse authentifiee par son jeton ; renvoie son restaurant. */
    public String device(String bearer) throws SQLException, PortalError {
        if (bearer == null || bearer.isEmpty()) {
            throw new PortalError(401, "unauthorized", "Jeton de caisse manquant.");
        }
        Map<String, Object> d = db.one("SELECT rid FROM devices WHERE token_hash = ?", Crypto.sha256(bearer));
        if (d == null) {
            throw new PortalError(401, "unauthorized", "Caisse inconnue ou dissociee.");
        }
        String rid = (String) d.get("rid");
        db.update("UPDATE devices SET last_seen = ? WHERE rid = ?", now(), rid);
        return rid;
    }

    // ================================================================== synchronisation

    /**
     * Lot envoye par la caisse. Un meme {@code batchId} n'est jamais applique
     * deux fois. Chaque lot porte une version {@code seq} croissante : un
     * ticket, une cloture ou une suppression n'est appliquee que si elle est
     * PLUS RECENTE que ce que le portail connait deja. Un lot destine a un autre
     * restaurant est refuse.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> sync(String rid, Map<String, Object> body) throws SQLException, PortalError {
        String batchId = str(body, "batchId");
        if (body.get("rid") != null && !rid.equals(body.get("rid"))) {
            throw new PortalError(409, "wrong_restaurant", "Lot destine a un autre restaurant : refuse.");
        }
        long seq = body.get("seq") == null ? 0 : num(body.get("seq")).longValue();
        Map<String, Object> meta = body.get("meta") instanceof Map ? (Map<String, Object>) body.get("meta") : null;
        boolean[] latest = {true};
        boolean duplicate = db.tx(() -> {
            if (db.one("SELECT batch_id FROM batches WHERE rid = ? AND batch_id = ?", rid, batchId) != null) {
                return true;
            }
            db.update("INSERT INTO batches (rid, batch_id, received) VALUES (?, ?, ?)", rid, batchId, now());
            Map<String, Object> dev = db.one("SELECT last_seq FROM devices WHERE rid = ?", rid);
            latest[0] = dev == null || seq >= ((Number) dev.get("last_seq")).longValue();
            for (Object o : list(body.get("tickets"))) {
                Map<String, Object> t = (Map<String, Object>) o;
                String id = (String) t.get("id");
                if (!newer("tickets", "id", rid, id, seq)) {
                    continue;   // le portail a deja une version plus recente
                }
                db.update("DELETE FROM tickets WHERE rid = ? AND id = ?", rid, id);
                db.update("INSERT INTO tickets (rid, id, ticket_no, ticket_type, ts, person, person_name, money, place,"
                        + " order_number, base, tax, total, discounts, json, rev) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        rid, id, num(t.get("ticketNo")).intValue(), num(t.get("type")).intValue(),
                        num(t.get("ts")).longValue(), t.get("person"), t.get("personName"), t.get("money"),
                        t.get("place"), t.get("orderNumber") == null ? null : num(t.get("orderNumber")).intValue(),
                        num(t.get("base")).doubleValue(), num(t.get("tax")).doubleValue(),
                        num(t.get("total")).doubleValue(), num(t.get("discounts")).doubleValue(), Json.write(t), seq);
            }
            for (Object o : list(body.get("deletedTickets"))) {
                // supprime a la caisse : retire seulement si la suppression est plus recente
                db.update("DELETE FROM tickets WHERE rid = ? AND id = ? AND rev < ?", rid, String.valueOf(o), seq);
            }
            for (Object o : list(body.get("closures"))) {
                Map<String, Object> cl = (Map<String, Object>) o;
                String money = String.valueOf(cl.get("money"));
                if (!newer("closures", "money", rid, money, seq)) {
                    continue;
                }
                db.update("DELETE FROM closures WHERE rid = ? AND money = ?", rid, money);
                db.update("INSERT INTO closures (rid, money, host, seq, date_start, date_end, json, rev)"
                        + " VALUES (?,?,?,?,?,?,?,?)", rid, money, cl.get("host"), num(cl.get("seq")).intValue(),
                        num(cl.get("start")).longValue(), cl.get("end") == null ? null : num(cl.get("end")).longValue(),
                        Json.write(cl), seq);
            }
            if (body.get("openOrders") instanceof List && latest[0]) {
                db.update("DELETE FROM open_orders WHERE rid = ?", rid);
                db.update("INSERT INTO open_orders (rid, ts, json, rev) VALUES (?, ?, ?, ?)", rid, now(),
                        Json.write(body.get("openOrders")), seq);
            }
            if (latest[0]) {
                db.update("UPDATE devices SET last_seq = ? WHERE rid = ?", seq, rid);
            }
            return false;
        });
        if (meta != null) {
            db.update("UPDATE devices SET currency = ?, locale = ?, timezone = ?, version = ? WHERE rid = ?",
                    meta.get("currency"), meta.get("locale"), meta.get("timezone"), meta.get("version"), rid);
        }
        if (body.get("users") instanceof List && latest[0] && !duplicate) {
            updateUsers(rid, (List<Object>) body.get("users"));   // jamais l'etat d'un ancien lot
        }
        db.update("UPDATE devices SET last_sync = ? WHERE rid = ?", now(), rid);
        return Json.obj("status", duplicate ? "duplicate" : "ok");
    }

    /** Vrai si la version {@code seq} est plus recente que celle enregistree (ou si rien n'est enregistre). */
    private boolean newer(String table, String key, String rid, String id, long seq) throws SQLException {
        Map<String, Object> r = db.one("SELECT rev FROM " + table + " WHERE rid = ? AND " + key + " = ?", rid, id);
        return r == null || seq > ((Number) r.get("rev")).longValue();
    }

    /**
     * Etat des comptes envoye par la caisse (empreintes opaques : aucun mot de
     * passe ni empreinte de mot de passe). Toute difference ferme les sessions
     * du compte concerne.
     */
    @SuppressWarnings("unchecked")
    public void updateUsers(String rid, List<Object> users) throws SQLException {
        db.tx(() -> {
            db.update("DELETE FROM users_state WHERE rid = ?", rid);
            for (Object o : users) {
                Map<String, Object> u = (Map<String, Object>) o;
                db.update("INSERT INTO users_state (rid, uid, name, stamp, eligible) VALUES (?, ?, ?, ?, ?)",
                        rid, u.get("uid"), u.get("name"), u.get("stamp"), Boolean.TRUE.equals(u.get("eligible")) ? 1 : 0);
            }
            // sessions dont le compte a change, disparu ou perdu l'acces : fermees
            db.update("DELETE FROM sessions WHERE rid = ? AND NOT EXISTS (SELECT 1 FROM users_state u"
                    + " WHERE u.rid = sessions.rid AND u.uid = sessions.uid AND u.stamp = sessions.stamp AND u.eligible = 1)", rid);
            return null;
        });
    }

    // ================================================================== connexion deleguee (cote caisse)

    /** Demandes de connexion en attente pour cette caisse (attente longue). */
    public List<Map<String, Object>> poll(String rid, long waitMs) throws SQLException, InterruptedException {
        long until = now() + waitMs;
        while (true) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : db.query("SELECT id, nonce, ciphertext FROM auth_requests WHERE rid = ?"
                    + " AND status = 'pending' AND delivered = 0 AND created > ?", rid, now() - AUTH_TIMEOUT)) {
                out.add(Json.obj("id", r.get("id"), "nonce", r.get("nonce"), "ciphertext", r.get("ciphertext")));
                db.update("UPDATE auth_requests SET delivered = 1 WHERE id = ?", r.get("id"));
            }
            if (!out.isEmpty() || now() >= until) {
                db.update("UPDATE devices SET last_seen = ? WHERE rid = ?", now(), rid);
                return out;
            }
            synchronized (authSignal) {
                authSignal.wait(Math.min(1000, Math.max(1, until - now())));
            }
        }
    }

    /** Reponse de la caisse : preuve signee, ou refus motive. */
    public void authResponse(String rid, String requestId, Map<String, Object> body) throws SQLException, PortalError {
        Map<String, Object> req = db.one("SELECT rid, status FROM auth_requests WHERE id = ?", requestId);
        if (req == null || !rid.equals(req.get("rid")) || !"pending".equals(req.get("status"))) {
            throw new PortalError(404, "unknown_request", "Demande inconnue.");
        }
        db.update("UPDATE auth_requests SET status = 'answered', result = ? WHERE id = ?", Json.write(body), requestId);
        synchronized (authSignal) {
            authSignal.notifyAll();
        }
    }

    // ================================================================== connexion du patron

    /** Etape 1 : cle publique de la caisse et defi a usage unique. */
    public Map<String, Object> challenge(String slug) throws SQLException, PortalError {
        String rid = ridForSlug(slug == null ? "" : slug.trim().toLowerCase());
        Map<String, Object> d = rid == null ? null
                : db.one("SELECT enc_key, last_seen FROM devices WHERE rid = ?", rid);
        if (d == null) {
            throw new PortalError(404, "unknown_restaurant", "Restaurant inconnu ou caisse non associee.");
        }
        if (now() - ((Number) d.get("last_seen")).longValue() > DEVICE_OFFLINE_AFTER) {
            throw new PortalError(503, "till_offline", "La caisse du restaurant est hors ligne : une nouvelle "
                    + "connexion est impossible tant qu'elle n'est pas reconnectee a Internet (elle seule verifie "
                    + "votre mot de passe). Les sessions deja ouvertes restent valables.");
        }
        String id = Crypto.id();
        String nonce = Crypto.token();
        db.update("INSERT INTO auth_requests (id, rid, nonce, created, status) VALUES (?, ?, ?, ?, 'new')",
                id, rid, nonce, now());
        return Json.obj("challengeId", id, "nonce", nonce, "encKey", d.get("enc_key"));
    }

    /**
     * Etape 2 : bloc chiffre pour la caisse ; attend sa reponse et ouvre une
     * session si la preuve signee est valide.
     * @return {token, user, restaurant}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> login(String slug, String userName, String challengeId, String ciphertext, String ip)
            throws SQLException, PortalError, InterruptedException {
        String rid = ridForSlug(slug == null ? "" : slug.trim().toLowerCase());
        if (rid == null || userName == null || userName.trim().isEmpty() || ciphertext == null
                || ciphertext.length() > 1500) {
            throw new PortalError(400, "invalid", "Restaurant, identifiant et mot de passe obligatoires.");
        }
        String userKey = "u|" + rid + "|" + userName.trim().toLowerCase();
        String ipKey = "ip|" + ip;
        checkLock(userKey);
        checkLock(ipKey);
        Map<String, Object> req = db.one("SELECT rid, nonce, status, created FROM auth_requests WHERE id = ?", challengeId);
        if (req == null || !rid.equals(req.get("rid")) || !"new".equals(req.get("status"))
                || now() - ((Number) req.get("created")).longValue() > 5 * 60_000L) {
            throw new PortalError(400, "invalid_challenge", "Defi expire : rechargez la page.");
        }
        String nonce = (String) req.get("nonce");
        db.update("UPDATE auth_requests SET status = 'pending', ciphertext = ?, created = ? WHERE id = ?",
                ciphertext, now(), challengeId);
        synchronized (authSignal) {
            authSignal.notifyAll();
        }
        long until = now() + AUTH_TIMEOUT;
        Map<String, Object> answered = null;
        while (now() < until) {
            Map<String, Object> r = db.one("SELECT status, result FROM auth_requests WHERE id = ?", challengeId);
            if (r != null && "answered".equals(r.get("status"))) {
                answered = r;
                break;
            }
            synchronized (authSignal) {
                authSignal.wait(250);
            }
        }
        db.update("UPDATE auth_requests SET status = 'done', ciphertext = NULL WHERE id = ?", challengeId);
        if (answered == null) {
            throw new PortalError(503, "till_offline", "La caisse n'a pas repondu : elle est probablement hors ligne. "
                    + "Nouvelle connexion impossible pour le moment.");
        }
        Map<String, Object> result;
        try {
            result = (Map<String, Object>) Json.parse((String) answered.get("result"));
        } catch (Json.ParseException e) {
            throw new PortalError(502, "bad_answer", "Reponse de la caisse illisible.");
        }
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            recordFailure(userKey);
            recordFailure(ipKey);
            String reason = String.valueOf(result.get("reason"));
            throw new PortalError(401, "denied", "no_password".equals(reason)
                    ? "Ce compte n'a pas de mot de passe : l'acces a distance est refuse."
                    : "not_enrolled".equals(reason)
                    ? "Ce compte n'est pas autorise sur le portail (activation et double authentification a faire a la caisse)."
                    : "locked".equals(reason) ? "Trop d'essais : compte bloque temporairement a la caisse."
                    : "Identifiant, mot de passe ou code de double authentification incorrect.");
        }
        // preuve signee par la caisse
        String assertion = (String) result.get("assertion");
        String signature = (String) result.get("signature");
        Map<String, Object> dev = db.one("SELECT sign_key FROM devices WHERE rid = ?", rid);
        if (assertion == null || dev == null || !Crypto.verifyEd25519((String) dev.get("sign_key"), assertion, signature)) {
            throw new PortalError(502, "bad_signature", "Preuve de la caisse invalide.");
        }
        Map<String, Object> a;
        try {
            a = (Map<String, Object>) Json.parse(assertion);
        } catch (Json.ParseException e) {
            throw new PortalError(502, "bad_answer", "Preuve de la caisse illisible.");
        }
        long exp = num(a.get("exp")).longValue();
        if (!rid.equals(a.get("rid")) || !challengeId.equals(a.get("req")) || !Crypto.same(nonce, (String) a.get("nonce"))
                || exp < now() || exp > now() + 5 * 60_000L) {
            throw new PortalError(502, "bad_assertion", "Preuve de la caisse non valable pour cette connexion.");
        }
        String uid = (String) a.get("uid");
        String stamp = (String) a.get("stamp");
        clearFailures(userKey);
        String token = Crypto.token();
        db.update("INSERT INTO users_state (rid, uid, name, stamp, eligible) SELECT ?, ?, ?, ?, 1 FROM restaurants"
                + " WHERE rid = ? AND NOT EXISTS (SELECT 1 FROM users_state WHERE rid = ? AND uid = ?)",
                rid, uid, a.get("name"), stamp, rid, rid, uid);
        db.update("INSERT INTO sessions (token_hash, rid, uid, name, stamp, created, last_used, expires)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Crypto.sha256(token), rid, uid, a.get("name"), stamp, now(), now(),
                now() + SESSION_MAX);
        return Json.obj("token", token, "user", a.get("name"), "restaurant", restaurantInfo(rid));
    }

    /** Session valide (expiration glissante 30 min, 12 h au plus) ou null. */
    public Session session(String token) throws SQLException {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String h = Crypto.sha256(token);
        Map<String, Object> s = db.one("SELECT rid, uid, name, last_used, expires, stamp FROM sessions WHERE token_hash = ?", h);
        if (s == null) {
            return null;
        }
        long last = ((Number) s.get("last_used")).longValue();
        if (now() > ((Number) s.get("expires")).longValue() || now() - last > SESSION_IDLE) {
            db.update("DELETE FROM sessions WHERE token_hash = ?", h);
            return null;
        }
        Map<String, Object> u = db.one("SELECT stamp, eligible FROM users_state WHERE rid = ? AND uid = ?",
                s.get("rid"), s.get("uid"));
        if (u == null || !s.get("stamp").equals(u.get("stamp")) || ((Number) u.get("eligible")).intValue() != 1) {
            db.update("DELETE FROM sessions WHERE token_hash = ?", h);
            return null;
        }
        db.update("UPDATE sessions SET last_used = ? WHERE token_hash = ?", now(), h);
        return new Session((String) s.get("rid"), (String) s.get("uid"), (String) s.get("name"));
    }

    public void logout(String token) throws SQLException {
        if (token != null) {
            db.update("DELETE FROM sessions WHERE token_hash = ?", Crypto.sha256(token));
        }
    }

    public Map<String, Object> restaurantInfo(String rid) throws SQLException {
        Map<String, Object> r = db.one("SELECT r.name, r.slug, d.currency, d.locale, d.timezone, d.version, d.last_seen,"
                + " d.last_sync FROM restaurants r LEFT JOIN devices d ON d.rid = r.rid WHERE r.rid = ?", rid);
        if (r == null) {
            return null;
        }
        long seen = r.get("last_seen") == null ? 0 : ((Number) r.get("last_seen")).longValue();
        r.put("online", seen > 0 && now() - seen <= DEVICE_OFFLINE_AFTER);
        return r;
    }

    // ================================================================== limitation des essais

    private void checkLock(String key) throws SQLException, PortalError {
        Map<String, Object> f = db.one("SELECT locked_until FROM login_failures WHERE k = ?", key);
        if (f != null && ((Number) f.get("locked_until")).longValue() > now()) {
            long s = (((Number) f.get("locked_until")).longValue() - now()) / 1000 + 1;
            throw new PortalError(429, "too_many_attempts", "Trop d'essais. Reessayez dans " + (s / 60 + 1) + " minute(s).");
        }
    }

    private void recordFailure(String key) throws SQLException {
        Map<String, Object> f = db.one("SELECT n, first_at FROM login_failures WHERE k = ?", key);
        int max = key.startsWith("ip|") ? MAX_FAILURES * 4 : MAX_FAILURES;
        if (f == null || now() - ((Number) f.get("first_at")).longValue() > LOCK_MS) {
            db.update("DELETE FROM login_failures WHERE k = ?", key);
            db.update("INSERT INTO login_failures (k, n, first_at, locked_until) VALUES (?, 1, ?, 0)", key, now());
            return;
        }
        int n = ((Number) f.get("n")).intValue() + 1;
        db.update("UPDATE login_failures SET n = ?, locked_until = ? WHERE k = ?", n, n >= max ? now() + LOCK_MS : 0, key);
    }

    private void clearFailures(String key) throws SQLException {
        db.update("DELETE FROM login_failures WHERE k = ?", key);
    }

    // ================================================================== outils

    static String str(Map<String, Object> m, String k) throws PortalError {
        Object v = m.get(k);
        if (!(v instanceof String) || ((String) v).isEmpty() || ((String) v).length() > 2000) {
            throw new PortalError(400, "missing_" + k, "Champ '" + k + "' obligatoire.");
        }
        return (String) v;
    }

    static Number num(Object o) {
        return o instanceof Number ? (Number) o : (o == null ? 0 : Double.parseDouble(String.valueOf(o)));
    }

    static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
