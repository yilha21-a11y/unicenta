//    OLPOS Portail Patron - ligne de commande
//    Licence GPL v3 (voir ../LICENSE.md).
package com.olpos.portal;

import java.io.File;
import java.util.Map;

/**
 * <pre>
 * java -jar olpos-portal.jar serve [--data DOSSIER] [--bind 127.0.0.1] [--port 8090] [--https-proxy]
 * java -jar olpos-portal.jar create-restaurant "Nom du restaurant" identifiant [--data DOSSIER]
 * java -jar olpos-portal.jar pairing-code identifiant [--data DOSSIER]
 * </pre>
 * --https-proxy : le portail est derriere un proxy HTTPS (cookies "Secure",
 * HSTS, adresse du client lue dans X-Forwarded-For).
 */
public final class PortalMain {

    private PortalMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage : serve | create-restaurant \"Nom\" identifiant | pairing-code identifiant");
            return;
        }
        File data = new File(opt(args, "--data", "portal-data"));
        data.mkdirs();
        PortalDb db = new PortalDb(data);
        PortalService portal = new PortalService(db, System::currentTimeMillis);
        switch (args[0]) {
            case "create-restaurant": {
                Map<String, Object> r = portal.createRestaurant(args[1], args[2]);
                System.out.println("Restaurant cree : " + r.get("name") + " (identifiant " + r.get("slug") + ")");
                System.out.println("Code d'association (usage unique, 30 min) : " + r.get("pairingCode"));
                db.close();
                return;
            }
            case "pairing-code": {
                String rid = portal.ridForSlug(args[1]);
                if (rid == null) {
                    System.out.println("Restaurant inconnu : " + args[1]);
                } else {
                    System.out.println("Code d'association (usage unique, 30 min) : " + portal.newPairingCode(rid));
                }
                db.close();
                return;
            }
            case "serve": {
                boolean proxy = has(args, "--https-proxy");
                PortalServer server = new PortalServer(portal, proxy, proxy);
                String bind = opt(args, "--bind", "127.0.0.1");
                server.start(bind, Integer.parseInt(opt(args, "--port", "8090")));
                System.out.println("Portail OLPOS demarre sur http://" + bind + ":" + server.port() + "/"
                        + (proxy ? " (derriere un proxy HTTPS)" : " (local, sans HTTPS)"));
                return;
            }
            default:
                System.out.println("Commande inconnue : " + args[0]);
        }
    }

    static String opt(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return def;
    }

    static boolean has(String[] args, String name) {
        for (String a : args) {
            if (a.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
