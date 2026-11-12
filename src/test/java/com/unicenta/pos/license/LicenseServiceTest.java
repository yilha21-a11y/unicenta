package com.unicenta.pos.license;

import com.olpos.license.License;
import com.olpos.license.LicenseCodec;
import com.olpos.license.LicenseEvaluation;
import com.olpos.license.LicenseEvaluation.Status;
import com.olpos.license.LicenseException;
import com.olpos.license.Serials;
import com.unicenta.data.loader.Session;
import com.unicenta.pos.forms.AppConfig;
import com.unicenta.pos.forms.AppViewConnection;
import com.unicenta.pos.forms.DataLogicSystem;
import com.unicenta.pos.testenv.TestEnvironment;
import java.io.File;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

/** Activation : valide, invalide, alteree, future, expiree, renouvellement, horloge, poste, postes. */
public class LicenseServiceTest {

    private static final LocalDate D = LocalDate.of(2027, 6, 1);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2027-06-15T12:00:00Z"));

    private LicenseService service(String name, DataLogicSystem db, String machine) {
        File dir = new File("build/test-work/licence/" + name + "-" + System.nanoTime());
        return TestLicenses.service(dir, db, now::get, machine);
    }

    private static String code(int rev, LocalDate from, LocalDate until) {
        return TestLicenses.code(TestLicenses.license("OLP-AAAA-BBBB-CCCC-DDDD-0", rev, from, until, 0, null));
    }

    @Test
    public void licenceValableAvecJoursRestantsEtSeuils() throws Exception {
        LicenseService s = service("valide", null, "POSTE");
        LicenseEvaluation e = s.install(code(1, D, D.plusDays(29)));   // fin : 30/06 23:59:59 UTC
        assertEquals(Status.VALID, e.getStatus());
        assertTrue(e.allowsNewSales());
        assertEquals(16, e.getDaysLeft());          // 15/06 12:00 -> 01/07 00:00 = 15,5 jours
        assertEquals(30, e.warningThreshold());
        now.set(Instant.parse("2027-06-26T00:00:00Z"));
        assertEquals(7, s.refresh().warningThreshold());
        now.set(Instant.parse("2027-06-30T12:00:00Z"));
        assertEquals(1, s.refresh().warningThreshold());
        assertEquals(1, s.getEvaluation().getDaysLeft());
    }

    @Test
    public void finDeJourneeUtcIncluse() throws Exception {
        LicenseService s = service("fin", null, "POSTE");
        s.install(code(1, D, D.plusDays(29)));
        now.set(Instant.parse("2027-06-30T23:59:59Z"));
        assertEquals("encore valable a 23:59:59 UTC", Status.VALID, s.refresh().getStatus());
        now.set(Instant.parse("2027-07-01T00:00:00Z"));
        assertEquals("expiree a minuit UTC", Status.EXPIRED, s.refresh().getStatus());
        assertFalse(s.getEvaluation().allowsNewSales());
    }

    @Test
    public void licenceFutureEtExpiree() throws Exception {
        LicenseService s = service("dates", null, "POSTE");
        assertEquals(Status.NOT_YET_VALID, s.install(code(1, D.plusMonths(1), D.plusMonths(2))).getStatus());
        LicenseService s2 = service("dates2", null, "POSTE");
        assertEquals(Status.EXPIRED, s2.install(code(1, D.minusYears(1), D.minusDays(1))).getStatus());
        assertFalse(s2.getEvaluation().allowsNewSales());
    }

