//    OLPOS (derive d'uniCenta oPOS) - API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.api;

import com.unicenta.basic.BasicException;
import com.unicenta.format.Formats;
import com.unicenta.pos.sales.TaxesException;
import com.unicenta.pos.sales.TaxesLogic;
import com.unicenta.pos.sales.restaurant.TableLocks;
import com.unicenta.pos.sales.restaurant.TableTicketStore;
import com.unicenta.pos.ticket.TicketInfo;
import com.unicenta.pos.ticket.TicketTaxInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Addition provisoire imprimee depuis le telephone : detail et total de la
 * table, SANS encaissement (aucune ecriture dans la commande ni dans la base).
 *
 * <ul>
 *   <li>Modele {@code Printer.TicketBill} de la base s'il existe (personnalisable
 *       dans Administration > Ressources), sinon celui fourni avec la caisse.</li>
 *   <li>Totaux et taxes recalcules par la caisse (TaxesLogic) au moment de l'impression.</li>
 *   <li>Chaque demande a un {@code printId} : renvoyee apres une coupure Wi-Fi,
 *       elle est reconnue et n'imprime pas une seconde addition. Une nouvelle
 *       addition = un nouveau printId (choix explicite du serveur).</li>
 *   <li>Memes statuts que la cuisine : confirme / envoye / a_verifier / non_imprime.</li>
 *   <li>Refus si la table est ouverte a la caisse (l'addition pourrait ne pas
 *       contenir les modifications en cours).</li>
 * </ul>
 */
final class MobileBillService {

    static final String PERMISSION = "sales.PrintTicket";
    private static final Pattern PRINT_ID = Pattern.compile("[A-Za-z0-9_-]{8,100}");
    private static final long KEEP_MILLIS = 24L * 3600 * 1000;
    private static final int KEEP_MAX = 2000;

    private final MobileDataService data;
    private final MobileOrderService orders;
    private final TableTicketStore store;
    private final TableLocks locks;
    private final MobilePrinter printer;
    private final PrintRunner runner;
    private final LinkedHashMap<String, Map<String, Object>> recent = new LinkedHashMap<>();

    MobileBillService(MobileDataService data, MobileOrderService orders, TableLocks locks,
            MobilePrinter printer, PrintRunner runner) {
        this.data = data;
        this.orders = orders;
        this.store = new TableTicketStore(data.getSession());
        this.locks = locks;
        this.printer = printer;
        this.runner = runner;
    }

