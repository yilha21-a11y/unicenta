package com.unicenta.pos.forms;

import java.io.File;
import java.util.TimeZone;
import org.junit.After;
import org.junit.Before;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

/** Reglage user.timezone : fuseau de la caisse choisi par restaurant. */
public class StartPOSTimeZoneTest {

    private TimeZone original;

    @Before
    public void save() {
        original = TimeZone.getDefault();
    }

    @After
    public void restore() {
        TimeZone.setDefault(original);
    }

    private static AppConfig config(String tz) {
        AppConfig c = new AppConfig(new File("build/test-work/inexistant.properties"));
        c.load();
        c.setProperty("user.timezone", tz);
        return c;
    }

    @Test
    public void fuseauApplique() {
        assertEquals("America/Los_Angeles", StartPOS.applyTimeZone(config("America/Los_Angeles")).getID());
        assertEquals("America/Los_Angeles", TimeZone.getDefault().getID());
    }

    @Test
    public void videOuInconnuNeChangeRien() {
        TimeZone before = TimeZone.getDefault();
        assertNull(StartPOS.applyTimeZone(config(null)));
        assertNull(StartPOS.applyTimeZone(config("  ")));
        assertNull(StartPOS.applyTimeZone(config("Pacifique/Inexistant")));
        assertEquals(before, TimeZone.getDefault());
    }
}
