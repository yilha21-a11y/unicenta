//    OLPOS (derive d'uniCenta oPOS) - liaison avec le portail Patron
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.portal;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Etat local de la liaison caisse / portail, dans le dossier de donnees
 * d'OLPOS ({@code portail\}) :
 * <ul>
 *   <li>{@code liaison.properties} : adresse du portail, restaurant, jeton
 *       d'appareil, secret local servant aux empreintes de comptes ;</li>
 *   <li>{@code cles.properties} : cle de SIGNATURE (Ed25519) des preuves de
 *       connexion et cle de CHIFFREMENT (RSA-OAEP) des identifiants envoyes par
 *       le navigateur. Seules les cles publiques sont envoyees au portail ;</li>
 *   <li>{@code double-authentification.properties} : secrets TOTP des comptes
 *       autorises (jamais envoyes au portail).</li>
 * </ul>
 */
public final class PortalLink {

    public static final String DIR = "portail";
    static final String OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    private final File dir;
    private final Properties link = new Properties();
    private final Properties keys = new Properties();
    private final Properties totp = new Properties();

    public PortalLink(File dataDir) {
        this.dir = new File(dataDir, DIR);
        load(link, "liaison.properties");
        load(keys, "cles.properties");
        load(totp, "double-authentification.properties");
    }

    public File dir() {
        return dir;
    }

    public synchronized boolean isPaired() {
        return link.getProperty("deviceToken") != null;
    }

    public synchronized String url() {
        return link.getProperty("url");
    }

    public synchronized String token() {
        return link.getProperty("deviceToken");
    }

    public synchronized String restaurantId() {
        return link.getProperty("restaurantId");
    }

    public synchronized String restaurantName() {
        return link.getProperty("name");
    }

    public synchronized String slug() {
        return link.getProperty("slug");
    }

    /** Adresse du portail : HTTPS obligatoire (sauf demonstration sur ce PC). */
    public static String checkUrl(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        java.net.URI uri = java.net.URI.create(u);
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean local = host.equals("localhost") || host.equals("127.0.0.1");
        if (!"https".equals(uri.getScheme()) && !(local && "http".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("L'adresse du portail doit commencer par https:// "
                    + "(http:// seulement pour une demonstration sur ce PC).");
        }
        return u;
    }

    // ================================================================== cles

    /** Cree les cles de la caisse si besoin ; renvoie {cle de signature publique, cle de chiffrement publique}. */
    public synchronized String[] ensureKeys() throws GeneralSecurityException, IOException {
        if (keys.getProperty("signPriv") == null) {
            KeyPair sign = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair enc = g.generateKeyPair();
            Base64.Encoder b = Base64.getEncoder();
            keys.setProperty("signPriv", b.encodeToString(sign.getPrivate().getEncoded()));
            keys.setProperty("signPub", b.encodeToString(sign.getPublic().getEncoded()));
            keys.setProperty("encPriv", b.encodeToString(enc.getPrivate().getEncoded()));
            keys.setProperty("encPub", b.encodeToString(enc.getPublic().getEncoded()));
            save(keys, "cles.properties");
        }
        return new String[] {keys.getProperty("signPub"), keys.getProperty("encPub")};
    }

    /** Signature Ed25519 (base64) d'une preuve de connexion. */
    public synchronized String sign(String text) throws GeneralSecurityException {
        PrivateKey k = KeyFactory.getInstance("Ed25519").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keys.getProperty("signPriv"))));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(k);
        s.update(text.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    /** Dechiffre le bloc envoye par le navigateur (RSA-OAEP SHA-256, comme WebCrypto). */
    public synchronized String decrypt(String ciphertextB64) throws GeneralSecurityException {
        PrivateKey k = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keys.getProperty("encPriv"))));
        Cipher c = Cipher.getInstance(OAEP);
        c.init(Cipher.DECRYPT_MODE, k, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT));
        return new String(c.doFinal(Base64.getDecoder().decode(ciphertextB64)), StandardCharsets.UTF_8);
    }

    // ================================================================== association

    public synchronized void savePairing(String url, String rid, String slug, String name, String token) throws IOException {
        link.setProperty("url", url);
        link.setProperty("restaurantId", rid);
        link.setProperty("slug", slug);
        link.setProperty("name", name);
        link.setProperty("deviceToken", token);
        link.setProperty("pairedAt", Long.toString(System.currentTimeMillis()));
        if (link.getProperty("stampSecret") == null) {
            byte[] b = new byte[32];
            new SecureRandom().nextBytes(b);
            link.setProperty("stampSecret", Base64.getEncoder().encodeToString(b));
        }
        save(link, "liaison.properties");
    }

    /** Dissocie : le jeton est oublie (le portail n'accepte plus cette caisse apres une nouvelle association). */
    public synchronized void unpair() throws IOException {
        link.remove("deviceToken");
        link.remove("restaurantId");
        save(link, "liaison.properties");
    }

    /**
     * Empreinte opaque de l'etat d'un compte : change si le mot de passe, le
     * role, la visibilite, l'autorisation ou la double authentification change.
     * Ne permet de retrouver ni le mot de passe ni son empreinte.
     */
    public synchronized String stamp(String uid, String password, String role, boolean eligible) {
        try {
            String secret = link.getProperty("stampSecret", "");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret.isEmpty() ? "AA==" : secret), "HmacSHA256"));
            byte[] h = mac.doFinal((uid + "\n" + password + "\n" + role + "\n" + eligible + "\n"
                    + totp.getProperty(uid, "")).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h).substring(0, 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ================================================================== double authentification

    public synchronized String totpSecret(String uid) {
        return totp.getProperty(uid);
    }

    public synchronized void enroll(String uid, String secret) throws IOException {
        totp.setProperty(uid, secret);
        save(totp, "double-authentification.properties");
    }

    public synchronized void unenroll(String uid) throws IOException {
        totp.remove(uid);
        save(totp, "double-authentification.properties");
    }

    // ================================================================== fichiers

    private void load(Properties p, String name) {
        File f = new File(dir, name);
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException e) {
                // fichier illisible : considere comme absent
            }
        }
    }

    private void save(Properties p, String name) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Dossier inaccessible : " + dir);
        }
        File tmp = new File(dir, name + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            p.store(out, "OLPOS - portail Patron (ne pas partager)");
        }
        java.nio.file.Files.move(tmp.toPath(), new File(dir, name).toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
