//    OLPOS (derive d'uniCenta oPOS) - numero de commande sur les tickets
//    Licence GPL v3 (voir LICENSE.md).
package com.openbravo.pos.sales;

import com.openbravo.pos.forms.DataLogicSystem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blocs "COMMANDE N\u00b0 42" des modeles d'impression, et leur ajout dans les
 * modeles d'une base EXISTANTE.
 *
 * <p>Le bloc est INSERE juste apres la premiere balise {@code <ticket ...>} :
 * rien n'est supprime ni modifie dans le modele du restaurant (nom, logo,
 * mise en page, prix, taxes). Un marqueur {@value #MARKER} evite de l'ajouter
 * deux fois. Desactivable : {@code olpos.ordernumber.templates=false}.</p>
 */
public final class OrderNumberTemplates {

    private static final Logger LOG = Logger.getLogger(OrderNumberTemplates.class.getName());
    public static final String MARKER = "OLPOS:order-number";

    /** Grand numero (double hauteur et largeur : 21 caracteres par ligne). */
    private static final String NUMBER_LINE =
            "        <line size=\"3\"><text align=\"center\" bold=\"true\" length=\"21\">COMMANDE N\u00b0 ${ticket.getProperty(\"order.number\")}</text></line>\n";

    /** Date, heure et table (ou a emporter) : le numero etant reutilise apres 100. */
    private static final String WHERE_WHEN =
            "        <line><text align=\"center\" length=\"42\">${ticket.printDate()}</text></line>\n"
            + "#if ($place && \"$place\" != \"\")\n"
            + "        <line size=\"1\"><text align=\"center\" bold=\"true\" length=\"42\">TABLE : ${place}</text></line>\n"
            + "#else\n"
            + "        <line size=\"1\"><text align=\"center\" bold=\"true\" length=\"42\">A EMPORTER</text></line>\n"
            + "#end\n";

    /** Ticket client, addition. */
    public static final String RECEIPT_BLOCK =
            "        <!-- " + MARKER + " : numero de commande commun au ticket client et au bon cuisine -->\n"
            + "#if ($ticket.getProperty(\"order.number\"))\n"
            + NUMBER_LINE
            + WHERE_WHEN
            + "        <line></line>\n"
            + "#end\n";

    /** Reimpression d'un ticket : DUPLICATA + numero d'origine. */
    public static final String DUPLICATE_BLOCK =
            "        <!-- " + MARKER + " : reimpression (DUPLICATA) avec le numero d'origine -->\n"
            + "        <line size=\"3\"><text align=\"center\" bold=\"true\" length=\"21\">DUPLICATA</text></line>\n"
            + "#if ($ticket.getProperty(\"order.number\"))\n"
            + NUMBER_LINE
            + WHERE_WHEN
            + "#end\n"
            + "        <line></line>\n";

    /**
     * Bon cuisine : AJOUT si des lignes de cette commande sont deja parties en
     * cuisine, DUPLICATA si des lignes sont reimprimees a la demande du serveur.
     */
    public static final String KITCHEN_BLOCK =
            "        <!-- " + MARKER + " : numero commun ; AJOUT = bon complementaire ; DUPLICATA = reimpression -->\n"
            + "#set ($olposAjout = false)\n"
            + "#set ($olposDuplicata = false)\n"
            + "#foreach ($olposLine in $ticket.getLines())\n"
            + "#if ($olposLine.isProductKitchen() && $olposLine.getProperty(\"sendstatus\"))\n"
            + "#if ($olposLine.getProperty(\"sendstatus\").equals(\"No\"))\n"
            + "#if ($olposLine.getProperty(\"kitchen.reprint\"))\n"
            + "#set ($olposDuplicata = true)\n"
            + "#end\n"
            + "#elseif (!$olposLine.getProperty(\"sendstatus\").equals(\"-\"))\n"
            + "#set ($olposAjout = true)\n"
            + "#end\n"
            + "#end\n"
            + "#end\n"
            + "#if ($olposDuplicata)\n"
            + "        <line size=\"3\"><text align=\"center\" bold=\"true\" length=\"21\">DUPLICATA</text></line>\n"
            + "#elseif ($olposAjout)\n"
            + "        <line size=\"3\"><text align=\"center\" bold=\"true\" length=\"21\">AJOUT</text></line>\n"
            + "#end\n"
            + "#if ($ticket.getProperty(\"order.number\"))\n"
            + NUMBER_LINE
            + "#end\n"
            + WHERE_WHEN
            + "        <line></line>\n";

    private static final Pattern TICKET_OPEN = Pattern.compile("<ticket\\b[^>]*>[ \\t]*\\r?\\n?");

    private OrderNumberTemplates() {
    }

    /** Bloc a utiliser pour une ressource, ou null si elle n'en recoit pas. */
    public static String blockFor(String resource) {
        switch (resource) {
            case "Printer.Ticket":
            case "Printer.TicketBill":
                return RECEIPT_BLOCK;
            case "Printer.TicketKitchen":
                return KITCHEN_BLOCK;
            case "Printer.TicketPreview":
            case "Printer.ReprintTicket":
                return DUPLICATE_BLOCK;
            default:
                return null;
        }
    }

    /** Insere le bloc apres la premiere balise ticket (une seule fois). */
    public static String addBlock(String resource, String template) {
        String block = blockFor(resource);
        if (template == null || block == null || template.contains(MARKER)) {
            return template;
        }
        Matcher m = TICKET_OPEN.matcher(template);
        if (!m.find()) {
            return template;   // modele sans bon imprime : rien a faire
        }
        String nl = template.contains("\r\n") ? "\r\n" : "\n";
        String insert = block.replace("\n", nl);
        String head = template.substring(0, m.end());
        if (!head.endsWith("\n")) {
            head += nl;
        }
        return head + insert + template.substring(m.end());
    }

    static final String[] RESOURCES = {
        "Printer.Ticket", "Printer.TicketKitchen", "Printer.TicketPreview", "Printer.ReprintTicket", "Printer.TicketBill",
    };

    /** Ajoute les blocs dans les modeles de la base qui ne les ont pas encore. */
    public static List<String> run(DataLogicSystem dlSystem) {
        List<String> changed = new ArrayList<>();
        for (String name : RESOURCES) {
            try {
                String text = dlSystem.getResourceAsText(name);
                if (text == null || text.trim().isEmpty()) {
                    continue;   // absent : le modele fourni avec la caisse (deja numerote) est utilise
                }
                String updated = addBlock(name, text);
                if (!updated.equals(text)) {
                    dlSystem.setResource(name, 0, updated.getBytes(StandardCharsets.UTF_8));
                    changed.add(name);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Numero de commande non ajoute au modele " + name, e);
            }
        }
        if (!changed.isEmpty()) {
            LOG.log(Level.INFO, "Numero de commande ajoute aux modeles : {0}", changed);
        }
        return changed;
    }
}
