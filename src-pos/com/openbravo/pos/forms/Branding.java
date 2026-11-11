//    OLPOS - version derivee d'uniCenta oPOS (Copyright (c) 2009-2014 uniCenta)
//    Licence GPL v3 (voir LICENSE.md).
package com.openbravo.pos.forms;

import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

/**
 * Identite visible du logiciel (OLPOS). Les noms TECHNIQUES ne changent pas
 * (paquets com.openbravo.*, identifiant {@link AppLocal#APP_ID} "unicentaopos",
 * fichier unicentaopos.properties, unicentaopos.jar, base de donnees) : ils
 * restent compatibles avec les installations existantes.
 *
 * Images generees par branding/generate_assets.py a partir des fichiers fournis.
 */
public final class Branding {

    public static final String NAME = "OLPOS";
    public static final String TAGLINE = "Point de vente tactile pour restaurants";
    /** Logiciel d'origine (attribution conservee). */
    public static final String DERIVED_FROM = "uniCenta oPOS 3.81";

    /** Logo horizontal (ecran de connexion). */
    public static final String LOGO = "/com/openbravo/images/olpos_logo.png";
    /** Petit logo (barre de titre de la caisse). */
    public static final String LOGO_SMALL = "/com/openbravo/images/olpos_logo_small.png";
    /** Symbole seul (outil de configuration). */
    public static final String SYMBOL = "/com/openbravo/images/olpos_logo_config.png";

    private static final int[] ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    private Branding() {
    }

    /** Icones de fenetre (plusieurs tailles : Windows choisit la meilleure). */
    public static List<Image> windowIcons() {
        List<Image> icons = new ArrayList<>();
        for (int s : ICON_SIZES) {
            try (InputStream in = Branding.class.getResourceAsStream("/com/openbravo/images/olpos_icon_" + s + ".png")) {
                if (in != null) {
                    icons.add(ImageIO.read(in));
                }
            } catch (IOException e) {
                // taille absente : les autres suffisent
            }
        }
        return icons;
    }

    public static ImageIcon icon(String path) {
        java.net.URL url = Branding.class.getResource(path);
        return url == null ? null : new ImageIcon(url);
    }

    /**
     * Texte "A propos" (ecran de connexion) : OLPOS presente comme version
     * derivee d'uniCenta oPOS ; mentions de copyright et licence d'origine
     * conservees telles quelles.
     */
    public static String aboutHtml() {
        return "<html><center><b>" + NAME + "</b> - " + TAGLINE + "<br>"
                + "Version " + AppLocal.APP_VERSION + " - version d\u00e9riv\u00e9e d'" + DERIVED_FROM + "<br>"
                + "<br>"
                + "Bas\u00e9 sur uniCenta oPOS - Touch Friendly Point of Sale<br>"
                + "Copyright \u00a9 2009-2014 uniCenta<br>"
                + "http://www.unicenta.com/<br>"
                + "et sur Openbravo POS<br>"
                + "<br>"
                + "uniCenta oPOS is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.<br>"
                + "<br>"
                + "uniCenta oPOS is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.<br>"
                + "<br>"
                + "You should have received a copy of the GNU General Public License along with uniCenta oPOS.  If not, see http://www.gnu.org/licenses/<br>"
                + "</center>";
    }
}
