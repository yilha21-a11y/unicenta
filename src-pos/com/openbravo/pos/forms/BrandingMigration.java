//    OLPOS - version derivee d'uniCenta oPOS (Copyright (c) 2009-2014 uniCenta)
//    Licence GPL v3 (voir LICENSE.md).
package com.openbravo.pos.forms;

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
        "Printer.TicketPreview", "Printer.Start",
    };

    /** Titre par defaut de la caisse (Window.Title.txt d'uniCenta), seulement pour Window.Title. */
    static final String[] TITLE = {"uniCenta oPOS - Touch Friendly Point Of Sale", "OLPOS - Point de vente tactile"};

    /** Lignes exactes du logiciel sur les tickets et l'afficheur client. */
    static final String[][] TICKET_LINES = {
        {">uniCenta oPOS</text>", ">OLPOS</text>"},
        // ligne de copyright du logiciel : attribution conservee en version courte
        {">Copyright (c) 2009-2014 uniCenta</text>", ">d'apres uniCenta oPOS - GPL v3</text>"},
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
        if (!changed.isEmpty()) {
            LOG.log(Level.INFO, "Marque OLPOS appliquee aux ressources : {0}", changed);
        }
        return changed;
    }
}
