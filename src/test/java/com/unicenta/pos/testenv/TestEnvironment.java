//    OLPOS (derive d'uniCenta oPOS) - Outil de creation d'un environnement de TEST isole.
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.testenv;

import com.unicenta.data.loader.BatchSentence;
import com.unicenta.data.loader.BatchSentenceResource;
import com.unicenta.data.loader.Session;
import com.unicenta.pos.forms.AppConfig;
import com.unicenta.pos.forms.AppLocal;
import com.unicenta.pos.forms.AppViewConnection;
import com.unicenta.pos.forms.DataLogicSystem;
import com.unicenta.pos.util.Hashcypher;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;

/**
 * Cree un environnement de test completement separe de toute installation
 * existante :
 * <ul>
 *   <li>une base Derby neuve dans {@code <dossier>/database} creee avec le
 *       meme script SQL que la caisse ({@code Derby-create.sql}) ;</li>
 *   <li>un fichier de configuration {@code <dossier>/unicentaopos-test.properties}
 *       (imprimantes = ecran, mode restaurant, API activee) ;</li>
 *   <li>des donnees de demonstration (categories, produits, un serveur).</li>
 * </ul>
 *
 * Securites :
 * <ul>
 *   <li>refuse de travailler sur la base designee par
 *       {@code ~/unicentaopos.properties} (installation existante) ;</li>
 *   <li>ne supprime une base existante qu'avec {@code --reset} ET seulement si
 *       le dossier porte le marqueur {@value #MARKER} cree par cet outil.</li>
 * </ul>
 *
 * Usage : {@code TestEnvironment <dossier> [--reset] [--port N] [--password P]}
 */
public final class TestEnvironment {

    static final String MARKER = ".unicenta-test-env";
    static final String CONFIG_NAME = "unicentaopos-test.properties";

