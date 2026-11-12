//    OLPOS Portail Patron - outils cryptographiques
//    Licence GPL v3 (voir ../LICENSE.md).
package com.olpos.portal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Hachage, aleas, verification des preuves signees par les caisses. */
public final class Crypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Code d'association : 10 caracteres sans ambiguite (pas de 0/O, 1/I/L). */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private Crypto() {
    }

    public static String token() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String id() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String pairingCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                sb.append('-');
            }
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Comparaison en temps constant. */
    public static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    public static PublicKey publicKey(String algorithm, String b64) throws GeneralSecurityException {
        return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    /** Signature Ed25519 (base64) d'un texte par la cle publique de la caisse. */
    public static boolean verifyEd25519(String publicKeyB64, String text, String signatureB64) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(publicKey("Ed25519", publicKeyB64));
            s.update(text.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(signatureB64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
