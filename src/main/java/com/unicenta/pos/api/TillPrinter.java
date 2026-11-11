//    OLPOS (derive d'uniCenta oPOS) - API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.api;

import com.unicenta.pos.forms.AppProperties;
import com.unicenta.pos.printer.DevicePrinter;
import com.unicenta.pos.printer.DevicePrinterNull;
import com.unicenta.pos.printer.DeviceTicket;
import com.unicenta.pos.printer.TicketParser;
import com.unicenta.pos.printer.escpos.PrinterWritterSocket;
import com.unicenta.pos.printer.screen.DevicePrinterPanel;
import com.unicenta.pos.scripting.ScriptEngine;
import com.unicenta.pos.scripting.ScriptFactory;
import com.unicenta.pos.ticket.TicketInfo;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;

/**
 * Impression avec les peripheriques de la caisse : memes modeles (ressources
 * modifiables dans Administration > Ressources), meme moteur (Velocity +
 * TicketParser) et memes imprimantes ({@code machine.printer.N}) que la caisse.
 *
 * <p>Avant d'imprimer, refuse clairement si une imprimante visee n'est pas
 * configuree ("Not defined" n'imprime rien sans erreur) ou si une imprimante
 * reseau ({@code socket}) ne repond pas.</p>
 */
public final class TillPrinter implements MobilePrinter {

    static final String KITCHEN_RESOURCE = "Printer.TicketKitchen";
    static final String BILL_RESOURCE = "Printer.TicketBill";
    private static final Pattern TICKET_PRINTER = Pattern.compile("<ticket(?:\\s+printer\\s*=\\s*\"([^\"]*)\")?");
    private static final int REACH_TIMEOUT_MS = 2000;

    private final DeviceTicket devices;
    private final TicketParser parser;
    private final AppProperties props;   // pour reconnaitre les imprimantes reseau (peut etre null)

    /**
     * Les modeles sont lus par l'API sur sa propre connexion : la connexion de
     * la caisse n'est utilisee que par l'interface.
     */
    public TillPrinter(DeviceTicket devices, TicketParser parser, AppProperties props) {
        this.devices = devices;
        this.parser = parser;
        this.props = props;
    }

    @Override
    public Prepared prepare(final TicketInfo ticket, String tableName, String template, Map<String, Object> variables)
            throws NotPrintedException {
        if (template == null) {
            throw new NotPrintedException("Modele de bon introuvable.", null);
        }
        final String xml;
        try {
            ScriptEngine script = ScriptFactory.getScriptEngine(ScriptFactory.VELOCITY);
            script.put("ticket", ticket);
            script.put("place", tableName);
            script.put("pickupid", Integer.toString(ticket.getPickupId()));
            if (variables != null) {
                for (Map.Entry<String, Object> e : variables.entrySet()) {
                    script.put(e.getKey(), e.getValue());
                }
            }
            xml = script.eval(template).toString();
        } catch (Exception e) {
            throw new NotPrintedException("Mise en page du bon impossible : " + e.getMessage(), e);
        }

        Set<String> ids = new LinkedHashSet<>();
        Matcher m = TICKET_PRINTER.matcher(xml);
        while (m.find()) {
            ids.add(m.group(1) == null || m.group(1).isEmpty() ? "1" : m.group(1));
        }
        if (ids.isEmpty()) {
            throw new NotPrintedException("Le modele ne contient aucun bon a imprimer.", null);
        }
        boolean allScreens = true;
        StringBuilder desc = new StringBuilder();
        for (String id : ids) {
            DevicePrinter p = devices.getDevicePrinter(id);
            if (p == null || p instanceof DevicePrinterNull) {
                throw new NotPrintedException("Imprimante n" + (char) 0xB0 + id + " non configuree ("
                        + key(id) + "). Rien n'a ete imprime.", null);
            }
            String network = networkAddress(id);
            if (network != null) {
                try {
                    PrinterWritterSocket.checkReachable(network, REACH_TIMEOUT_MS);
                } catch (IOException | IllegalArgumentException e) {
                    throw new NotPrintedException("L'imprimante reseau n" + (char) 0xB0 + id + " (" + network
                            + ") ne repond pas : verifiez qu'elle est allumee et sur le reseau. Rien n'a ete imprime.", e);
                }
            }
            boolean screen = p instanceof DevicePrinterPanel;
            allScreens &= screen;
            if (desc.length() > 0) {
                desc.append(", ");
            }
            desc.append(id).append(screen ? " (ecran)" : network != null ? " (reseau " + network + ")" : "");
        }
        final boolean confirms = allScreens;
        final String printers = desc.toString();

        return new Prepared() {
            @Override
            public boolean confirmsPrinting() {
                return confirms;
            }

            @Override
            public String printers() {
                return printers;
            }

            @Override
            public void print() throws Exception {
                // Peripheriques de la caisse (dont l'affichage ecran) utilises
                // depuis le fil de l'interface, comme a la caisse.
                final Exception[] failure = new Exception[1];
                Runnable job = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            parser.printTicket(xml, ticket);
                        } catch (Exception e) {
                            failure[0] = e;
                        }
                    }
                };
                if (SwingUtilities.isEventDispatchThread()) {
                    job.run();
                } else {
                    try {
                        SwingUtilities.invokeAndWait(job);
                    } catch (InvocationTargetException e) {
                        throw new Exception(e.getCause());
                    }
                }
                if (failure[0] != null) {
                    throw failure[0];
                }
            }
        };
    }

    private static String key(String id) {
        return "1".equals(id) ? "machine.printer" : "machine.printer." + id;
    }

    /** "hote:port" si l'imprimante est configuree en reseau ({@code <modele>:socket,hote:port}), sinon null. */
    private String networkAddress(String id) {
        if (props == null) {
            return null;
        }
        String conf = props.getProperty(key(id));
        if (conf == null) {
            return null;
        }
        int colon = conf.indexOf(':');
        String rest = colon < 0 ? "" : conf.substring(colon + 1);
        if (!rest.startsWith("socket,")) {
            return null;
        }
        return rest.substring("socket,".length()).trim();
    }
}
