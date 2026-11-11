//    OLPOS (derive d'uniCenta oPOS) - API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.api;

import com.unicenta.pos.forms.AppProperties;

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
        // Les commandes par table n'ont de sens que si la caisse est en mode restaurant
        restaurantMode = "restaurant".equals(trim(props.getProperty("machine.ticketsbag")));
    }

    private final boolean restaurantMode;

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
