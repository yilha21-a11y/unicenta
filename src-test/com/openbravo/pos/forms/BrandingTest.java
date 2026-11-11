package com.openbravo.pos.forms;

import com.openbravo.data.loader.Session;
import com.openbravo.pos.testenv.TestEnvironment;
import java.awt.Image;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Marque OLPOS : nom visible, identifiants techniques inchanges, attribution, migration douce. */
public class BrandingTest {

    @Test
    public void nomVisibleEtIdentifiantTechnique() {
        assertEquals("OLPOS", AppLocal.APP_NAME);
        assertEquals("compatibilite : base, unicentaopos.properties", "unicentaopos", AppLocal.APP_ID);
    }

    @Test
    public void aProposPresenteOlposCommeDeriveEtGardeLaLicence() {
        String about = Branding.aboutHtml();
        assertTrue(about.contains("OLPOS"));
        assertTrue(about.contains("uniCenta oPOS 3.81"));
        assertTrue(about.contains("Copyright © 2009-2014 uniCenta"));
        assertTrue(about.contains("Openbravo POS"));
        assertTrue(about.contains("GNU General Public License"));
        assertTrue(about.contains("WITHOUT ANY WARRANTY"));
    }

    @Test
    public void iconesEtLogosPresentsDansLeJar() {
        List<Image> icons = Branding.windowIcons();
        assertEquals(9, icons.size());
        assertEquals(16, icons.get(0).getWidth(null));
        assertEquals(256, icons.get(icons.size() - 1).getWidth(null));
        for (String p : new String[] {Branding.LOGO, Branding.LOGO_SMALL, Branding.SYMBOL, "/com/openbravo/images/favicon.png"}) {
            assertNotNull(p, Branding.icon(p));
        }
        assertEquals(410, Branding.icon(Branding.LOGO).getIconWidth());
    }

    // ------------------------------------------------------------------ migration douce

    private static final String OLD_TICKET =
            "<output>\n<ticket>\n"
            + "  <line><text align=\"center\" length=\"42\">Restaurant Chez Marie</text></line>\n"
            + "  <line><text align=\"center\" length=\"42\">uniCenta oPOS</text></line>\n"
            + "  <line><text align=\"center\" length=\"42\">Copyright (c) 2009-2014 uniCenta</text></line>\n"
            + "  <!-- uniCenta oPOS - Touch Friendly Point Of Sale (en-tete de licence) -->\n"
            + "</ticket>\n</output>\n";

    @Test
    public void seulesLesLignesDuLogicielChangent() {
        String m = BrandingMigration.migrate("Printer.Ticket", OLD_TICKET);
        assertTrue(m.contains(">OLPOS</text>"));
        assertTrue(m.contains(">d'apres uniCenta oPOS - GPL v3</text>"));
        assertTrue("nom du restaurant intact", m.contains(">Restaurant Chez Marie</text>"));
        assertTrue("commentaire de licence intact", m.contains("uniCenta oPOS - Touch Friendly Point Of Sale (en-tete de licence)"));
        assertEquals("idempotent", m, BrandingMigration.migrate("Printer.Ticket", m));
        // titre : seulement la valeur par defaut d'uniCenta
        assertEquals("OLPOS - Point de vente tactile",
                BrandingMigration.migrate("Window.Title", "uniCenta oPOS - Touch Friendly Point Of Sale"));
        assertEquals("Chez Marie - Caisse 1", BrandingMigration.migrate("Window.Title", "Chez Marie - Caisse 1"));
    }

    @Test
    public void migrationDUneBaseExistante() throws Exception {
        File root = new File(".").getCanonicalFile();
        TestEnvironment.Result env = TestEnvironment.create(new File(root, "build/test-work/branding"), true, 0,
                "b-" + System.nanoTime(), root);
        AppConfig config = new AppConfig(env.configFile);
        config.load();
        Session s = AppViewConnection.createSession(config);
        try {
            DataLogicSystem dl = new DataLogicSystem();
            dl.init(s);
            // etat d'une base creee par uniCenta : anciens textes en base
            dl.setResource("Printer.Ticket", 0, OLD_TICKET.getBytes(StandardCharsets.UTF_8));
            dl.setResource("Window.Title", 0, "uniCenta oPOS - Touch Friendly Point Of Sale".getBytes(StandardCharsets.UTF_8));
            dl.setResource("Printer.TicketNew", 0, "<output><ticket><line><text>Chez Marie</text></line></ticket></output>"
                    .getBytes(StandardCharsets.UTF_8));

            List<String> changed = BrandingMigration.run(dl);
            assertTrue(changed.contains("Printer.Ticket"));
            assertTrue(changed.contains("Window.Title"));
            assertTrue("modele personnalise sans mention : intact", !changed.contains("Printer.TicketNew"));

            DataLogicSystem fresh = new DataLogicSystem();   // relu depuis la base (sans cache)
            fresh.init(s);
            assertEquals("OLPOS - Point de vente tactile", fresh.getResourceAsText("Window.Title"));
            String ticket = fresh.getResourceAsText("Printer.Ticket");
            assertTrue(ticket.contains(">OLPOS</text>"));
            assertTrue(ticket.contains("Restaurant Chez Marie"));
            assertEquals("<output><ticket><line><text>Chez Marie</text></line></ticket></output>",
                    fresh.getResourceAsText("Printer.TicketNew"));
            assertTrue("2e demarrage : rien a faire", BrandingMigration.run(fresh).isEmpty());

            // logo des tickets : celui d'exemple d'uniCenta est remplace, celui d'un restaurant jamais
            byte[] sample = java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("src-test/com/openbravo/pos/forms/unicenta-sample-logo.png"));
            fresh.setResource("Printer.Ticket.Logo", 1, sample);
            assertTrue(BrandingMigration.run(fresh).contains("Printer.Ticket.Logo"));
            DataLogicSystem again = new DataLogicSystem();
            again.init(s);
            assertTrue("logo OLPOS installe", !java.util.Arrays.equals(sample, again.getResourceAsBinary("Printer.Ticket.Logo")));
            byte[] restaurantLogo = new byte[] {1, 2, 3, 4, 5};
            again.setResource("Printer.Ticket.Logo", 1, restaurantLogo);
            assertTrue(BrandingMigration.run(again).isEmpty());
            assertTrue("logo du restaurant intact", java.util.Arrays.equals(restaurantLogo, again.getResourceAsBinary("Printer.Ticket.Logo")));
            // la base reste reconnue (identifiant technique inchange)
            assertEquals(AppLocal.APP_VERSION, fresh.findVersion());
        } finally {
            s.close();
        }
    }
}
