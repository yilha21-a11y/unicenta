package com.olpos.licensemanager;

import com.olpos.license.License;
import com.olpos.license.LicenseCodec;
import com.olpos.license.LicenseException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.PublicKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

public class ManagerStoreTest {

    private static final char[] PW = "Mot-de-passe-1".toCharArray();

    private static File dir(String name) {
        File d = new File(System.getProperty("olpos.lm.dir", "target/test-lm-data"), name + "-" + System.nanoTime());
        d.mkdirs();
        return d;
    }

    private static Map<String, PublicKey> trusted(ManagerStore s) {
        return Collections.singletonMap(s.getKeyId(), s.getPublicKey());
    }

    @Test
    public void cleChiffreeEtMotDePasseExige() throws Exception {
        File d = dir("coffre");
        ManagerStore s = ManagerStore.create(d, PW);
        byte[] raw = Files.readAllBytes(s.getFile().toPath());
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        assertFalse("aucune donnee en clair", text.contains("privateKey") || text.contains("OLPOS-LM-1"));
        try {
            ManagerStore.open(d, "mauvais-mot-2".toCharArray());
            fail();
        } catch (Vault.WrongPasswordException expected) {
            // refuse
        }
        raw[raw.length - 3] ^= 1;   // fichier altere
        try {
            Vault.decrypt(raw, PW);
            fail();
        } catch (Vault.WrongPasswordException expected) {
            // GCM detecte la modification
        }
        assertEquals(s.getKeyId(), ManagerStore.open(d, PW).getKeyId());
        try {
            ManagerStore.create(dir("faible"), "court".toCharArray());
            fail();
        } catch (IllegalArgumentException expected) {
            // mot de passe trop faible
        }
    }

    @Test
    public void licenceAccepteeParOlposEtRenouvellementAvecHistorique() throws Exception {
        ManagerStore s = ManagerStore.create(dir("licences"), PW);
        Map<String, Object> c = s.saveClient(null, "SARL Dupont", "Chez Marie", "06 00 00 00 00", "");
        Map<String, Object> l1 = s.issue((String) c.get("id"), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31),
                new LinkedHashSet<>(Arrays.asList(License.FEATURE_MOBILE)), 2, null, "premiere");
        License v1 = LicenseCodec.verify((String) l1.get("code"), trusted(s));   // meme verification qu'OLPOS
        assertEquals("Chez Marie", v1.getRestaurant());
        assertTrue(v1.hasFeature(License.FEATURE_SALES));
        assertTrue(v1.hasFeature(License.FEATURE_MOBILE));
        assertFalse(v1.hasFeature(License.FEATURE_KITCHEN));
        assertEquals(2, v1.getMaxStations());

