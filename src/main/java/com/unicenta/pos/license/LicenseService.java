//    OLPOS (derive d'uniCenta oPOS) - activation par licence
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.license;

import com.olpos.license.License;
import com.olpos.license.LicenseCodec;
import com.olpos.license.LicenseEvaluation;
import com.olpos.license.LicenseException;
import com.unicenta.pos.forms.DataLogicSystem;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Activation d'OLPOS par licence signee.
 *
 * <h3>Ce qui est verifie, sans connexion Internet</h3>
 * <ul>
 *   <li>signature Ed25519 avec la cle PUBLIQUE embarquee (une licence modifiee
 *       ou fabriquee est refusee) ;</li>
 *   <li>dates en UTC (debut / fin inclus, voir {@link License}) ;</li>
 *   <li>poste : si la licence est liee a un poste, code du poste (empreinte
 *       SHA-256 de l'identifiant Windows, non reversible) ;</li>
 *   <li>recul d'horloge : derniere heure connue gardee dans la base ET dans le
 *       dossier de donnees ;</li>
 *   <li>nombre de postes : postes enregistres dans la base partagee (actifs
 *       sur 30 jours).</li>
 * </ul>
 *
 * <h3>Limites (activation entierement hors ligne)</h3>
 * Sans serveur de licences : pas de revocation immediate d'une licence deja
 * delivree (elle reste valable jusqu'a sa date de fin), pas de controle des
 * postes entre bases de donnees separees, et la detection du recul d'horloge
 * peut etre contournee en effacant les traces locales. OLPOS etant un
 * logiciel libre (GPL v3), ce mecanisme n'empeche pas de modifier ou de
 * redistribuer le code : il protege l'usage normal du programme distribue.
 *
 * <h3>Comportement</h3>
 * Licence absente, invalide ou expiree : les NOUVELLES ventes sont bloquees ;
 * les commandes en cours peuvent etre terminees et encaissees ; l'activation,
 * la consultation, les rapports, les sauvegardes et l'export restent
 * accessibles. Les donnees ne sont jamais supprimees ni chiffrees.
 */
public final class LicenseService {

    private static final Logger LOG = Logger.getLogger(LicenseService.class.getName());

    public static final String TRUSTED_KEYS_RESOURCE = "/com/olpos/license/trusted-keys.properties";
    static final String DB_LICENSE = "olpos.license";
    static final String DB_CLOCK = "olpos.license.clock";
    static final String DB_STATIONS = "olpos.license.stations";
    static final String FILE_LICENSE = "licence" + LicenseCodec.FILE_EXTENSION;
    static final String FILE_HISTORY = "licence-historique.txt";
    static final String FILE_CLOCK = "licence-horloge.txt";
    static final String FILE_MACHINE = "poste.txt";
    /** Tolerance d'horloge (synchronisation Windows, changement d'heure). */
    static final long CLOCK_TOLERANCE_MS = 10 * 60_000L;
    /** Un poste sans activite depuis 30 jours libere sa place. */
    static final long STATION_INACTIVE_MS = 30L * 86_400_000L;

    private static volatile LicenseService current;

    private final Map<String, PublicKey> trusted;
    private final File dataDir;
    private final DataLogicSystem db;           // peut etre null (avant ouverture de la base)
    private final Supplier<Instant> clock;
    private final String machineCode;
    private final ZoneId zone;
    private volatile LicenseEvaluation evaluation = LicenseEvaluation.none();

    public LicenseService(Map<String, PublicKey> trusted, File dataDir, DataLogicSystem db,
            Supplier<Instant> clock, String machineCode, ZoneId zone) {
        this.trusted = Collections.unmodifiableMap(new LinkedHashMap<>(trusted));
        this.dataDir = dataDir;
        this.db = db;
        this.clock = clock;
        this.machineCode = machineCode;
        this.zone = zone;
    }

    /** Service de la caisse (cles embarquees, horloge du PC). */
    public static LicenseService forTill(File dataDir, DataLogicSystem db) {
        return new LicenseService(embeddedKeys(), dataDir, db, Instant::now, machineCode(dataDir), ZoneId.systemDefault());
    }

    public static LicenseService current() {
        return current;
    }

    public static void install(LicenseService s) {
        current = s;
    }

    /** Etat actuel (calcule par le dernier {@link #refresh()}). */
    public static LicenseEvaluation currentEvaluation() {
        LicenseService s = current;
        return s == null ? LicenseEvaluation.none() : s.evaluation;
    }

    /** Les nouvelles ventes sont-elles autorisees sur ce poste ? */
    public static boolean allowsNewSales() {
        return currentEvaluation().allowsNewSales();
    }

    /**
     * Fonction utilisable pour TERMINER une commande deja ouverte : une licence
     * reconnue (meme expiree) qui comprend cette fonction suffit ; la creation
     * de nouvelles commandes reste soumise a {@link #allowsNewSales()}.
     */
    public static boolean allowsForOpenOrders(String feature) {
        LicenseEvaluation e = currentEvaluation();
        License l = e.getLicense();
        if (l == null || !l.hasFeature(feature)) {
            return false;
        }
        switch (e.getStatus()) {
            case VALID:
            case EXPIRED:
            case CLOCK_ROLLBACK:
            case TOO_MANY_STATIONS:
            case NOT_YET_VALID:
                return true;
            default:
                return false;
        }
    }

    public LicenseEvaluation getEvaluation() {
        return evaluation;
    }

    public String getMachineCode() {
        return machineCode;
    }

    public Map<String, PublicKey> getTrustedKeys() {
        return trusted;
    }

    public ZoneId getZone() {
        return zone;
    }

    // ================================================================== evaluation

    /** Relit la licence installee et recalcule son etat. */
    public synchronized LicenseEvaluation refresh() {
        Instant now = clock.get();
        String code = storedCode();
        LicenseEvaluation e;
        if (code == null) {
            e = LicenseEvaluation.none();
        } else {
            try {
                License l = LicenseCodec.verify(code, trusted);
                e = evaluate(l, now);
            } catch (LicenseException ex) {
                e = LicenseEvaluation.invalid(ex.getMessage());
            }
        }
        evaluation = e;
        return e;
    }

    private LicenseEvaluation evaluate(License l, Instant now) {
        // 1. horloge : jamais avant la derniere heure connue, ni avant la delivrance
        Instant lastSeen = lastSeen();
        if (lastSeen != null && now.plusMillis(CLOCK_TOLERANCE_MS).isBefore(lastSeen)) {
            return LicenseEvaluation.clockRollback(l, lastSeen, zone);
        }
        if (now.plusMillis(CLOCK_TOLERANCE_MS).isBefore(l.getIssuedAt())) {
            return LicenseEvaluation.clockRollback(l, l.getIssuedAt(), zone);
        }
        rememberTime(now);
        // 2. dates et poste
        LicenseEvaluation e = LicenseEvaluation.evaluate(l, now, machineCode);
        // 3. nombre de postes (base partagee)
        if (e.getStatus() == LicenseEvaluation.Status.VALID && l.getMaxStations() > 0 && db != null
                && !registerStation(l.getMaxStations(), now)) {
            return LicenseEvaluation.tooManyStations(l, l.getMaxStations());
        }
        return e;
    }

    // ================================================================== installation

    /**
     * Installe une licence (code ou contenu de fichier) apres verification.
     * Une licence renouvelee remplace l'ancienne sans reinstallation ni perte
     * de donnees ; une licence PLUS ANCIENNE que celle installee est refusee.
     */
    public synchronized LicenseEvaluation install(String code) throws LicenseException, IOException {
        License l = LicenseCodec.verify(code, trusted);
        String old = storedCode();
        License installed = null;
        if (old != null) {
            try {
                installed = LicenseCodec.verify(old, trusted);
            } catch (LicenseException unreadable) {
                installed = null;   // l'ancienne n'est plus lisible : la nouvelle la remplace
            }
        }
        if (installed != null && installed.getSerial().equals(l.getSerial())
                && l.getRevision() < installed.getRevision()) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "Cette licence (revision "
                    + l.getRevision() + ") est plus ancienne que celle installee (revision "
                    + installed.getRevision() + ").");
        }
        String clean = code.replaceAll("\\s+", "");
        writeFile(FILE_LICENSE, clean + "\r\n");
        if (db != null) {
            db.setResource(DB_LICENSE, 0, clean.getBytes(StandardCharsets.UTF_8));
        }
        appendHistory(l);
        return refresh();
    }

    /** Code installe : base (partagee par les postes), sinon fichier du dossier de donnees. */
    String storedCode() {
        String c = null;
        if (db != null) {
            try {
                c = db.getResourceAsText(DB_LICENSE);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Licence illisible dans la base", e);
            }
        }
        if (c == null || c.trim().isEmpty()) {
            c = readFile(FILE_LICENSE);
            if (c != null && !c.trim().isEmpty() && db != null) {
                db.setResource(DB_LICENSE, 0, c.trim().getBytes(StandardCharsets.UTF_8));   // partage avec les autres postes
            }
        }
        return c == null || c.trim().isEmpty() ? null : c.trim();
    }

    private void appendHistory(License l) {
        String line = clock.get() + " ; " + l.getSerial() + " rev " + l.getRevision() + " ; " + l.getRestaurant()
                + " ; " + l.getValidFrom() + " -> " + l.getValidUntil() + "\r\n";
        try {
            File f = new File(dataDir, FILE_HISTORY);
            Files.write(f.toPath(), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Historique des licences non ecrit", e);
        }
    }

    // ================================================================== horloge

    /** Derniere heure connue : la plus recente de la base et du fichier local. */
    Instant lastSeen() {
        Instant a = parseInstant(readFile(FILE_CLOCK));
        Instant b = null;
        if (db != null) {
            try {
                b = parseInstant(db.getResourceAsText(DB_CLOCK));
            } catch (RuntimeException e) {
                b = null;
            }
        }
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private void rememberTime(Instant now) {
        Instant last = lastSeen();
        if (last != null && !now.isAfter(last)) {
            return;
        }
        try {
            writeFile(FILE_CLOCK, now.toString());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Heure de reference non ecrite", e);
        }
        if (db != null) {
            try {
                db.setResource(DB_CLOCK, 0, now.toString().getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Heure de reference non ecrite dans la base", e);
            }
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ================================================================== postes

    /** Postes connus : code -> "derniere activite (ms)|nom du PC". */
    public synchronized Map<String, String> stations() {
        Properties p = new Properties();
        if (db != null) {
            String t = db.getResourceAsText(DB_STATIONS);
            if (t != null) {
                try {
                    p.load(new java.io.StringReader(t));
                } catch (IOException e) {
                    // liste illisible : repart de zero
                }
            }
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : p.stringPropertyNames()) {
            m.put(k, p.getProperty(k));
        }
        return m;
    }

    private void saveStations(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        db.setResource(DB_STATIONS, 0, sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Enregistre ce poste ; false si la licence n'a plus de place. */
    private boolean registerStation(int max, Instant now) {
        Map<String, String> m = stations();
        long limit = now.toEpochMilli() - STATION_INACTIVE_MS;
        m.entrySet().removeIf(e -> lastActivity(e.getValue()) < limit && !e.getKey().equals(machineCode));
        if (!m.containsKey(machineCode) && m.size() >= max) {
            saveStations(m);
            return false;
        }
        m.put(machineCode, now.toEpochMilli() + "|" + hostName().replace('|', '_').replace('\n', ' '));
        saveStations(m);
        return true;
    }

    /** Libere la place d'un poste (PC remplace ou hors service). */
    public synchronized void freeStation(String code) {
        if (db == null) {
            return;
        }
        Map<String, String> m = stations();
        m.remove(code);
        saveStations(m);
        refresh();
    }

    static long lastActivity(String v) {
        try {
            return Long.parseLong(v.split("\\|", 2)[0]);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "?";
        }
    }

    // ================================================================== code du poste

    /**
     * Code du poste : empreinte SHA-256 (tronquee) de l'identifiant d'installation
     * de Windows (MachineGuid), prefixee pour OLPOS. Non reversible, ne contient
     * ni nom, ni adresse, ni numero de serie de materiel. Change si Windows est
     * reinstalle ou si le PC est remplace : la licence se transfere alors depuis
     * OLPOS License Manager (nouvelle revision pour le nouveau code).
     */
    public static String machineCode(File dataDir) {
        String id = windowsMachineGuid();
        if (id == null) {
            // autre systeme ou registre inaccessible : identifiant aleatoire garde localement
            String stored = readFile(dataDir, FILE_MACHINE);
            if (stored == null || stored.trim().isEmpty()) {
                byte[] b = new byte[16];
                new SecureRandom().nextBytes(b);
                stored = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
                try {
                    Files.write(new File(dataDir, FILE_MACHINE).toPath(), stored.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Identifiant de poste non enregistre", e);
                }
            }
            id = "local:" + stored.trim();
        }
        return codeFor(id);
    }

    /** Code affiche a partir d'un identifiant brut (XXXX-XXXX-XXXX-XXXX). */
    public static String codeFor(String rawId) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("OLPOS-POSTE|" + rawId.trim().toLowerCase())
                    .getBytes(StandardCharsets.UTF_8));
            String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
            StringBuilder sb = new StringBuilder();
            long bits = 0;
            int n = 0;
            int i = 0;
            while (sb.length() < 19) {
                if (n < 5) {
                    bits = (bits << 8) | (d[i++] & 0xff);
                    n += 8;
                }
                n -= 5;
                sb.append(alphabet.charAt((int) ((bits >> n) & 31)));
                if (sb.length() == 4 || sb.length() == 9 || sb.length() == 14) {
                    sb.append('-');
                }
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String windowsMachineGuid() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return null;
        }
        try {
            Process p = new ProcessBuilder("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
                    .redirectErrorStream(true).start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(readAll(in), StandardCharsets.UTF_8);
            }
            p.waitFor();
            for (String line : out.split("\\r?\\n")) {
                String t = line.trim();
                if (t.startsWith("MachineGuid")) {
                    String[] parts = t.split("\\s+");
                    return parts[parts.length - 1];
                }
            }
        } catch (IOException | InterruptedException e) {
            LOG.log(Level.FINE, "MachineGuid illisible", e);
        }
        return null;
    }

    // ================================================================== cles embarquees

    /** Cles publiques de confiance fournies avec le programme (aucune cle privee). */
    public static Map<String, PublicKey> embeddedKeys() {
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        try (InputStream in = LicenseService.class.getResourceAsStream(TRUSTED_KEYS_RESOURCE)) {
            if (in == null) {
                LOG.warning("Aucune cle publique de licence dans ce programme : activation impossible.");
                return keys;
            }
            Properties p = new Properties();
            p.load(in);
            for (String id : p.stringPropertyNames()) {
                try {
                    PublicKey k = LicenseCodec.decodePublicKey(p.getProperty(id));
                    if (!LicenseCodec.keyId(k).equals(id)) {
                        LOG.warning("Cle de licence ignoree (identifiant incoherent) : " + id);
                        continue;
                    }
                    keys.put(id, k);
                } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
                    LOG.log(Level.WARNING, "Cle de licence illisible : " + id, e);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cles de licence illisibles", e);
        }
        return keys;
    }

    // ================================================================== fichiers

    private String readFile(String name) {
        return readFile(dataDir, name);
    }

    private static String readFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.isFile()) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private void writeFile(String name, String content) throws IOException {
        if (!dataDir.isDirectory() && !dataDir.mkdirs()) {
            throw new IOException("Dossier de donnees inaccessible : " + dataDir);
        }
        Files.write(new File(dataDir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
}
