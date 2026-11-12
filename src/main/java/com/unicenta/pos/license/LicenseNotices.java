//    OLPOS (derive d'uniCenta oPOS) - avertissements de licence
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.license;

import com.olpos.license.LicenseEvaluation;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import javax.swing.JOptionPane;

/**
 * Messages de licence au demarrage de la caisse (jamais pendant un
 * encaissement) :
 * <ul>
 *   <li>licence absente / non valable : message et acces a l'activation ;</li>
 *   <li>expiration a 30, 7 et 1 jour(s) : un avertissement par seuil et par
 *       jour (memorise dans le dossier de donnees).</li>
 * </ul>
 */
public final class LicenseNotices {

    static final String FILE_SHOWN = "licence-avertissements.txt";

    private LicenseNotices() {
    }

    public static void atStartup(Component parent, LicenseService service) {
        LicenseEvaluation e = service.getEvaluation();
        if (e.getStatus() != LicenseEvaluation.Status.VALID) {
            int r = JOptionPane.showOptionDialog(parent, e.getMessage()
                    + "\n\nLes nouvelles ventes sont bloquees. Vos donnees, rapports, sauvegardes et exports "
                    + "restent accessibles.", "OLPOS - Licence", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE, null, new Object[] {"Activation / Licence...", "Plus tard"},
                    "Activation / Licence...");
            if (r == 0) {
                LicenseDialog.open(parent, service);
            }
            return;
        }
        int threshold = e.warningThreshold();
        if (threshold > 0 && shouldWarn(service, threshold)) {
            JOptionPane.showMessageDialog(parent, "La licence OLPOS expire dans " + e.getDaysLeft() + " jour(s) ("
                    + LicenseEvaluation.describeEnd(e.getLicense(), service.getZone()) + ").\n"
                    + "Importez la licence renouvelee dans Activation / Licence : aucune reinstallation, "
                    + "aucune perte de donnees.", "OLPOS - Licence", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /** Un avertissement par seuil (30, 7, 1) et par jour. */
    static boolean shouldWarn(LicenseService service, int threshold) {
        File dir = com.unicenta.pos.forms.AppConfig.getDataDir();
        File f = new File(dir, FILE_SHOWN);
        String key = LocalDate.now() + ":" + threshold + ":" + service.getEvaluation().getLicense().getSerial();
        try {
            String done = f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
            if (done.contains(key)) {
                return false;
            }
            Files.write(f.toPath(), (key + "\r\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ex) {
            // pas de memoire : on avertit quand meme
        }
        return true;
    }
}