    @Test
    public void codeInvalideAltereOuCleInconnueRefuses() throws Exception {
        LicenseService s = service("invalide", null, "POSTE");
        try {
            s.install("n'importe quoi");
            fail();
        } catch (LicenseException e) {
            assertEquals(LicenseException.Reason.MALFORMED, e.getReason());
        }
        // contenu altere (date de fin repoussee) : signature invalide
        String good = code(1, D, D.plusDays(29));
        String[] p = good.substring(LicenseCodec.PREFIX.length()).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(p[0]), "UTF-8").replace("2027-06-30", "2099-06-30");
        String altered = LicenseCodec.PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes("UTF-8")) + "." + p[1];
        try {
            s.install(altered);
            fail("licence alteree acceptee");
        } catch (LicenseException e) {
            assertEquals(LicenseException.Reason.BAD_SIGNATURE, e.getReason());
        }
        // signee par une autre cle (pirate) avec le meme identifiant de cle
        java.security.KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String forged = LicenseCodec.sign(TestLicenses.license("OLP-AAAA-BBBB-CCCC-DDDD-0", 1, D, D.plusYears(9), 0, null),
                other.getPrivate());
        try {
            s.install(forged);
            fail("licence forgee acceptee");
        } catch (LicenseException e) {
            assertEquals(LicenseException.Reason.BAD_SIGNATURE, e.getReason());
        }
        // aucune licence installee : pas de nouvelle vente
        assertEquals(Status.NONE, s.refresh().getStatus());
        assertFalse(s.getEvaluation().allowsNewSales());
        // code recopie avec espaces et retours a la ligne : accepte
        assertEquals(Status.VALID, s.install(good.substring(0, 40) + "\r\n  " + good.substring(40)).getStatus());
    }

    @Test
    public void renouvellementSansPerteEtAncienneRevisionRefusee() throws Exception {
        LicenseService s = service("renouvellement", null, "POSTE");
        s.install(code(1, D, D.plusDays(10)));
        now.set(Instant.parse("2027-07-01T00:00:00Z"));
        assertEquals(Status.EXPIRED, s.refresh().getStatus());
        LicenseEvaluation e = s.install(code(2, D.plusDays(11), D.plusYears(1)));
        assertEquals(Status.VALID, e.getStatus());
        assertEquals(2, e.getLicense().getRevision());
        try {
            s.install(code(1, D, D.plusDays(10)));
            fail("ancienne revision acceptee");
        } catch (LicenseException expected) {
            assertTrue(expected.getMessage().contains("plus ancienne"));
        }
        assertEquals(2, s.refresh().getLicense().getRevision());
    }

    @Test
    public void reculDHorlogeDetecte() throws Exception {
        LicenseService s = service("horloge", null, "POSTE");
        s.install(code(1, D, D.plusYears(1)));
        now.set(Instant.parse("2027-08-01T10:00:00Z"));
        assertEquals(Status.VALID, s.refresh().getStatus());
        now.set(Instant.parse("2027-08-01T09:55:00Z"));   // 5 min : tolerance (synchronisation)
        assertEquals(Status.VALID, s.refresh().getStatus());
        now.set(Instant.parse("2027-07-01T10:00:00Z"));   // un mois en arriere
        LicenseEvaluation e = s.refresh();
        assertEquals(Status.CLOCK_ROLLBACK, e.getStatus());
        assertFalse(e.allowsNewSales());
        now.set(Instant.parse("2027-08-02T10:00:00Z"));   // horloge corrigee
        assertEquals(Status.VALID, s.refresh().getStatus());
    }

    @Test
    public void licenceLieeAUnAutrePoste() throws Exception {
        String code = TestLicenses.code(TestLicenses.license("OLP-AAAA-BBBB-CCCC-EEEE-0", 1, D, D.plusYears(1), 0,
                "AAAA-BBBB-CCCC-DDDD"));
        assertEquals(Status.WRONG_MACHINE, service("autreposte", null, "ZZZZ-YYYY-XXXX-WWWW").install(code).getStatus());
        assertEquals(Status.VALID, service("bonposte", null, "AAAA-BBBB-CCCC-DDDD").install(code).getStatus());
        // code de poste : stable, non reversible, sans l'identifiant d'origine
        String c = LicenseService.codeFor("1234-abcd-guid");
        assertEquals(c, LicenseService.codeFor("1234-ABCD-GUID"));
        assertTrue(c.matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}"));
        assertFalse(c.contains("1234"));
    }

    @Test
    public void nombreDePostesControleDansLaBasePartagee() throws Exception {
        File root = new File(".").getCanonicalFile();
        TestEnvironment.Result env = TestEnvironment.create(new File(root, "build/test-work/licence-postes"), true, 0,
                "lic-" + System.nanoTime(), root);
        AppConfig config = new AppConfig(env.configFile);
        config.load();
        Session session = AppViewConnection.createSession(config);
        try {
            DataLogicSystem db = new DataLogicSystem();
            db.init(session);
            String code = TestLicenses.code(TestLicenses.license("OLP-AAAA-BBBB-CCCC-FFFF-0", 1, D, D.plusYears(1), 2, null));
            LicenseService a = service("posteA", db, "POSTE-A");
            LicenseService b = service("posteB", db, "POSTE-B");
            LicenseService c = service("posteC", db, "POSTE-C");
            assertEquals(Status.VALID, a.install(code).getStatus());
            assertEquals("licence partagee par la base", Status.VALID, b.refresh().getStatus());
            assertEquals(Status.TOO_MANY_STATIONS, c.refresh().getStatus());
            assertFalse(c.getEvaluation().allowsNewSales());
            a.freeStation("POSTE-B");
            assertEquals(Status.VALID, c.refresh().getStatus());
            assertEquals(Status.TOO_MANY_STATIONS, b.refresh().getStatus());
            // un poste inactif depuis plus de 30 jours libere sa place
            now.set(now.get().plusSeconds(31L * 86400));
            assertEquals(Status.VALID, a.refresh().getStatus());
            assertEquals(Status.VALID, b.refresh().getStatus());
        } finally {
            session.close();
        }
    }

    @Test
    public void numerosDeSerieUniquesEtControles() {
        SecureRandom r = new SecureRandom();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String s = Serials.newSerial(r);
            assertTrue(s, Serials.isWellFormed(s));
            assertTrue(seen.add(s));
        }
        String s = Serials.newSerial(r);
        char last = s.charAt(s.length() - 1);
        String typo = s.substring(0, s.length() - 1) + (last == '0' ? '1' : '0');
        assertFalse("faute de frappe detectee", Serials.isWellFormed(typo));
    }

    @Test
    public void formeCanoniqueStricte() throws Exception {
        License l = TestLicenses.license("OLP-AAAA-BBBB-CCCC-DDDD-0", 1, D, D.plusDays(1), 0, null);
        assertEquals(l.canonical(), License.parseCanonical(l.canonicalBytes()).canonical());
        try {
            License.parseCanonical((l.canonical() + "extra=1\n").getBytes("UTF-8"));
            fail();
        } catch (LicenseException expected) {
            // champ en plus refuse
        }
    }
}
