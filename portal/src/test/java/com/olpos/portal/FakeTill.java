package com.olpos.portal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Caisse SIMULEE pour les tests du portail : meme protocole que
 * com.unicenta.pos.portal (cles Ed25519 + RSA-OAEP, attente longue, preuve
 * signee). Les comptes et leurs mots de passe ne quittent jamais cet objet.
 * Le code de double authentification attendu est fixe ("123456").
 */
@SuppressWarnings("unchecked")
final class FakeTill {

    static final String CODE = "123456";

    final String base;
    final HttpClient http = HttpClient.newHttpClient();
    final KeyPair sign;
    final KeyPair enc;
    final LongSupplier clock;
    String rid;
    String token;
    String slug;
    /** uid -> {nom, mot de passe ("" = aucun), autorise (true/false), empreinte} */
    final Map<String, String[]> users = new LinkedHashMap<>();
    volatile boolean running;
    volatile KeyPair forgeWith;   // pour simuler une preuve signee avec une autre cle
    private Thread thread;

    FakeTill(String base, LongSupplier clock) throws Exception {
        this.base = base;
        this.clock = clock;
        sign = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        enc = g.generateKeyPair();
    }

    void user(String uid, String name, String password, boolean allowed) {
        users.put(uid, new String[] {name, password, Boolean.toString(allowed), UUID.randomUUID().toString()});
    }

    HttpResponse<String> post(String path, Object body, boolean auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (auth) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    int pair(String code) throws Exception {
        HttpResponse<String> r = post("/api/device/pair", Json.obj("code", code, "deviceName", "Caisse test",
                "signKey", b64(sign.getPublic().getEncoded()), "encKey", b64(enc.getPublic().getEncoded()),
                "meta", Json.obj()), false);
        if (r.statusCode() == 200) {
            Map<String, Object> m = (Map<String, Object>) Json.parse(r.body());
            rid = (String) m.get("restaurantId");
            token = (String) m.get("deviceToken");
            slug = (String) m.get("slug");
        }
        return r.statusCode();
    }

    List<Object> usersState() {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : users.entrySet()) {
            String[] u = e.getValue();
            out.add(Json.obj("uid", e.getKey(), "name", u[0], "stamp", u[3],
                    "eligible", !u[1].isEmpty() && Boolean.parseBoolean(u[2])));
        }
        return out;
    }

    HttpResponse<String> sync(Map<String, Object> batch) throws Exception {
        batch.put("users", usersState());
        return post("/api/device/sync", batch, true);
    }

    /** Un tour d'attente longue : traite les demandes recues. */
    void pollOnce(int waitSeconds) throws Exception {
        HttpResponse<String> r = post("/api/device/poll?wait=" + waitSeconds, Json.obj("users", usersState()), true);
        if (r.statusCode() != 200) {
            throw new IllegalStateException("poll " + r.statusCode() + " " + r.body());
        }
        Map<String, Object> m = (Map<String, Object>) Json.parse(r.body());
        for (Object o : (List<Object>) m.get("authRequests")) {
            Map<String, Object> q = (Map<String, Object>) o;
            String id = (String) q.get("id");
            post("/api/device/auth/" + id, answer(id, (String) q.get("nonce"), (String) q.get("ciphertext")), true);
        }
    }

    void start() {
        running = true;
        thread = new Thread(() -> {
            while (running) {
                try {
                    pollOnce(1);
                } catch (Exception e) {
                    if (!running) {
                        return;
                    }
                }
            }
        }, "caisse-simulee");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() throws InterruptedException {
        running = false;
        if (thread != null) {
            thread.join(5000);
        }
    }

    private Map<String, Object> answer(String id, String nonce, String ciphertext) throws Exception {
        Map<String, Object> c;
        try {
            Cipher ci = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            ci.init(Cipher.DECRYPT_MODE, enc.getPrivate(), new OAEPParameterSpec("SHA-256", "MGF1",
                    MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            c = (Map<String, Object>) Json.parse(new String(ci.doFinal(Base64.getDecoder().decode(ciphertext)),
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Json.obj("ok", false, "reason", "invalid");
        }
        if (!id.equals(c.get("cid")) || !nonce.equals(c.get("nonce"))) {
            return Json.obj("ok", false, "reason", "invalid");
        }
        for (Map.Entry<String, String[]> e : users.entrySet()) {
            String[] u = e.getValue();
            if (!u[0].equalsIgnoreCase(String.valueOf(c.get("user")))) {
                continue;
            }
            if (u[1].isEmpty()) {
                return Json.obj("ok", false, "reason", "no_password");
            }
            if (!u[1].equals(c.get("password")) || !CODE.equals(c.get("totp"))) {
                return Json.obj("ok", false, "reason", "invalid");
            }
            if (!Boolean.parseBoolean(u[2])) {
                return Json.obj("ok", false, "reason", "not_enrolled");
            }
            long now = clock.getAsLong();
            String a = Json.write(Json.obj("v", 1L, "rid", rid, "req", id, "nonce", nonce, "uid", e.getKey(),
                    "name", u[0], "stamp", u[3], "iat", now, "exp", now + 60_000L));
            Signature s = Signature.getInstance("Ed25519");
            s.initSign((forgeWith != null ? forgeWith : sign).getPrivate());
            s.update(a.getBytes(StandardCharsets.UTF_8));
            return Json.obj("ok", true, "assertion", a, "signature", b64(s.sign()));
        }
        return Json.obj("ok", false, "reason", "invalid");
    }

    /** Ce que fait le navigateur (WebCrypto RSA-OAEP SHA-256). */
    static String browserEncrypt(String encKeyB64, Map<String, Object> creds) throws Exception {
        Cipher ci = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        ci.init(Cipher.ENCRYPT_MODE, Crypto.publicKey("RSA", encKeyB64), new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return b64(ci.doFinal(Json.write(creds).getBytes(StandardCharsets.UTF_8)));
    }

    static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    /** Ticket fictif : une ligne de produit a 10 % de taxe, paye en especes. */
    static Map<String, Object> ticket(String id, long ts, int no, int type, String person, String product,
            String category, double units, double price, String payment) {
        double base = units * price;
        double tax = Math.round(base * 0.10 * 100) / 100.0;
        List<Object> lines = new ArrayList<>();
        lines.add(Json.obj("line", 0L, "productId", product, "name", product, "category", category, "units", units,
                "price", price, "total", base, "taxRate", 0.10, "discount", false));
        List<Object> pays = new ArrayList<>();
        pays.add(Json.obj("type", payment, "total", base + tax, "tip", 0.0));
        List<Object> taxes = new ArrayList<>();
        taxes.add(Json.obj("name", "TVA 10", "base", base, "amount", tax));
        Map<String, Object> t = Json.obj("id", id, "ts", ts, "money", "caisse-1", "type", (long) type,
                "ticketNo", (long) no, "person", person, "personName", person, "orderNumber", null, "place", "Table 1");
        t.put("lines", lines);
        t.put("taxes", taxes);
        t.put("payments", pays);
        t.put("base", base);
        t.put("tax", tax);
        t.put("total", base + tax);
        t.put("discounts", 0.0);
        return t;
    }
}
