//    OLPOS - licences (code commun a OLPOS et a OLPOS License Manager)
//    Licence GPL v3 (voir LICENSE.md).
package com.olpos.license;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * Code d'activation / fichier de licence :
 * <pre>OLPOS1.&lt;contenu en base64url&gt;.&lt;signature Ed25519 en base64url&gt;</pre>
 * Le meme texte sert de code a copier-coller et de contenu du fichier
 * {@code .olposlic}. Espaces et retours a la ligne sont ignores a la lecture
 * (code recopie depuis un e-mail).
 *
 * <p>Signature : Ed25519 (standard, fourni par Java 15+). Seule la cle
 * PUBLIQUE est embarquee dans OLPOS ; la cle privee reste dans OLPOS License
 * Manager.</p>
 */
public final class LicenseCodec {

    public static final String PREFIX = "OLPOS1.";
    public static final String ALGORITHM = "Ed25519";
    public static final String FILE_EXTENSION = ".olposlic";

    private LicenseCodec() {
    }

    /** Signe la licence avec la cle privee du gestionnaire. */
    public static String sign(License license, PrivateKey key) throws GeneralSecurityException {
        byte[] payload = license.canonicalBytes();
        Signature s = Signature.getInstance(ALGORITHM);
        s.initSign(key);
        s.update(payload);
        byte[] sig = s.sign();
        Base64.Encoder b = Base64.getUrlEncoder().withoutPadding();
        return PREFIX + b.encodeToString(payload) + "." + b.encodeToString(sig);
    }

    /**
     * Lit et VERIFIE un code de licence avec les cles publiques de confiance
     * ({@code keyId -> cle}).
     */
    public static License verify(String code, Map<String, PublicKey> trusted) throws LicenseException {
        if (code == null) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "Code de licence vide");
        }
        String c = code.replaceAll("\\s+", "");
        if (!c.startsWith(PREFIX)) {
            throw new LicenseException(LicenseException.Reason.MALFORMED,
                    "Ce n'est pas un code de licence OLPOS (il doit commencer par " + PREFIX + ")");
        }
        String[] parts = c.substring(PREFIX.length()).split("\\.", -1);
        if (parts.length != 2) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "Code de licence incomplet");
        }
        byte[] payload;
        byte[] sig;
        try {
            Base64.Decoder d = Base64.getUrlDecoder();
            payload = d.decode(parts[0]);
            sig = d.decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "Code de licence illisible (copie incomplete ?)");
        }
        License l = License.parseCanonical(payload);
        PublicKey key = trusted.get(l.getKeyId());
        if (key == null) {
            throw new LicenseException(LicenseException.Reason.UNKNOWN_KEY, "Licence signee par une cle inconnue de "
                    + "ce programme (" + l.getKeyId() + ") : demandez une licence a votre fournisseur OLPOS.");
        }
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(key);
            s.update(payload);
            if (!s.verify(sig)) {
                throw new LicenseException(LicenseException.Reason.BAD_SIGNATURE,
                        "Signature invalide : licence modifiee ou non delivree par votre fournisseur.");
            }
        } catch (GeneralSecurityException e) {
            throw new LicenseException(LicenseException.Reason.BAD_SIGNATURE, "Signature invalide : " + e.getMessage());
        }
        return l;
    }

    // ------------------------------------------------------------------ cles publiques

    /** Cle publique encodee (X.509 SubjectPublicKeyInfo, base64). */
    public static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublicKey(String base64) throws GeneralSecurityException {
        return KeyFactory.getInstance(ALGORITHM).generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
    }

    /**
     * Identifiant d'une cle publique : 16 premiers caracteres hexadecimaux de son
     * empreinte SHA-256 (affiche dans les deux programmes pour verification).
     */
    public static String keyId(PublicKey key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Empreinte complete (SHA-256, hexadecimal groupe) d'une cle publique. */
    public static String fingerprint(PublicKey key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0 && i % 2 == 0) {
                    sb.append(' ');
                }
                sb.append(String.format("%02X", d[i]));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
