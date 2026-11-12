//    OLPOS License Manager - donnees du gestionnaire
//    Licence GPL v3 (voir ../LICENSE.md).
package com.olpos.licensemanager;

import com.olpos.license.License;
import com.olpos.license.LicenseCodec;
import com.olpos.license.LicenseEvaluation;
import com.olpos.license.Serials;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Donnees du gestionnaire (clients, restaurants, licences et leur historique)
 * et cle privee de signature, dans UN fichier chiffre ({@link Vault}).
 *
 * <p>La cle privee est generee a la premiere configuration ; elle n'est jamais
 * ecrite en clair. Sa perte empeche de signer les renouvellements : une
 * sauvegarde chiffree separee est proposee ({@link #exportPrivateKey}).</p>
 */
public final class ManagerStore {

    public static final String DATA_FILE = "olpos-license-manager.dat";

    /** Etat d'une licence pour les listes. */
    public enum State { ACTIVE, FUTURE, EXPIRED }

    private final File file;
    private char[] password;
    private Map<String, Object> data;
    private PrivateKey privateKey;
    private PublicKey publicKey;

    private ManagerStore(File file, char[] password) {
        this.file = file;
        this.password = password.clone();
    }

    /** Dossier du gestionnaire : -Dolpos.lm.dir, sinon %APPDATA%\OLPOS License Manager. */
    public static File defaultDir() {
        String d = System.getProperty("olpos.lm.dir");
        if (d != null && !d.trim().isEmpty()) {
            return new File(d.trim());
        }
        String appdata = System.getenv("APPDATA");
        File base = appdata == null ? new File(System.getProperty("user.home")) : new File(appdata);
        return new File(base, "OLPOS License Manager");
    }

    public static boolean exists(File dir) {
        return new File(dir, DATA_FILE).isFile();
    }

    /** Premiere configuration : nouvelle paire de cles Ed25519. */
    public static ManagerStore create(File dir, char[] password) throws GeneralSecurityException, IOException {
        KeyPair kp = KeyPairGenerator.getInstance(LicenseCodec.ALGORITHM).generateKeyPair();
        return create(dir, password, kp.getPrivate(), kp.getPublic());
    }

    /** Premiere configuration a partir d'une cle sauvegardee (PC remplace). */
    public static ManagerStore createFromKeyBackup(File dir, char[] password, File keyBackup, char[] passphrase)
            throws GeneralSecurityException, IOException, Vault.WrongPasswordException {
        Map<String, Object> k = parseObject(Vault.decrypt(Files.readAllBytes(keyBackup.toPath()), passphrase));
        PrivateKey priv = decodePrivate((String) k.get("privateKey"));
        PublicKey pub = LicenseCodec.decodePublicKey((String) k.get("publicKey"));
        return create(dir, password, priv, pub);
    }

    private static ManagerStore create(File dir, char[] password, PrivateKey priv, PublicKey pub)
            throws GeneralSecurityException, IOException {
        String problem = Vault.checkPassword(password);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        File f = new File(dir, DATA_FILE);
        if (f.exists()) {
            throw new IOException("Le gestionnaire est deja configure (" + f + ").");
        }
        ManagerStore s = new ManagerStore(f, password);
        s.data = new LinkedHashMap<>();
        s.data.put("format", "OLPOS-LM-1");
        s.data.put("createdAt", Instant.now().toString());
        s.data.put("privateKey", Base64.getEncoder().encodeToString(priv.getEncoded()));
        s.data.put("publicKey", LicenseCodec.encodePublicKey(pub));
        s.data.put("clients", new ArrayList<Object>());
        s.data.put("licenses", new ArrayList<Object>());
        s.privateKey = priv;
        s.publicKey = pub;
        s.save();
        return s;
    }

    public static ManagerStore open(File dir, char[] password)
            throws IOException, Vault.WrongPasswordException, GeneralSecurityException {
        File f = new File(dir, DATA_FILE);
        ManagerStore s = new ManagerStore(f, password);
        s.data = parseObject(Vault.decrypt(Files.readAllBytes(f.toPath()), password));
        s.privateKey = decodePrivate((String) s.data.get("privateKey"));
        s.publicKey = LicenseCodec.decodePublicKey((String) s.data.get("publicKey"));
        return s;
    }

    public synchronized void save() throws IOException {
        try {
            Vault.writeAtomically(file, Vault.encrypt(Json.write(data).getBytes(StandardCharsets.UTF_8), password));
        } catch (GeneralSecurityException e) {
            throw new IOException("Chiffrement impossible : " + e.getMessage(), e);
        }
    }

    public synchronized void changePassword(char[] oldPassword, char[] newPassword) throws IOException,
            Vault.WrongPasswordException {
        if (!Arrays.equals(oldPassword, password)) {
            throw new Vault.WrongPasswordException();
        }
        String problem = Vault.checkPassword(newPassword);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        password = newPassword.clone();
        save();
    }

    public File getFile() {
        return file;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public String getKeyId() {
        return LicenseCodec.keyId(publicKey);
    }

    // ================================================================== clients

    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> clients() {
        return (List<Map<String, Object>>) (List<?>) data.get("clients");
    }

    public synchronized List<Map<String, Object>> searchClients(String q) {
        List<Map<String, Object>> out = new ArrayList<>();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        for (Map<String, Object> c : clients()) {
            String hay = (c.get("customer") + " " + c.get("restaurant") + " " + c.get("contact") + " " + c.get("notes"))
                    .toLowerCase(Locale.ROOT);
            if (needle.isEmpty() || hay.contains(needle)) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> c) -> String.valueOf(c.get("customer")).toLowerCase(Locale.ROOT))
                .thenComparing(c -> String.valueOf(c.get("restaurant")).toLowerCase(Locale.ROOT)));
        return out;
    }

    public synchronized Map<String, Object> client(String id) {
        for (Map<String, Object> c : clients()) {
            if (id.equals(c.get("id"))) {
                return c;
            }
        }
        return null;
    }

    public synchronized Map<String, Object> saveClient(String id, String customer, String restaurant, String contact,
            String notes) throws IOException {
        if (customer == null || customer.trim().isEmpty() || restaurant == null || restaurant.trim().isEmpty()) {
            throw new IllegalArgumentException("Client et restaurant sont obligatoires.");
        }
        Map<String, Object> c = id == null ? null : client(id);
        if (c == null) {
            c = new LinkedHashMap<>();
            c.put("id", UUID.randomUUID().toString());
            c.put("createdAt", Instant.now().toString());
            clients().add(c);
        }
        c.put("customer", customer.trim());
        c.put("restaurant", restaurant.trim());
        c.put("contact", contact == null ? "" : contact.trim());
        c.put("notes", notes == null ? "" : notes.trim());
        save();
        return c;
    }

    public synchronized void deleteClient(String id) throws IOException {
        for (Map<String, Object> l : licenses()) {
            if (id.equals(l.get("clientId"))) {
                throw new IllegalStateException("Ce client a des licences : il est conserve (historique).");
            }
        }
        clients().removeIf(c -> id.equals(c.get("id")));
        save();
    }

    // ================================================================== licences

    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> licenses() {
        return (List<Map<String, Object>>) (List<?>) data.get("licenses");
    }

    /** Derniere revision de chaque numero de serie. */
    public synchronized List<Map<String, Object>> latestLicenses() {
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        for (Map<String, Object> l : licenses()) {
            Map<String, Object> cur = latest.get(l.get("serial"));
            if (cur == null || num(l.get("revision")) > num(cur.get("revision"))) {
                latest.put((String) l.get("serial"), l);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(latest.values());
        out.sort(Comparator.comparing((Map<String, Object> l) -> String.valueOf(l.get("validUntil"))));
        return out;
    }

    /** Historique complet d'un numero de serie (revision croissante). */
    public synchronized List<Map<String, Object>> history(String serial) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : licenses()) {
            if (serial.equals(l.get("serial"))) {
                out.add(l);
            }
        }
        out.sort(Comparator.comparingLong(l -> num(l.get("revision"))));
        return out;
    }

    /** Nouvelle licence (nouveau numero de serie). */
    public synchronized Map<String, Object> issue(String clientId, LocalDate from, LocalDate until, Set<String> features,
            int maxStations, String machine, String notes) throws IOException, GeneralSecurityException {
        String serial;
        do {
            serial = Serials.newSerial(new SecureRandom());
        } while (!history(serial).isEmpty());
        return sign(serial, 1, clientId, from, until, features, maxStations, machine, notes);
    }

    /**
     * Renouvellement (ou transfert vers un nouveau poste) : MEME numero de serie,
     * revision + 1 ; les revisions precedentes restent dans l'historique.
     */
    public synchronized Map<String, Object> renew(String serial, LocalDate from, LocalDate until, Set<String> features,
            int maxStations, String machine, String notes) throws IOException, GeneralSecurityException {
        List<Map<String, Object>> h = history(serial);
        if (h.isEmpty()) {
            throw new IllegalArgumentException("Licence inconnue : " + serial);
        }
        Map<String, Object> last = h.get(h.size() - 1);
        return sign(serial, (int) num(last.get("revision")) + 1, (String) last.get("clientId"), from, until, features,
                maxStations, machine, notes);
    }

    private Map<String, Object> sign(String serial, int revision, String clientId, LocalDate from, LocalDate until,
            Set<String> features, int maxStations, String machine, String notes)
            throws IOException, GeneralSecurityException {
        Map<String, Object> c = client(clientId);
        if (c == null) {
            throw new IllegalArgumentException("Client inconnu.");
        }
        Set<String> f = new LinkedHashSet<>(features);
        f.add(License.FEATURE_SALES);   // une licence autorise toujours les ventes
        License l = new License(serial, revision, (String) c.get("customer"), (String) c.get("restaurant"), from, until,
                f, maxStations, machine, Instant.now(), getKeyId());
        String code = LicenseCodec.sign(l, privateKey);
        // controle immediat avec la cle publique (meme verification qu'OLPOS)
        try {
            LicenseCodec.verify(code, Collections.singletonMap(getKeyId(), publicKey));
        } catch (com.olpos.license.LicenseException e) {
            throw new GeneralSecurityException("Licence produite invalide : " + e.getMessage());
        }
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("serial", serial);
        rec.put("revision", (long) revision);
        rec.put("clientId", clientId);
        rec.put("customer", l.getCustomer());
        rec.put("restaurant", l.getRestaurant());
        rec.put("validFrom", from.toString());
        rec.put("validUntil", until.toString());
        rec.put("features", new ArrayList<Object>(f));
        rec.put("maxStations", (long) maxStations);
        rec.put("machine", l.getMachine());
        rec.put("issuedAt", l.getIssuedAt().toString());
        rec.put("notes", notes == null ? "" : notes.trim());
        rec.put("code", code);
        licenses().add(rec);
        save();
        return rec;
    }

    public static State stateOf(Map<String, Object> l, Instant now) {
        License lic = toLicense(l);
        if (now.isBefore(lic.getStart())) {
            return State.FUTURE;
        }
        return now.isBefore(lic.getEnd()) ? State.ACTIVE : State.EXPIRED;
    }

    public static long daysLeft(Map<String, Object> l, Instant now) {
        return LicenseEvaluation.daysLeft(now, toLicense(l).getEnd());
    }

    @SuppressWarnings("unchecked")
    static License toLicense(Map<String, Object> l) {
        return new License((String) l.get("serial"), (int) num(l.get("revision")), (String) l.get("customer"),
                (String) l.get("restaurant"), LocalDate.parse((String) l.get("validFrom")),
                LocalDate.parse((String) l.get("validUntil")), new LinkedHashSet<>((List<String>) (List<?>) l.get("features")),
                (int) num(l.get("maxStations")), (String) l.get("machine"), Instant.parse((String) l.get("issuedAt")),
                "-");
    }

    // ================================================================== export / sauvegardes

    /** Export CSV (separateur ; , UTF-8 avec BOM pour Excel). */
    public synchronized void exportCsv(File f, List<Map<String, Object>> rows, Instant now) throws IOException {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Numero de serie;Revision;Client;Restaurant;Debut;Fin (incluse, UTC);Etat;Jours restants;"
                + "Fonctions;Postes max;Poste lie;Emise le (UTC);Notes\r\n");
        for (Map<String, Object> l : rows) {
            State st = stateOf(l, now);
            @SuppressWarnings("unchecked")
            List<Object> feats = (List<Object>) l.get("features");
            sb.append(csv(l.get("serial"))).append(';').append(csv(l.get("revision"))).append(';')
                    .append(csv(l.get("customer"))).append(';').append(csv(l.get("restaurant"))).append(';')
                    .append(csv(l.get("validFrom"))).append(';').append(csv(l.get("validUntil"))).append(';')
                    .append(csv(st == State.ACTIVE ? "active" : st == State.FUTURE ? "future" : "expiree")).append(';')
                    .append(st == State.EXPIRED ? "0" : Long.toString(daysLeft(l, now))).append(';')
                    .append(csv(String.join(",", toStrings(feats)))).append(';')
                    .append(csv(num(l.get("maxStations")) == 0 ? "illimite" : l.get("maxStations"))).append(';')
                    .append(csv(l.get("machine"))).append(';').append(csv(l.get("issuedAt"))).append(';')
                    .append(csv(l.get("notes"))).append("\r\n");
        }
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Sauvegarde complete (fichier chiffre par le mot de passe du gestionnaire). */
    public synchronized void backup(File target) throws IOException {
        save();
        Files.copy(file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Restaure une sauvegarde complete. Le fichier est d'abord verifie avec
     * {@code backupPassword} ; les donnees actuelles sont gardees a cote (.avant-restauration).
     */
    public static ManagerStore restore(File dir, File backup, char[] backupPassword)
            throws IOException, Vault.WrongPasswordException, GeneralSecurityException {
        byte[] content = Files.readAllBytes(backup.toPath());
        Map<String, Object> d = parseObject(Vault.decrypt(content, backupPassword));
        if (!"OLPOS-LM-1".equals(d.get("format"))) {
            throw new IOException("Ce n'est pas une sauvegarde d'OLPOS License Manager.");
        }
        File f = new File(dir, DATA_FILE);
        if (f.isFile()) {
            Files.copy(f.toPath(), new File(dir, DATA_FILE + ".avant-restauration").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Vault.writeAtomically(f, content);
        return open(dir, backupPassword);
    }

    /** Sauvegarde de la cle privee seule, chiffree par une phrase de passe distincte. */
    public synchronized void exportPrivateKey(File target, char[] passphrase) throws IOException, GeneralSecurityException {
        String problem = Vault.checkPassword(passphrase);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("format", "OLPOS-LM-KEY-1");
        k.put("keyId", getKeyId());
        k.put("privateKey", data.get("privateKey"));
        k.put("publicKey", data.get("publicKey"));
        Vault.writeAtomically(target, Vault.encrypt(Json.write(k).getBytes(StandardCharsets.UTF_8), passphrase));
    }

    /**
     * Cle PUBLIQUE a integrer a OLPOS (src/main/resources/com/olpos/license/trusted-keys.properties).
     * Elle ne permet pas de creer de licences.
     */
    public synchronized void exportPublicKey(File target) throws IOException {
        String text = "# Cle publique de verification des licences OLPOS (OLPOS License Manager)\r\n"
                + "# Empreinte SHA-256 : " + LicenseCodec.fingerprint(publicKey) + "\r\n"
                + getKeyId() + "=" + LicenseCodec.encodePublicKey(publicKey) + "\r\n";
        Files.write(target.toPath(), text.getBytes(StandardCharsets.ISO_8859_1));
    }

    // ================================================================== outils

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(byte[] json) throws IOException {
        try {
            return (Map<String, Object>) Json.parse(new String(json, StandardCharsets.UTF_8));
        } catch (Json.ParseException | ClassCastException e) {
            throw new IOException("Donnees du gestionnaire illisibles : " + e.getMessage());
        }
    }

    private static PrivateKey decodePrivate(String b64) throws GeneralSecurityException {
        return KeyFactory.getInstance(LicenseCodec.ALGORITHM).generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : Long.parseLong(String.valueOf(o));
    }

    private static List<String> toStrings(List<Object> l) {
        List<String> out = new ArrayList<>();
        if (l != null) {
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    private static String csv(Object o) {
        String s = o == null ? "" : String.valueOf(o);
        if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        // pas de formule interpretee par le tableur
        return s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@") ? "'" + s : s;
    }
}
