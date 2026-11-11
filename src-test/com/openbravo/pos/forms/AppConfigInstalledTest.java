package com.openbravo.pos.forms;

import java.io.File;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Version installee (OLPOS-Setup.exe) : configuration et base dans un dossier
 * de donnees separe, jamais dans ~/unicentaopos.properties.
 */
public class AppConfigInstalledTest {

    @After
    public void reset() {
        System.clearProperty("olpos.datadir");
        System.clearProperty("olpos.installed");
        AppConfig.setActiveConfigFile(null);
    }

    @Test
    public void horsInstallationRienNeChange() {
        assertNull(AppConfig.installedDataDirPath());
        assertEquals(new File(System.getProperty("user.home"), "unicentaopos.properties"),
                AppConfig.getActiveConfigFile());
    }

    @Test
    public void installeeUtiliseLeDossierDeDonnees() {
        File dir = new File("build/test-work/olpos-data").getAbsoluteFile();
        System.setProperty("olpos.datadir", dir.getPath());
        assertEquals(dir, AppConfig.getInstalledDataDir());
        assertTrue(dir.isDirectory());
        assertEquals(new File(dir, "olpos.properties"), AppConfig.getActiveConfigFile());
        AppConfig c = new AppConfig(new String[0]);
        c.load();
        assertEquals("jdbc:derby:" + new File(dir, "database").getAbsolutePath() + ";create=true",
                c.getProperty("db.URL"));
        // Restaurant + mobile actives d'office dans la version installee
        assertEquals("true", c.getProperty("api.enabled"));
        assertEquals("8095", c.getProperty("api.port"));
        assertEquals("restaurant", c.getProperty("machine.ticketsbag"));
    }

    @Test
    public void installeeMobileDesactivableDansLeFichier() throws Exception {
        File dir = new File("build/test-work/olpos-data-off").getAbsoluteFile();
        System.setProperty("olpos.datadir", dir.getPath());
        dir.mkdirs();
        java.nio.file.Files.write(new File(dir, "olpos.properties").toPath(),
                "api.enabled=false\n".getBytes("ISO-8859-1"));
        AppConfig c = new AppConfig(new String[0]);
        c.load();
        assertEquals("false", c.getProperty("api.enabled"));
    }

    @Test
    public void horsInstallationMobileResteDesactive() {
        AppConfig c = new AppConfig(new File("build/test-work/absent.properties"));
        c.load();
        assertNull(c.getProperty("api.enabled"));
        assertEquals("standard", c.getProperty("machine.ticketsbag"));
    }

    @Test
    public void installeeSansDossierExpliciteVaDansLocalAppData() {
        System.setProperty("olpos.installed", "true");
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            // installedDataDirPath : ne cree pas le vrai dossier pendant les tests
            assertEquals(new File(local, "OLPOS"), AppConfig.installedDataDirPath());
        }
    }
}
