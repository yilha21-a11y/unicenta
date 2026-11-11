//    OLPOS - version derivee d'uniCenta oPOS (Copyright (c) 2009-2014 uniCenta)
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.forms;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Migration douce de la marque dans une base EXISTANTE.
 *
 * <p>Le titre de la caisse et les modeles de tickets sont stockes dans la base
 * (table RESOURCES) a sa creation : changer les fichiers ne suffit pas. Au
 * demarrage, on remplace UNIQUEMENT les lignes exactes du logiciel livrees par
 * uniCenta (texte "uniCenta oPOS" des tickets, titre par defaut). Le nom, le
 * logo et tout le reste du restaurant ne sont jamais touches ; un modele deja
 * personnalise sans ces lignes reste identique. Operation idempotente.</p>
 *
 * <p>Desactivable : {@code olpos.branding.migrate=false} dans la configuration.</p>
 */
public final class BrandingMigration {

    private static final Logger LOG = Logger.getLogger(BrandingMigration.class.getName());

    /** Ressources susceptibles de contenir les mentions du logiciel d'origine. */
    static final String[] RESOURCES = {
        "Window.Title", "Printer.Ticket", "Printer.TicketNew", "Printer.ReprintTicket",
        "Printer.TicketPreview", "Printer.Start", "Printer.TicketLine",
    };

    /** Titre par defaut de la caisse (Window.Title.txt d'uniCenta), seulement pour Window.Title. */
    static final String[] TITLE = {"uniCenta oPOS - Touch Friendly Point Of Sale", "OLPOS - Point de vente tactile"};

    /** Lignes exactes du logiciel sur les tickets et l'afficheur client. */
    static final String[][] TICKET_LINES = {
        {">uniCenta oPOS</text>", ">OLPOS</text>"},
        // ligne de copyright du logiciel : attribution conservee en version courte
        {">Copyright (c) 2009-2014 uniCenta</text>", ">d'apres uniCenta oPOS - GPL v3</text>"},
        // slogan du logiciel d'origine sur les tickets
        {">Touch Friendly Point Of Sale</text>", ">Point de vente tactile</text>"},
        // afficheur client d'uniCenta 5.0 (Printer.Start)
        {">Touch Friendly POS</text>", ">Caisse tactile</text>"},
    };

    private BrandingMigration() {
    }

    /** Applique les remplacements prevus pour cette ressource (rien d'autre ne change). */
    public static String migrate(String resource, String text) {
        if (text == null) {
            return null;
        }
        if ("Window.Title".equals(resource)) {
            return text.replace(TITLE[0], TITLE[1]);
        }
        String s = text;
        for (String[] r : TICKET_LINES) {
            s = s.replace(r[0], r[1]);
        }
        return s;
    }

    /**
     * Met a jour les ressources de la base qui contiennent encore les textes
     * d'origine du logiciel.
     * @return noms des ressources modifiees
     */
    public static List<String> run(DataLogicSystem dlSystem) {
        List<String> changed = new ArrayList<>();
        for (String name : RESOURCES) {
            try {
                String text = dlSystem.getResourceAsText(name);
                String migrated = migrate(name, text);
                if (text != null && !text.isEmpty() && !migrated.equals(text)) {
                    dlSystem.setResource(name, 0, migrated.getBytes(StandardCharsets.UTF_8));
                    changed.add(name);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Migration de marque impossible pour " + name, e);
            }
        }
        if (replaceDefaultReceiptLogo(dlSystem)) {
            changed.add(RECEIPT_LOGO);
        }
        if (!changed.isEmpty()) {
            LOG.log(Level.INFO, "Marque OLPOS appliquee aux ressources : {0}", changed);
        }
        return changed;
    }

    /** Logo imprime en haut des tickets (normalement celui du restaurant). */
    static final String RECEIPT_LOGO = "Printer.Ticket.Logo";
    /** Empreinte SHA-256 du logo d'EXEMPLE livre par uniCenta 3.81 (printer.ticket.logo.png d'origine). */
    static final String UNICENTA_SAMPLE_LOGO_SHA256 = "54e07b13d11d4f6c8c3e8e17a994f152320c9dbea53309ddda020357e686e797";
    /** Empreinte SHA-256 du logo d'EXEMPLE livre par uniCenta 5.0 (printer.ticket.logo.jpg d'origine). */
    static final String UNICENTA_5_SAMPLE_LOGO_SHA256 = "c607a519fc697aee9ed90d80cbc70c5c80da6cebe819bff3a53896c0f4ae4567";

    /**
     * Remplace le logo d'exemple d'uniCenta par celui d'OLPOS, UNIQUEMENT s'il
     * est identique a l'original : un logo de restaurant n'est jamais touche.
     */
    static boolean replaceDefaultReceiptLogo(DataLogicSystem dlSystem) {
        try {
            byte[] current = dlSystem.getResourceAsBinary(RECEIPT_LOGO);
            if (current == null) {
                return false;
            }
            String h = sha256(current);
            if (!UNICENTA_SAMPLE_LOGO_SHA256.equals(h) && !UNICENTA_5_SAMPLE_LOGO_SHA256.equals(h)) {
                return false;   // logo du restaurant : jamais touche
            }
            byte[] olpos;
            try (java.io.InputStream in = BrandingMigration.class.getResourceAsStream(
                    "/com/unicenta/images/printer.ticket.logo.jpg")) {
                if (in == null) {
                    return false;
                }
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                olpos = out.toByteArray();
            }
            if (UNICENTA_SAMPLE_LOGO_SHA256.equals(sha256(olpos)) || UNICENTA_5_SAMPLE_LOGO_SHA256.equals(sha256(olpos))) {
                return false;   // jar non regenere : rien a faire
            }
            dlSystem.setResource(RECEIPT_LOGO, 1, olpos);
            return true;
        } catch (java.io.IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Logo d'exemple des tickets non remplace", e);
            return false;
        }
    }

    static String sha256(byte[] data) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