    Map<String, Object> print(String tableId, Map<String, Object> body, ApiSessions.ApiSession user)
            throws ApiServer.ApiError, BasicException {
        if (!user.hasPermission(PERMISSION)) {
            throw new ApiServer.ApiError(403, "forbidden", "Permission " + PERMISSION + " requise pour imprimer l'addition.");
        }
        Object pid = body.get("printId");
        if (!(pid instanceof String) || !PRINT_ID.matcher((String) pid).matches()) {
            throw new ApiServer.ApiError(400, "invalid_print_id", "Champ 'printId' obligatoire : 8 a 100 caracteres "
                    + "(lettres, chiffres, - ou _), nouveau pour chaque addition et IDENTIQUE en cas de nouvel essai.");
        }
        String printId = (String) pid;
        if (printer == null) {
            throw new ApiServer.ApiError(503, "printing_unavailable", "Impression indisponible sur cette caisse.");
        }
        String tableName;
        synchronized (data) {
            tableName = orders.tableName(tableId);
        }

        // Demande deja recue (ou en cours) : on n'imprime pas une seconde addition.
        Map<String, Object> known = claim(printId, tableId, user);
        if (known != null) {
            synchronized (data) {
                return Json.obj("duplicate", true, "print", new LinkedHashMap<>(known),
                        "order", orders.orderJson(tableId, tableName, store.load(tableId)));
            }
        }

        MobilePrinter.Prepared prepared;
        String total;
        try {
            TableLocks.Holder h;
            try {
                h = locks.tryAcquireApi(tableId, user.getUserName(), 2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiServer.ApiError(503, "interrupted", "Requete interrompue, reessayez.");
            }
            if (h != null) {
                Map<String, Object> extra = Json.obj("lockedBy", MobileOrderService.holderJson(h));
                if (h.getSource() == TableLocks.Source.CAISSE) {
                    throw new ApiServer.ApiError(423, "table_locked", "La table " + tableName + " est ouverte a la caisse par "
                            + h.getWho() + " : l'addition pourrait etre incomplete. Rien n'a ete imprime ; "
                            + "reessayez quand elle sera refermee.", extra);
                }
                throw new ApiServer.ApiError(409, "table_busy", "La table " + tableName
                        + " est en cours de modification depuis un autre telephone. Rien n'a ete imprime : reessayez.", extra);
            }
            try {
                synchronized (data) {
                    TableTicketStore.Snapshot snap = store.load(tableId);
                    if (snap == null || snap.getTicket().getLinesCount() == 0) {
                        throw new ApiServer.ApiError(409, "empty_order", "Aucun article sur " + tableName
                                + " : pas d'addition a imprimer.");
                    }
                    TicketInfo copy = MobileKitchenService.escapedCopy(snap.getTicket(), user);
                    @SuppressWarnings("unchecked")
                    TaxesLogic taxes = new TaxesLogic(data.getDataLogicSales().getTaxList().list());
                    try {
                        taxes.calculateTaxes(copy);
                    } catch (TaxesException e) {
                        throw new ApiServer.ApiError(500, "tax_error", "Calcul des taxes impossible : " + e.getMessage());
                    }
                    List<Map<String, Object>> billTaxes = new ArrayList<>();
                    for (TicketTaxInfo t : copy.getTaxLines()) {
                        if (t.getTax() != 0.0) {
                            billTaxes.add(Json.obj("name", MobileKitchenService.xml(t.getTaxInfo().getName()),
                                    "base", t.printSubTotal(), "amount", t.printTax()));
                        }
                    }
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("billtaxes", billTaxes);
                    vars.put("printedat", new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date()));
                    total = Formats.CURRENCY.formatValue(copy.getTotal());
                    try {
                        prepared = printer.prepare(copy, MobileKitchenService.xml(tableName), template(), vars);
                    } catch (MobilePrinter.NotPrintedException e) {
                        throw new ApiServer.ApiError(503, "printer_not_ready", e.getMessage());
                    }
                }
            } finally {
                locks.releaseApi(tableId);
            }
        } catch (ApiServer.ApiError | BasicException | RuntimeException e) {
            forget(printId);   // rien n'a ete imprime : la meme demande pourra etre refaite
            throw e;
        }

        PrintRunner.Outcome outcome = runner.run(prepared, "l'addition");
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", printId);
        rec.put("tableId", tableId);
        rec.put("status", outcome.status);
        rec.put("message", outcome.message);
        rec.put("at", System.currentTimeMillis());
        rec.put("by", user.getUserName());
        rec.put("printer", prepared.printers());
        rec.put("total", total);
        remember(printId, rec);
        synchronized (data) {
            return Json.obj("duplicate", false, "print", rec,
                    "order", orders.orderJson(tableId, tableName, store.load(tableId)));
        }
    }

    /** Modele de la base ("Printer.TicketBill") ou, a defaut, celui fourni avec la caisse. */
    private String template() throws ApiServer.ApiError {
        String t = data.getDataLogicSystem().getResourceAsXML(TillPrinter.BILL_RESOURCE);
        if (t != null && !t.trim().isEmpty()) {
            return t;
        }
        try (InputStream in = MobileBillService.class.getResourceAsStream(
                "/com/unicenta/pos/templates/" + TillPrinter.BILL_RESOURCE + ".xml")) {
            if (in == null) {
                throw new ApiServer.ApiError(503, "printer_not_ready", "Modele d'addition introuvable.");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiServer.ApiError(503, "printer_not_ready", "Modele d'addition illisible.");
        }
    }

    // ------------------------------------------------------------------ memoire des demandes

    /** Reserve le printId ; renvoie l'issue connue si la demande a deja ete recue. */
    private synchronized Map<String, Object> claim(String printId, String tableId, ApiSessions.ApiSession user)
            throws ApiServer.ApiError {
        purge();
        Map<String, Object> rec = recent.get(printId);
        if (rec != null) {
            if (!tableId.equals(rec.get("tableId"))) {
                throw new ApiServer.ApiError(409, "print_id_reused", "Ce printId a deja servi pour une autre table.");
            }
            return rec;
        }
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("id", printId);
        pending.put("tableId", tableId);
        pending.put("status", "en_cours");
        pending.put("message", "Impression en cours...");
        pending.put("at", System.currentTimeMillis());
        pending.put("by", user.getUserName());
        recent.put(printId, pending);
        return null;
    }

    private synchronized void remember(String printId, Map<String, Object> rec) {
        recent.put(printId, rec);
    }

    private synchronized void forget(String printId) {
        recent.remove(printId);
    }

    private void purge() {
        long limit = System.currentTimeMillis() - KEEP_MILLIS;
        for (Iterator<Map<String, Object>> it = recent.values().iterator(); it.hasNext();) {
            Map<String, Object> r = it.next();
            if (recent.size() > KEEP_MAX || ((Number) r.get("at")).longValue() < limit) {
                it.remove();
            } else {
                break;
            }
        }
    }
}
