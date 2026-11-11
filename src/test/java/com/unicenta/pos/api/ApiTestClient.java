package com.unicenta.pos.api;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Petit client HTTP/JSON pour les tests de l'API. */
@SuppressWarnings("unchecked")
final class ApiTestClient {

    static final class Response {
        int status;
        String body;

        Map<String, Object> json() {
            try {
                return (Map<String, Object>) Json.parse(body);
            } catch (Json.ParseException e) {
                throw new AssertionError("Reponse non JSON : " + body);
            }
        }

        String errorCode() {
            Map<String, Object> e = (Map<String, Object>) json().get("error");
            return e == null ? null : (String) e.get("code");
        }

        String errorMessage() {
            Map<String, Object> e = (Map<String, Object>) json().get("error");
            return e == null ? null : (String) e.get("message");
        }

        Map<String, Object> order() {
            return (Map<String, Object>) json().get("order");
        }
    }

    private final String base;

    ApiTestClient(int port) {
        this.base = "http://127.0.0.1:" + port + "/api/v1";
    }

    Response call(String method, String path, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(30000);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        Response r = new Response();
        r.status = c.getResponseCode();
        InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
        }
        r.body = new String(out.toByteArray(), StandardCharsets.UTF_8);
        return r;
    }

    String login(String userId, String password) throws Exception {
        Response r = call("POST", "/auth/login", null, Json.write(Json.obj("userId", userId, "password", password)));
        if (r.status != 200) {
            throw new AssertionError("Connexion impossible : " + r.body);
        }
        return (String) r.json().get("token");
    }

    Response getOrder(String token, String table) throws Exception {
        return call("GET", "/tables/" + table + "/order", token, null);
    }

    /** Identifiant de la commande actuellement sur la table (comme le telephone l'a lu), ou null. */
    @SuppressWarnings("unchecked")
    String currentOrderId(String token, String table) throws Exception {
        Response r = getOrder(token, table);
        Object o = r.status == 200 ? ((Map<String, Object>) r.json().get("order")).get("orderId") : null;
        return o instanceof String ? (String) o : null;
    }

    Response changes(String token, String table, String requestId, long baseVersion, Object... operations) throws Exception {
        Map<String, Object> body = Json.obj("requestId", requestId, "baseVersion", baseVersion, "operations", operations);
        if (baseVersion > 0) {
            body.put("baseOrderId", currentOrderId(token, table));   // OLPOS : commande visee
        }
        return call("POST", "/tables/" + table + "/order/changes", token, Json.write(body));
    }

    /** Modification visant explicitement la commande {@code orderId}. */
    Response changesOn(String token, String table, String orderId, String requestId, long baseVersion,
            Object... operations) throws Exception {
        return call("POST", "/tables/" + table + "/order/changes", token, Json.write(Json.obj("requestId", requestId,
                "baseOrderId", orderId, "baseVersion", baseVersion, "operations", operations)));
    }

    static Map<String, Object> add(String productId, int quantity, String note) {
        Map<String, Object> m = Json.obj("op", "add", "productId", productId, "quantity", quantity);
        if (note != null) {
            m.put("note", note);
        }
        return m;
    }

    static Map<String, Object> setQuantity(String lineId, int quantity) {
        return Json.obj("op", "setQuantity", "lineId", lineId, "quantity", quantity);
    }

    static Map<String, Object> setNote(String lineId, String note) {
        return Json.obj("op", "setNote", "lineId", lineId, "note", note);
    }

    static Map<String, Object> remove(String lineId) {
        return Json.obj("op", "remove", "lineId", lineId);
    }

    static List<Object> lines(Map<String, Object> order) {
        return (List<Object>) order.get("lines");
    }

    static Map<String, Object> line(Map<String, Object> order, int i) {
        return (Map<String, Object>) lines(order).get(i);
    }

    static long version(Map<String, Object> order) {
        return ((Number) order.get("version")).longValue();
    }

    static double num(Object o) {
        return ((Number) o).doubleValue();
    }
}
