package com.unicenta.pos.api;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class ApiSessionsTest {

    private long now;
    private ApiSessions sessions;
    private final Set<String> perms = Collections.singleton(ApiConfig.PERMISSION_ACCESS);

    @Before
    public void setUp() {
        now = 1_000_000L;
        sessions = new ApiSessions(60_000L, 3, 30_000L, new ApiSessions.Clock() {
            @Override
            public long millis() {
                return now;
            }
        });
    }

    @Test
    public void jetonsUniquesEtLongs() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String t = sessions.create("u", "U", "2", perms).getToken();
            assertTrue("jeton trop court", t.length() >= 40);
            assertTrue("jeton en double", tokens.add(t));
        }
    }

    @Test
    public void expirationGlissante() {
        String t = sessions.create("u", "U", "2", perms).getToken();
        now += 59_000;
        assertNotNull(sessions.validate(t));       // prolonge de 60 s
        now += 59_000;
        assertNotNull(sessions.validate(t));
        now += 60_000;
        assertNull("doit avoir expire", sessions.validate(t));
        assertNull("reste invalide", sessions.validate(t));
    }

    @Test
    public void jetonInconnuOuVide() {
        assertNull(sessions.validate(null));
        assertNull(sessions.validate(""));
        assertNull(sessions.validate("inconnu"));
    }

    @Test
    public void deconnexion() {
        String t = sessions.create("u", "U", "2", perms).getToken();
        sessions.invalidate(t);
        assertNull(sessions.validate(t));
    }

    @Test
    public void blocageApresTropDEchecs() {
        assertFalse(sessions.recordFailure("u"));
        assertFalse(sessions.recordFailure("u"));
        assertTrue(sessions.recordFailure("u"));      // 3e echec -> bloque
        assertEquals(30, sessions.lockRemainingSeconds("u"));
        assertEquals("un autre compte n'est pas bloque", 0, sessions.lockRemainingSeconds("v"));
        now += 29_500;
        assertEquals(1, sessions.lockRemainingSeconds("u"));
        now += 600;
        assertEquals(0, sessions.lockRemainingSeconds("u"));
        // compteur remis a zero apres le blocage
        assertFalse(sessions.recordFailure("u"));
    }

    @Test
    public void connexionReussieRemetLeCompteurAZero() {
        sessions.recordFailure("u");
        sessions.recordFailure("u");
        ApiSessions.ApiSession s1 = sessions.create("u", "U", "2", perms);
        assertFalse(sessions.recordFailure("u"));
        assertFalse(sessions.recordFailure("u"));
        ApiSessions.ApiSession s2 = sessions.create("u", "U", "2", perms);
        assertNotSame(s1.getToken(), s2.getToken());
    }

    @Test
    public void permissionsNonModifiables() {
        ApiSessions.ApiSession s = sessions.create("u", "U", "2", new HashSet<>(perms));
        try {
            s.getPermissions().add("sales.Total");
            throw new AssertionError("les permissions ne doivent pas etre modifiables");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
