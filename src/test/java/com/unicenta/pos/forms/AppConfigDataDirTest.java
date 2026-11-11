package com.unicenta.pos.forms;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * OLPOS 5 : configuration, base et journaux dans un dossier de donnees separe,
 * jamais ~/unicentaopos.properties ni ~/.unicenta (installation uniCenta).
 * Les tests tournent avec -Dolpos.datadir (voir pom.xml, surefire).
 */
public class AppConfigDataDirTest {

    private final String saved = System.getProperty("olpos.datadir");

    @After
    public void reset() {
        if (saved == null) {
            System.clearProperty("olpos.datadir");
        } else {
            System.setProperty("olpos.datadir", saved);
        }
        AppConfig.setActiveConfigFile(null);
    }

    @Test
    public void configurationEtBaseDansLeDossierDeDonnees() {
        File dir = new File("build/test-work/olpos-data").getAbsoluteFile();
        System.setProperty("olpos.datadir", dir.getPath());
        assertEquals(dir, AppConfig.getDataDir());
        assertTrue(dir.isDirectory());
        assertEquals(new File(dir, "olpos.properties"), AppConfig.getActiveConfigFile());
        AppConfig c = new AppConfig(new String[0]);
        c.load();
        String url = c.getProperty("db.URL") + c.getProperty("db.schema");
        assertEquals("jdbc:derby:" + dir.getPath().replace(File.separatorChar, '/') + "/database;create=true", url);
        // Restaurant + mobile actives d'office
        assertEquals("true", c.getProperty("api.enabled"));
        assertEquals("8095", c.getProperty("api.port"));
        assertEquals("restaurant", c.getProperty("machine.ticketsbag"));
    }

    @Test
    public void jamaisLaConfigurationNiLaBaseDUniCenta() {
        File home = new File(System.getProperty("user.home"));
        AppConfig.setActiveConfigFile(null);
        assertFalse(AppConfig.getActiveConfigFile().equals(new File(home, "unicentaopos.properties")));
        AppConfig c = new AppConfig(new String[0]);
        c.load();
        String url = (c.getProperty("db.URL") + c.getProperty("db.schema")).replace(File.separatorChar, '/').toLowerCase();
        assertFalse(url, url.contains("/.unicenta"));
        assertFalse(url, url.contains("unicentaopos-database"));
        assertEquals(AppConfig.getActiveConfigFile(), AppConfig.getInstance().getConfigFile());
    }

    @Test
    public void mobileDesactivableDansLeFichier() throws Exception {
        File dir = new File("build/test-work/olpos-data-off").getAbsoluteFile();
        System.setProperty("olpos.datadir", dir.getPath());
        dir.mkdirs();
        Files.write(new File(dir, "olpos.properties").toPath(), "api.enabled=false\n".getBytes(StandardCharsets.ISO_8859_1));
        AppConfig c = new AppConfig(new String[0]);
        c.load();
        assertEquals("false", c.getProperty("api.enabled"));
    }

    @Test
    public void sansDossierExpliciteVaDansLocalAppDataOlpos5() {
        System.clearProperty("olpos.datadir");
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            // dataDirPath ne cree pas le vrai dossier pendant les tests
            assertEquals(new File(local, "OLPOS5"), AppConfig.dataDirPath());
        }
    }
}
