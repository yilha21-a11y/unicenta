package com.openbravo.pos.forms;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Properties;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/** Chaque instance doit relire SON fichier de configuration, pas ~/unicentaopos.properties. */
public class AppConfigActiveTest {

    @After
    public void reset() {
        AppConfig.setActiveConfigFile(null);
    }

    @Test
    public void lesEcransRelisentLeFichierDeLInstance() throws Exception {
        File dir = new File("build/test-work/config");
        dir.mkdirs();
        File f = new File(dir, "restaurant-a.properties");
        Properties p = new Properties();
        p.setProperty("till.pricewith00", "true");
        p.setProperty("till.receiptprefix", "RESTO-A-");
        try (OutputStream out = new FileOutputStream(f)) {
            p.store(out, "test");
        }
        AppConfig.setActiveConfigFile(f);
        AppConfig c = AppConfig.loadActive();
        assertEquals(f, c.getConfigFile());
        assertEquals("true", c.getProperty("till.pricewith00"));
        assertEquals("RESTO-A-", c.getProperty("till.receiptprefix"));
    }

    @Test
    public void sansDeclarationOnGardeLeFichierParDefaut() {
        AppConfig.setActiveConfigFile(null);
        assertEquals(new File(System.getProperty("user.home"), "unicentaopos.properties"),
                AppConfig.getActiveConfigFile());
    }
}
