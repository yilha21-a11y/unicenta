//    OLPOS License Manager - stockage chiffre
//    Licence GPL v3 (voir ../LICENSE.md).
package com.olpos.licensemanager;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Fichier chiffre du gestionnaire : AES-256-GCM, cle derivee du mot de passe
 * par PBKDF2-HMAC-SHA256 (sel aleatoire, 310 000 iterations).
 *
 * <pre>OLPOSLM1 | iterations (4 o) | sel (16 o) | iv (12 o) | donnees chiffrees + etiquette</pre>
 *
 * Toute modification du fichier ou mauvais mot de passe => refus (GCM).
 * Utilise pour les donnees du gestionnaire (cle privee comprise) et pour la
 * sauvegarde separee de la cle privee (avec sa propre phrase de passe).
 */
public final class Vault {

    static final byte[] MAGIC = "OLPOSLM1".getBytes(StandardCharsets.US_ASCII);
    static final int ITERATIONS = 310_000;

    /** Mot de passe incorrect ou fichier altere. */
    public static final class WrongPasswordException extends Exception {
        private static final long serialVersionUID = 1L;

        WrongPasswordException() {
            super("Mot de passe incorrect (ou fichier modifie).");
        }
    }

    private Vault() {
    }

    public static byte[] encrypt(byte[] plain, char[] password) throws GeneralSecurityException {
        SecureRandom r = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        r.nextBytes(salt);
        r.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), new GCMParameterSpec(128, iv));
        c.updateAAD(MAGIC);
        byte[] ct = c.doFinal(plain);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(MAGIC);
            out.writeInt(ITERATIONS);
            out.write(salt);
            out.write(iv);
            out.write(ct);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] decrypt(byte[] data, char[] password) throws WrongPasswordException, IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("Ce n'est pas un fichier OLPOS License Manager.");
            }
            int iterations = in.readInt();
            if (iterations < 100_000 || iterations > 10_000_000) {
                throw new IOException("Fichier OLPOS License Manager invalide.");
            }
            byte[] salt = new byte[16];
            byte[] iv = new byte[12];
            in.readFully(salt);
            in.readFully(iv);
            byte[] ct = new byte[in.available()];
            in.readFully(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), new GCMParameterSpec(128, iv));
            c.updateAAD(MAGIC);
            return c.doFinal(ct);
        } catch (AEADBadTagException e) {
            throw new WrongPasswordException();
        } catch (java.io.EOFException e) {
            throw new IOException("Fichier OLPOS License Manager tronque.");
        } catch (GeneralSecurityException e) {
            throw new IOException("Dechiffrement impossible : " + e.getMessage(), e);
        }
    }

    private static SecretKeySpec key(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(k, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    /** Ecriture sure : fichier temporaire puis remplacement (pas de fichier a moitie ecrit). */
    public static void writeAtomically(File f, byte[] data) throws IOException {
        File dir = f.getAbsoluteFile().getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Dossier inaccessible : " + dir);
        }
        File tmp = new File(dir, f.getName() + ".tmp");
        Files.write(tmp.toPath(), data);
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Regles minimales du mot de passe du gestionnaire. */
    public static String checkPassword(char[] pw) {
        if (pw == null || pw.length < 10) {
            return "Le mot de passe doit contenir au moins 10 caracteres.";
        }
        boolean letter = false;
        boolean other = false;
        for (char c : pw) {
            if (Character.isLetter(c)) {
                letter = true;
            } else {
                other = true;
            }
        }
        return letter && other ? null : "Melangez lettres et chiffres ou symboles.";
    }
}
