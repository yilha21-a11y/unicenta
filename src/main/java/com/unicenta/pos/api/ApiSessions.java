//    OLPOS (derive d'uniCenta oPOS) - API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.api;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Sessions des telephones connectes (en memoire) et protection contre les
 * essais repetes de mot de passe.
 *
 * <ul>
 *   <li>Jeton : 32 octets aleatoires (SecureRandom), jamais ecrit dans les journaux.</li>
 *   <li>Expiration glissante : chaque requete valide prolonge la session.</li>
 *   <li>Apres N echecs pour un meme serveur, connexion bloquee pendant X secondes.</li>
 * </ul>
 * Thread-safe (toutes les methodes publiques sont synchronisees).
 */
public final class ApiSessions {

    /** Horloge injectable pour les tests. */
    public interface Clock {
        long millis();
    }

    /** Session d'un serveur connecte depuis un telephone. */
    public static final class ApiSession implements KitchenService.Actor {
        private final String token;
        private final String userId;
        private final String userName;
        private final String roleId;
        private final String passwordStamp;          // mot de passe (hache) au moment de la connexion
        private volatile Set<String> permissions;
        private volatile long checkedAt;             // derniere verification des droits dans la base
        private volatile long expiresAt;

        ApiSession(String token, String userId, String userName, String roleId, Set<String> permissions,
                String passwordStamp, long now, long expiresAt) {
            this.token = token;
            this.userId = userId;
            this.userName = userName;
            this.roleId = roleId;
            this.permissions = Collections.unmodifiableSet(new java.util.LinkedHashSet<>(permissions));
            this.passwordStamp = passwordStamp;
            this.checkedAt = now;
            this.expiresAt = expiresAt;
        }

        /** Mot de passe enregistre lors de la connexion (detection d'un changement). */
        public String getPasswordStamp() {
            return passwordStamp;
        }

        public long getCheckedAt() {
            return checkedAt;
        }

        /** Droits relus dans la base (OLPOS : un retrait de droits s'applique tout de suite). */
        void refresh(Set<String> current, long now) {
            this.permissions = Collections.unmodifiableSet(new java.util.LinkedHashSet<>(current));
            this.checkedAt = now;
        }

        public String getToken() {
            return token;
        }

        @Override
        public String getUserId() {
            return userId;
        }

        @Override
        public String getUserName() {
            return userName;
        }

        public String getRoleId() {
            return roleId;
        }

        public Set<String> getPermissions() {
            return permissions;
        }

        @Override
        public boolean hasPermission(String p) {
            return permissions.contains(p);
        }

        @Override
        public com.unicenta.pos.sales.restaurant.TableLocks.Source getSource() {
            return com.unicenta.pos.sales.restaurant.TableLocks.Source.MOBILE;
        }

        public long getExpiresAt() {
            return expiresAt;
        }
    }

    private final Map<String, ApiSession> sessions = new HashMap<>();
    private final Map<String, int[]> failures = new HashMap<>();     // userId -> {nombre d'echecs}
    private final Map<String, Long> lockedUntil = new HashMap<>();   // userId -> fin du blocage
    private final SecureRandom random = new SecureRandom();
    private final long ttlMillis;
    private final int maxAttempts;
    private final long lockMillis;
    private final Clock clock;

    public ApiSessions(ApiConfig config) {
        this(config.getSessionMinutes() * 60_000L, config.getMaxLoginAttempts(), config.getLockSeconds() * 1000L,
                new Clock() {
                    @Override
                    public long millis() {
                        return System.currentTimeMillis();
                    }
                });
    }

    public ApiSessions(long ttlMillis, int maxAttempts, long lockMillis, Clock clock) {
        this.ttlMillis = ttlMillis;
        this.maxAttempts = maxAttempts;
        this.lockMillis = lockMillis;
        this.clock = clock;
    }

    public long getTtlMillis() {
        return ttlMillis;
    }

    /** Secondes restantes de blocage pour ce serveur (0 = pas bloque). */
    public synchronized long lockRemainingSeconds(String userId) {
        Long until = lockedUntil.get(userId);
        if (until == null) {
            return 0;
        }
        long remaining = until - clock.millis();
        if (remaining <= 0) {
            lockedUntil.remove(userId);
            failures.remove(userId);
            return 0;
        }
        return (remaining + 999) / 1000;
    }

    /** Enregistre un echec ; renvoie true si le compte vient d'etre bloque. */
    public synchronized boolean recordFailure(String userId) {
        int[] count = failures.get(userId);
        if (count == null) {
            count = new int[1];
            failures.put(userId, count);
        }
        count[0]++;
        if (count[0] >= maxAttempts) {
            lockedUntil.put(userId, clock.millis() + lockMillis);
            failures.remove(userId);
            return true;
        }
        return false;
    }

    public synchronized ApiSession create(String userId, String userName, String roleId, Set<String> permissions) {
        return create(userId, userName, roleId, permissions, null);
    }

    public synchronized ApiSession create(String userId, String userName, String roleId, Set<String> permissions,
            String passwordStamp) {
        failures.remove(userId);
        lockedUntil.remove(userId);
        purgeExpired();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long now = clock.millis();
        ApiSession s = new ApiSession(token, userId, userName, roleId, permissions, passwordStamp, now, now + ttlMillis);
        sessions.put(token, s);
        return s;
    }

    /** Renvoie la session valide associee au jeton (et la prolonge), sinon null. */
    public synchronized ApiSession validate(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        ApiSession s = sessions.get(token);
        if (s == null) {
            return null;
        }
        long now = clock.millis();
        if (now >= s.expiresAt) {
            sessions.remove(token);
            return null;
        }
        s.expiresAt = now + ttlMillis;
        return s;
    }

    public long now() {
        return clock.millis();
    }

    /** Ferme toutes les sessions d'un serveur (droits retires). */
    public synchronized int invalidateUser(String userId) {
        int n = 0;
        for (Iterator<ApiSession> it = sessions.values().iterator(); it.hasNext();) {
            if (it.next().userId.equals(userId)) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    public synchronized void invalidate(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public synchronized int activeCount() {
        purgeExpired();
        return sessions.size();
    }

    private void purgeExpired() {
        long now = clock.millis();
        for (Iterator<ApiSession> it = sessions.values().iterator(); it.hasNext();) {
            if (now >= it.next().expiresAt) {
                it.remove();
            }
        }
    }
}