        Map<String, Object> l2 = s.renew((String) l1.get("serial"), LocalDate.of(2028, 1, 1), LocalDate.of(2028, 12, 31),
                new LinkedHashSet<>(Arrays.asList(License.FEATURE_MOBILE, License.FEATURE_KITCHEN)), 2,
                "AAAA-BBBB-CCCC-DDDD", "transfert nouveau PC");
        License v2 = LicenseCodec.verify((String) l2.get("code"), trusted(s));
        assertEquals(v1.getSerial(), v2.getSerial());
        assertEquals(2, v2.getRevision());
        assertEquals("AAAA-BBBB-CCCC-DDDD", v2.getMachine());
        assertEquals(2, s.history(v1.getSerial()).size());
        assertEquals("une ligne par numero de serie", 1, s.latestLicenses().size());
        // relu apres reouverture (donnees enregistrees chiffrees)
        ManagerStore again = ManagerStore.open(s.getFile().getParentFile(), PW);
        assertEquals(2, again.history(v1.getSerial()).size());
        // une autre cle (autre gestionnaire) n'est pas reconnue
        ManagerStore other = ManagerStore.create(dir("autre"), PW);
        try {
            LicenseCodec.verify((String) l2.get("code"), trusted(other));
            fail();
        } catch (LicenseException e) {
            assertEquals(LicenseException.Reason.UNKNOWN_KEY, e.getReason());
        }
    }

    @Test
    public void etatsEtExportCsv() throws Exception {
        ManagerStore s = ManagerStore.create(dir("csv"), PW);
        String id = (String) s.saveClient(null, "Client; avec point-virgule", "=Resto", "", "").get("id");
        s.issue(id, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31), new LinkedHashSet<String>(), 0, null, "");
        s.issue(id, LocalDate.of(2026, 1, 1), LocalDate.of(2030, 12, 31), new LinkedHashSet<String>(), 0, null, "");
        s.issue(id, LocalDate.of(2031, 1, 1), LocalDate.of(2031, 12, 31), new LinkedHashSet<String>(), 0, null, "");
        Instant now = Instant.parse("2027-06-01T00:00:00Z");
        int active = 0;
        int future = 0;
        int expired = 0;
        for (Map<String, Object> l : s.latestLicenses()) {
            switch (ManagerStore.stateOf(l, now)) {
                case ACTIVE: active++; break;
                case FUTURE: future++; break;
                default: expired++; break;
            }
        }
        assertEquals(Arrays.asList(1, 1, 1), Arrays.asList(active, future, expired));
        File csv = new File(s.getFile().getParentFile(), "export.csv");
        s.exportCsv(csv, s.latestLicenses(), now);
        List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        assertEquals(4, lines.size());
        assertTrue(lines.get(1), lines.get(1).contains("\"Client; avec point-virgule\""));
        assertTrue("pas de formule", lines.get(1).contains(";'=Resto;"));
    }

    @Test
    public void sauvegardesCleEtDonnees() throws Exception {
        File d = dir("sauvegarde");
        ManagerStore s = ManagerStore.create(d, PW);
        String id = (String) s.saveClient(null, "Client", "Resto", "", "").get("id");
        Map<String, Object> l = s.issue(id, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31),
                new LinkedHashSet<String>(), 0, null, "");

        // cle privee : sauvegarde chiffree separee, restauree sur un nouveau PC
        File key = new File(d, "cle.olposkey");
        char[] phrase = "Phrase-de-passe-9".toCharArray();
        s.exportPrivateKey(key, phrase);
        assertFalse(new String(Files.readAllBytes(key.toPath()), StandardCharsets.ISO_8859_1).contains("privateKey"));
        ManagerStore restored = ManagerStore.createFromKeyBackup(dir("nouveau-pc"), "Autre-mot-3".toCharArray(), key, phrase);
        assertEquals("meme cle", s.getKeyId(), restored.getKeyId());
        String rid = (String) restored.saveClient(null, "Client", "Resto", "", "").get("id");
        Map<String, Object> renewed = restored.issue(rid, LocalDate.of(2028, 1, 1), LocalDate.of(2028, 12, 31),
                new LinkedHashSet<String>(), 0, null, "");
        LicenseCodec.verify((String) renewed.get("code"), trusted(s));   // toujours accepte par OLPOS

        // donnees : sauvegarde complete puis restauration
        File backup = new File(d, "tout.olposlm");
        s.backup(backup);
        s.saveClient(null, "Ajout apres sauvegarde", "X", "", "");
        ManagerStore back = ManagerStore.restore(d, backup, PW);
        assertEquals(1, back.clients().size());
        assertEquals(l.get("code"), back.latestLicenses().get(0).get("code"));
        assertTrue(new File(d, ManagerStore.DATA_FILE + ".avant-restauration").isFile());

        // cle publique exportee : lisible par OLPOS (meme format que trusted-keys.properties)
        File pub = new File(d, "trusted-keys.properties");
        s.exportPublicKey(pub);
        Properties p = new Properties();
        try (java.io.InputStream in = new java.io.FileInputStream(pub)) {
            p.load(in);
        }
        assertEquals(s.getKeyId(), LicenseCodec.keyId(LicenseCodec.decodePublicKey(p.getProperty(s.getKeyId()))));
        assertFalse("aucune cle privee", new String(Files.readAllBytes(pub.toPath()), StandardCharsets.ISO_8859_1)
                .contains("PRIVATE"));
    }

    @Test
    public void motDePasseChange() throws Exception {
        File d = dir("changement");
        ManagerStore s = ManagerStore.create(d, PW);
        char[] neu = "Nouveau-mdp-42".toCharArray();
        s.changePassword(PW, neu);
        assertEquals(s.getKeyId(), ManagerStore.open(d, neu).getKeyId());
        try {
            ManagerStore.open(d, PW);
            fail();
        } catch (Vault.WrongPasswordException expected) {
            // ancien mot de passe refuse
        }
        assertNull(Vault.checkPassword("abcdefghij1".toCharArray()));
    }
}
