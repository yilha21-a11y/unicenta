//    OLPOS (derive d'uniCenta oPOS) - double authentification du portail Patron
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.portal;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Codes a usage temporaire (TOTP, RFC 6238 : HMAC-SHA1, 6 chiffres, 30 s),
 * compatibles Google Authenticator, Microsoft Authenticator, Aegis, etc.
 * Le secret reste dans la caisse : le portail central ne le connait pas.
 */
public final class Totp {

    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    public static String newSecret() {
        byte[] b = new byte[20];
        new SecureRandom().nextBytes(b);
        return base32(b);
    }

    /** Code attendu a l'instant {@code millis}. */
    public static String code(String secret, long millis) {
        return code(base32Decode(secret), millis / 30_000L);
    }

    /** Verifie un code (decalage tolere : une periode avant ou apres). */
    public static boolean verify(String secret, String code, long millis) {
        return matchStep(secret, code, millis) >= 0;
    }

    /** Periode correspondant au code (pour refuser sa reutilisation), ou -1. */
    public static long matchStep(String secret, String code, long millis) {
        if (code == null || !code.trim().matches("\\d{6}")) {
            return -1;
        }
        byte[] key = base32Decode(secret);
        long step = millis / 30_000L;
        for (long s = step - 1; s <= step + 1; s++) {
            if (java.security.MessageDigest.isEqual(code(key, s).getBytes(), code.trim().getBytes())) {
                return s;
            }
        }
        return -1;
    }

    /** Adresse a saisir ou scanner dans l'application d'authentification. */
    public static String uri(String secret, String account, String issuer) {
        return "otpauth://totp/" + enc(issuer) + ":" + enc(account) + "?secret=" + secret + "&issuer=" + enc(issuer)
                + "&digits=6&period=30";
    }

    private static String code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(new byte[] {(byte) (step >>> 56), (byte) (step >>> 48), (byte) (step >>> 40),
                (byte) (step >>> 32), (byte) (step >>> 24), (byte) (step >>> 16), (byte) (step >>> 8), (byte) step});
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base32(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte x : b) {
            buffer = (buffer << 8) | (x & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(B32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(B32.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        String in = s.replace(" ", "").replace("=", "").toUpperCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : in.toCharArray()) {
            int v = B32.indexOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("Secret invalide");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
