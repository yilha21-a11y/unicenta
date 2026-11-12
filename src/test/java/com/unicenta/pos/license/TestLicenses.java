package com.unicenta.pos.license;

import com.olpos.license.License;
import com.olpos.license.LicenseCodec;
import com.unicenta.pos.forms.DataLogicSystem;
import java.io.File;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Licences de TEST : cle Ed25519 generee a chaque execution, jamais ecrite sur
 * disque, jamais celle d'OLPOS License Manager.
 */
public final class TestLicenses {

    public static final KeyPair KEYS;
    public static final String KEY_ID;

    static {
        try {
            KEYS = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            KEY_ID = LicenseCodec.keyId(KEYS.getPublic());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private TestLicenses() {
    }

    public static Map<String, PublicKey> trusted() {
        return Collections.singletonMap(KEY_ID, KEYS.getPublic());
    }

    public static License license(String serial, int revision, LocalDate from, LocalDate until, int maxStations,
            String machine, String... features) {
        return new License(serial, revision, "Client test", "Restaurant test", from, until,
                new LinkedHashSet<>(Arrays.asList(features.length == 0
                        ? new String[] {License.FEATURE_SALES, License.FEATURE_MOBILE, License.FEATURE_KITCHEN} : features)),
                maxStations, machine, Instant.parse("2026-01-01T00:00:00Z"), KEY_ID);
    }

    public static String code(License l) {
        try {
            return LicenseCodec.sign(l, KEYS.getPrivate());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static LicenseService service(File dir, DataLogicSystem db, Supplier<Instant> clock, String machine) {
        dir.mkdirs();
        return new LicenseService(trusted(), dir, db, clock, machine, ZoneId.of("America/Vancouver"));
    }

    /**
     * Installe, pour toute la JVM de test, une licence valable (toutes fonctions)
     * afin que les tests de la caisse et de l'API fonctionnent comme sur un poste active.
     */
    public static synchronized void installValidForTests(File dir) {
        try {
            LicenseService s = service(dir, null, Instant::now, "TEST-POSTE");
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            s.install(code(license("OLP-TEST-TEST-TEST-TEST-0", 1, today.minusDays(1), today.plusDays(365), 0, null)));
            LicenseService.install(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static SecureRandom random() {
        return new SecureRandom();
    }
}
