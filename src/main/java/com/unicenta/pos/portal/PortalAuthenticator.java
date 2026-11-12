//    OLPOS (derive d'uniCenta oPOS) - verification des connexions au portail Patron
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.portal;

import com.unicenta.basic.BasicException;
import com.unicenta.pos.api.Json;
import com.unicenta.pos.forms.AppUser;
import com.unicenta.pos.forms.DataLogicSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * La CAISSE verifie les identifiants du patron (authentification deleguee) :
 * <ol>
 *   <li>dechiffre le bloc du navigateur (le portail ne l'a jamais lu) ;</li>
 *   <li>controle le defi a usage unique (pas de rejeu) ;</li>
 *   <li>retrouve le compte DANS CE restaurant, refuse s'il n'a pas de mot de
 *       passe, verifie le mot de passe avec la methode de la caisse
 *       ({@link AppUser#authenticate(String)}) - aucune copie du mot de passe ;</li>
 *   <li>exige l'autorisation "portail" (role Administrateur ou portal.Access)
 *       et le code de double authentification (secret garde par la caisse) ;</li>
 *   <li>limite les essais (5 echecs => 15 minutes de blocage) ;</li>
 *   <li>renvoie une preuve signee, valable 60 s, liee a la demande.</li>
 * </ol>
 */
public final class PortalAuthenticator {

    public static final String PERMISSION = "portal.Access";
    /** Marqueur du role Administrateur d'uniCenta 5.0 (absent du role Manager). */
    public static final String ADMIN_MARKER = "com.unicenta.pos.admin.RolesPanel";
    static final int MAX_FAILURES = 5;
    static final long LOCK_MS = 15 * 60_000L;

    private final PortalLink link;
    private final DataLogicSystem dl;
    private final LongSupplier clock;
    private final Map<String, long[]> failures = new HashMap<>();   // uid -> {nombre, debut, bloque jusqu'a}
    private final Map<String, Long> usedSteps = new HashMap<>();      // uid -> derniere periode TOTP acceptee

    public PortalAuthenticator(PortalLink link, DataLogicSystem dl, LongSupplier clock) {
        this.link = link;
        this.dl = dl;
        this.clock = clock;
    }

    /** Compte autorise sur le portail : mot de passe, droit, double authentification activee. */
    public static boolean hasPortalRight(AppUser u, DataLogicSystem dl) {
        u.fillPermissions(dl);
        return u.hasPermission(PERMISSION) || u.hasPermission(ADMIN_MARKER);
    }

    static boolean hasPassword(AppUser u) {
        String p = u.getPassword();
        return p != null && !p.isEmpty() && !p.startsWith("empty:");
    }

    /** Etat des comptes pour le portail (empreintes opaques, pas de mot de passe). */
    public List<Object> usersState() throws BasicException {
        List<Object> out = new ArrayList<>();
        for (Object o : dl.listPeopleVisible()) {
            AppUser u = (AppUser) o;
            if (link.totpSecret(u.getId()) == null) {
                continue;   // jamais autorise sur le portail : inutile de l'envoyer
            }
            boolean eligible = hasPassword(u) && hasPortalRight(u, dl);
            out.add(Json.obj("uid", u.getId(), "name", u.getName(),
                    "stamp", link.stamp(u.getId(), u.getPassword(), u.getRole(), eligible), "eligible", eligible));
        }
        return out;
    }

    /** Traite une demande ; renvoie la reponse a poster au portail. */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> handle(String requestId, String nonce, String ciphertext) {
        Map<String, Object> creds;
        try {
            creds = (Map<String, Object>) Json.parse(link.decrypt(ciphertext));
        } catch (Exception e) {
            return deny("invalid");
        }
        if (!requestId.equals(creds.get("cid")) || !nonce.equals(creds.get("nonce"))) {
            return deny("invalid");   // bloc rejoue ou destine a une autre demande
        }
        String name = creds.get("user") instanceof String ? ((String) creds.get("user")).trim() : "";
        String password = creds.get("password") instanceof String ? (String) creds.get("password") : "";
        String code = creds.get("totp") instanceof String ? (String) creds.get("totp") : "";
        AppUser user;
        try {
            user = find(name);
        } catch (BasicException e) {
            return deny("invalid");
        }
        if (user == null) {
            return deny("invalid");
        }
        String uid = user.getId();
        if (locked(uid)) {
            return deny("locked");
        }
        if (!hasPassword(user)) {
            return deny("no_password");
        }
        if (!user.authenticate(password)) {
            fail(uid);
            return deny("invalid");
        }
        String secret = link.totpSecret(uid);
        if (secret == null || !hasPortalRight(user, dl)) {
            return deny("not_enrolled");
        }
        long now = clock.getAsLong();
        long step = Totp.matchStep(secret, code, now);
        Long last = usedSteps.get(uid);
        if (step < 0 || (last != null && step <= last)) {
            fail(uid);
            return deny("invalid");
        }
        usedSteps.put(uid, step);   // un meme code ne sert qu'une fois
        failures.remove(uid);
        Map<String, Object> a = Json.obj("v", 1L, "rid", link.restaurantId(), "req", requestId, "nonce", nonce,
                "uid", uid, "name", user.getName(), "stamp", link.stamp(uid, user.getPassword(), user.getRole(), true),
                "iat", now, "exp", now + 60_000L);
        String text = Json.write(a);
        try {
            return Json.obj("ok", true, "assertion", text, "signature", link.sign(text));
        } catch (java.security.GeneralSecurityException e) {
            return deny("invalid");
        }
    }

    private AppUser find(String name) throws BasicException {
        AppUser found = null;
        for (Object o : dl.listPeopleVisible()) {
            AppUser u = (AppUser) o;
            if (u.getName().equalsIgnoreCase(name)) {
                if (found != null) {
                    return null;   // nom ambigu : refuse plutot que deviner
                }
                found = u;
            }
        }
        return found;
    }

    private boolean locked(String uid) {
        long[] f = failures.get(uid);
        return f != null && f[2] > clock.getAsLong();
    }

    private void fail(String uid) {
        long now = clock.getAsLong();
        long[] f = failures.get(uid);
        if (f == null || now - f[1] > LOCK_MS) {
            f = new long[] {0, now, 0};
            failures.put(uid, f);
        }
        f[0]++;
        if (f[0] >= MAX_FAILURES) {
            f[2] = now + LOCK_MS;
        }
    }

    private static Map<String, Object> deny(String reason) {
        return Json.obj("ok", false, "reason", reason);
    }
}
