//    OLPOS (derive d'uniCenta oPOS) - API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.api;

import com.unicenta.pos.forms.AppProperties;
import java.io.File;

/**
 * Reglages de l'API mobile, lus dans le fichier de configuration de la caisse
 * (unicentaopos.properties). Aucune adresse ni mot de passe n'est code en dur :
 * chaque restaurant regle ses valeurs, sinon les valeurs par defaut ci-dessous
 * s'appliquent.
 *
 * <pre>
 * api.enabled=false            # l'API est DESACTIVEE par defaut
 * api.bind=0.0.0.0             # interface reseau d'ecoute (0.0.0.0 = toutes)
 * api.port=8095                # port TCP
 * api.session.minutes=240      # duree d'inactivite avant expiration du jeton
 * api.login.maxattempts=5      # echecs de connexion avant blocage temporaire
 * api.login.lockseconds=60     # duree du blocage
 * api.users.list=true          # le telephone peut afficher la liste des serveurs
 * api.threads=4                # requetes traitees en parallele
 *
 * api.https.enabled=false      # HTTPS (mots de passe et jetons chiffres), voir docs/HTTPS.md
 * api.https.keystore=          # certificat du PC (PKCS12) ; vide = dossier de donnees\https\olpos-https.p12
 * api.https.password=          # mot de passe du fichier ; vide = contenu de https\password.txt
 * api.https.caport=8094        # page HTTP (sans mot de passe) pour installer l'autorite sur le telephone ; 0 = aucune
 * </pre>
 */
public final class ApiConfig {

    public static final String PERMISSION_ACCESS = "mobile.Access";

    private final boolean enabled;
    private final String bind;
    private final int port;
    private final int sessionMinutes;
    private final int maxLoginAttempts;
    private final int lockSeconds;
    private final boolean listUsers;
    private final int threads;

    public ApiConfig(AppProperties props) {
        enabled = Boolean.parseBoolean(trim(props.getProperty("api.enabled")));
        String b = trim(props.getProperty("api.bind"));
        bind = b == null || b.isEmpty() ? "0.0.0.0" : b;
        port = intValue(props, "api.port", 8095, 0, 65535);
        sessionMinutes = intValue(props, "api.session.minutes", 240, 1, 24 * 60);
        maxLoginAttempts = intValue(props, "api.login.maxattempts", 5, 1, 100);
        lockSeconds = intValue(props, "api.login.lockseconds", 60, 1, 3600);
        String lu = trim(props.getProperty("api.users.list"));
        listUsers = lu == null || Boolean.parseBoolean(lu);
        threads = intValue(props, "api.threads", 4, 1, 32);
        httpsEnabled = Boolean.parseBoolean(trim(props.getProperty("api.https.enabled")));
        String ks = trim(props.getProperty("api.https.keystore"));
        File httpsDir = new File(com.unicenta.pos.forms.AppConfig.dataDirPath(), "https");
        httpsKeystore = ks == null || ks.isEmpty() ? new File(httpsDir, "olpos-https.p12") : new File(ks);
        String pw = props.getProperty("api.https.password");
        httpsPassword = pw == null || pw.trim().isEmpty() ? null : pw.trim();
        httpsPasswordFile = new File(httpsKeystore.getParentFile(), "password.txt");
        httpsCaFile = new File(httpsKeystore.getParentFile(), "olpos-ca.cer");
        caPort = intValue(props, "api.https.caport", 8094, 0, 65535);
        // Les commandes par table n'ont de sens que si la caisse est en mode restaurant
        restaurantMode = "restaurant".equals(trim(props.getProperty("machine.ticketsbag")));
    }

    private final boolean restaurantMode;
    private final boolean httpsEnabled;
    private final File httpsKeystore;
    private final String httpsPassword;
    private final File httpsPasswordFile;
    private final File httpsCaFile;
    private final int caPort;

    public boolean isHttpsEnabled() {
        return httpsEnabled;
    }

    public File getHttpsKeystore() {
        return httpsKeystore;
    }

    /** Mot de passe du certificat : api.https.password, sinon le fichier password.txt a cote. */
    public char[] getHttpsPassword() throws java.io.IOException {
        if (httpsPassword != null) {
            return httpsPassword.toCharArray();
        }
        if (!httpsPasswordFile.isFile()) {
            throw new java.io.IOException("Mot de passe du certificat HTTPS introuvable (" + httpsPasswordFile + ")");
        }
        return new String(java.nio.file.Files.readAllBytes(httpsPasswordFile.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).trim().toCharArray();
    }

    /** Certificat de l'autorite locale a installer sur les telephones. */
    public File getHttpsCaFile() {
        return httpsCaFile;
    }

    public int getCaPort() {
        return caPort;
    }

    /** true si la caisse utilise le plan de salle (machine.ticketsbag=restaurant). */
    public boolean isRestaurantMode() {
        return restaurantMode;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static int intValue(AppProperties props, String key, int def, int min, int max) {
        String v = trim(props.getProperty(key));
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            int i = Integer.parseInt(v);
            if (i < min || i > max) {
                throw new IllegalArgumentException(key + " doit etre entre " + min + " et " + max + " (valeur : " + v + ")");
            }
            return i;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " n'est pas un nombre entier (valeur : " + v + ")");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getBind() {
        return bind;
    }

    public int getPort() {
        return port;
    }

    public int getSessionMinutes() {
        return sessionMinutes;
    }

    public int getMaxLoginAttempts() {
        return maxLoginAttempts;
    }

    public int getLockSeconds() {
        return lockSeconds;
    }

    public boolean isListUsers() {
        return listUsers;
    }

    public int getThreads() {
        return threads;
    }
}