    private TestEnvironment() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage : TestEnvironment <dossier> [--reset] [--port N] [--password P] [--timezone Zone]");
            System.exit(2);
        }
        File dir = new File(args[0]).getCanonicalFile();
        boolean reset = false;
        String port = "8095";
        String password = null;
        String timezone = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--timezone": timezone = args[++i]; break;
                case "--reset": reset = true; break;
                case "--port": port = args[++i]; break;
                case "--password": password = args[++i]; break;
                default:
                    System.err.println("Argument inconnu : " + args[i]);
                    System.exit(2);
            }
        }
        Result r = create(dir, reset, Integer.parseInt(port), password, new File(".").getCanonicalFile());
        if (timezone != null) {
            AppConfig c = new AppConfig(r.configFile);
            c.load();
            c.setProperty("user.timezone", timezone);
            c.save();
        }
        System.out.println();
        System.out.println("Environnement de test pret : " + dir);
        System.out.println("  Configuration : " + r.configFile);
        System.out.println("  Base Derby    : " + r.dbDir);
        System.out.println("  Port API      : " + port);
        System.out.println("  Fuseau        : " + (timezone == null ? "celui de Windows" : timezone));
        System.out.println("  Serveur test  : \"" + r.waiterName + "\"  mot de passe : " + r.waiterPassword);
        System.out.println("  (Administrator n'a pas de mot de passe dans la base de test : connexion caisse directe.)");
    }

    /** Resultat de la creation (utilise aussi par les tests automatiques). */
    public static final class Result {
        public File configFile;
        public File dbDir;
        public String waiterId;
        public String waiterName;
        public String waiterPassword;
    }

    public static Result create(File dir, boolean reset, int apiPort, String password, File projectRoot) throws Exception {
        File dbDir = new File(dir, "database");
        File configFile = new File(dir, CONFIG_NAME);

        refuseProductionDatabase(dbDir);
        // poste de test active (licence de test, cle generee pour cette execution)
        com.unicenta.pos.license.TestLicenses.installValidForTests(new File(dir.getParentFile(), "licence-test"));

        if (dbDir.exists()) {
            if (!reset) {
                throw new IllegalStateException("La base de test existe deja : " + dbDir
                        + "\nRelancez avec --reset pour la recreer.");
            }
            if (!new File(dir, MARKER).exists()) {
                throw new IllegalStateException("Refus de supprimer " + dbDir
                        + " : ce dossier n'a pas ete cree par cet outil (marqueur " + MARKER + " absent).");
            }
            deleteRecursively(dbDir);
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Impossible de creer " + dir);
        }
        writeText(new File(dir, MARKER), "Dossier cree par com.unicenta.pos.testenv.TestEnvironment. Base de TEST uniquement.\n");

        // ---- Configuration : on part des valeurs par defaut de la caisse ----
        System.setProperty("dirname.path", projectRoot.getAbsolutePath());
        AppConfig config = new AppConfig(configFile);
        config.load(); // fichier absent -> valeurs par defaut de AppConfig
        // uniCenta 5.0 : pilote Derby du depot Maven ; URL = db.URL + db.schema + db.options
        config.setProperty("db.driverlib", derbyJar().getAbsolutePath());
        config.setProperty("db.driver", "org.apache.derby.jdbc.EmbeddedDriver");
        config.setProperty("db.URL", "jdbc:derby:" + dbDir.getAbsolutePath().replace(File.separatorChar, '/') + ";create=true");
        config.setProperty("db.schema", "");
        config.setProperty("db.options", "");
        config.setProperty("db.user", "");
        config.setProperty("db.password", "");
        config.setProperty("machine.hostname", "TEST-POS");
        config.setProperty("machine.ticketsbag", "restaurant");
        // Imprimantes : tout s'affiche a l'ecran tant que le materiel n'est pas connu.
        // 1 = recus, 2 = cuisine (produits de test : champ Imprimante = 2)
        config.setProperty("machine.printer", "screen");
        config.setProperty("machine.printer.2", "screen");
        config.setProperty("machine.display", "screen");
        config.setProperty("user.language", "fr");
        config.setProperty("user.country", "FR");
        config.setProperty("user.variant", "");
        // API mobile
        config.setProperty("api.enabled", "true");
        config.setProperty("api.port", Integer.toString(apiPort));
        config.setProperty("api.bind", "0.0.0.0");
        config.save();

        // ---- Creation de la base avec le script officiel ----
        Session session = AppViewConnection.createSession(config);
        Result r = new Result();
        try {
            DataLogicSystem dlSystem = new DataLogicSystem();
            dlSystem.init(session);
            String script = dlSystem.getInitScript() + "-create.sql";
            BatchSentence batch = new BatchSentenceResource(session, script);
            batch.putParameter("APP_ID", Matcher.quoteReplacement(AppLocal.APP_ID));
            batch.putParameter("APP_NAME", Matcher.quoteReplacement(AppLocal.APP_NAME));
            batch.putParameter("APP_VERSION", Matcher.quoteReplacement(AppLocal.APP_VERSION));
            List<?> warnings = batch.list();
            if (!warnings.isEmpty()) {
                for (Object w : warnings) {
                    System.err.println("Avertissement SQL : " + w);
                }
                throw new IllegalStateException(warnings.size() + " erreur(s) lors de la creation de la base de test.");
            }
            if (!AppLocal.APP_VERSION.equals(dlSystem.findVersion())) {
                throw new IllegalStateException("Version de base inattendue apres creation.");
            }

            // Base de test : taxe standard a 10 % (uniCenta 5.0 cree 20 %), comme les tests d'origine
            try (java.sql.Statement st = session.getConnection().createStatement()) {
                st.executeUpdate("UPDATE taxes SET rate = 0.10 WHERE id = '001'");
            }
            r.waiterPassword = password != null ? password : randomPassword();
            r.waiterName = "Serveur Test";
            r.waiterId = insertDemoData(session.getConnection(), r.waiterName, r.waiterPassword);
            // comme la caisse au demarrage : modeles OLPOS (bon cuisine, addition) et numeros de commande
            com.unicenta.pos.sales.OrderNumberTemplates.run(dlSystem);
        } finally {
            session.close();
        }
        r.configFile = configFile;
        r.dbDir = dbDir;
        return r;
    }

    /** Donnees de demonstration : 3 categories, 7 produits, 1 serveur (role Employee). */
    private static String insertDemoData(Connection c, String waiterName, String waiterPassword) throws Exception {
        String[][] categories = {
            {"cat-entrees", "Entrees"},
            {"cat-plats", "Plats"},
            {"cat-boissons", "Boissons"},
        };
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO CATEGORIES(ID, NAME, CATSHOWNAME) VALUES (?, ?, 1)")) {
            for (String[] cat : categories) {
                ps.setString(1, cat[0]);
                ps.setString(2, cat[1]);
                ps.executeUpdate();
            }
        }
        // id, ref/code, nom, prix HT, categorie, taxe ('001' = Tax Standard 10%, '000' = exonere), cuisine
        Object[][] products = {
            {"p-salade", "T001", "Salade cesar", 7.50, "cat-entrees", "001", 1},
            {"p-soupe", "T002", "Soupe du jour", 5.00, "cat-entrees", "001", 1},
            {"p-steak", "T003", "Steak frites", 14.00, "cat-plats", "001", 1},
            {"p-poisson", "T004", "Poisson grille", 16.50, "cat-plats", "001", 1},
            {"p-eau", "T005", "Eau minerale", 2.00, "cat-boissons", "000", 0},
            {"p-cafe", "T006", "Cafe", 1.80, "cat-boissons", "001", 0},
            {"p-the", "T007", "The a la menthe", 2.20, "cat-boissons", "001", 0},
        };
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO products(ID, REFERENCE, CODE, NAME, PRICEBUY, PRICESELL, CATEGORY, TAXCAT, PRINTTO) "
                + "VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?)");
             PreparedStatement pc = c.prepareStatement("INSERT INTO PRODUCTS_CAT(PRODUCT, CATORDER) VALUES (?, ?)")) {
            int order = 0;
            for (Object[] p : products) {
                ps.setString(1, (String) p[0]);
                ps.setString(2, (String) p[1]);
                ps.setString(3, (String) p[1]);
                ps.setString(4, (String) p[2]);
                ps.setDouble(5, (Double) p[3]);
                ps.setString(6, (String) p[4]);
                ps.setString(7, (String) p[5]);
                // uniCenta 5.0 : champ Imprimante (PRINTTO) ; 2 = imprimante cuisine, 0 = pas de cuisine
                ps.setString(8, ((Integer) p[6]) == 1 ? "2" : "0");
                ps.executeUpdate();
                pc.setString(1, (String) p[0]);
                pc.setInt(2, order++);
                pc.executeUpdate();
            }
        }
        String waiterId = UUID.randomUUID().toString();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO PEOPLE(ID, NAME, APPPASSWORD, ROLE, VISIBLE) VALUES (?, ?, ?, '2', 1)")) {
            ps.setString(1, waiterId);
            ps.setString(2, waiterName);
            ps.setString(3, Hashcypher.hashString(waiterPassword));
            ps.executeUpdate();
        }
        if (!c.getAutoCommit()) {
            c.commit();
        }
        return waiterId;
    }

    /** Refuse de travailler sur la base de l'installation existante (~/unicentaopos.properties). */
    static void refuseProductionDatabase(File dbDir) throws IOException {
        File home = new File(System.getProperty("user.home"));
        File prodConfig = new File(home, "unicentaopos.properties");
        String target = dbDir.getCanonicalPath().toLowerCase();
        if (prodConfig.exists()) {
            Properties p = new Properties();
            try (InputStream in = new FileInputStream(prodConfig)) {
                p.load(in);
            }
            String url = p.getProperty("db.URL", "");
            if (url.startsWith("jdbc:derby:")) {
                String path = url.substring("jdbc:derby:".length());
                int semi = path.indexOf(';');
                if (semi >= 0) {
                    path = path.substring(0, semi);
                }
                String prod = new File(path).getCanonicalPath().toLowerCase();
                if (target.startsWith(prod) || prod.startsWith(target)) {
                    throw new IllegalStateException("Refus : " + dbDir + " correspond a la base de "
                            + prodConfig + " (installation existante).");
                }
            }
        }
        // Emplacements par defaut d'uniCenta et dossiers de donnees OLPOS installes : jamais pour les tests
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            for (String installed : new String[] {"OLPOS", "OLPOS5"}) {
                String f = new File(local, installed).getCanonicalPath().toLowerCase();
                if (target.startsWith(f)) {
                    throw new IllegalStateException("Refus : " + dbDir + " est le dossier de donnees d'OLPOS installe.");
                }
            }
        }
        for (String forbidden : new String[] {".unicenta", AppLocal.APP_ID + "-database"}) {
            String f = new File(home, forbidden).getCanonicalPath().toLowerCase();
            if (target.startsWith(f)) {
                throw new IllegalStateException("Refus : " + dbDir + " est un emplacement de base par defaut d'uniCenta.");
            }
        }
    }

    /** Jar du pilote Derby utilise par les tests (depot Maven local). */
    static File derbyJar() throws Exception {
        return new File(org.apache.derby.jdbc.EmbeddedDriver.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
    }

    private static String randomPassword() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static void deleteRecursively(File f) throws IOException {
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!f.delete()) {
            throw new IOException("Suppression impossible : " + f + " (la caisse de test est-elle encore ouverte ?)");
        }
    }

    private static void writeText(File f, String text) throws IOException {
        try (Writer w = new OutputStreamWriter(new java.io.FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(text);
        }
    }
}
