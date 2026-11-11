//    OLPOS (derive d'uniCenta oPOS) - API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.openbravo.pos.api;

import com.openbravo.basic.BasicException;
import com.openbravo.pos.sales.restaurant.OrderConflictException;
import com.openbravo.pos.sales.restaurant.TableLocks;
import com.openbravo.pos.sales.restaurant.TableTicketStore;
import com.openbravo.pos.ticket.TicketInfo;
import com.openbravo.pos.ticket.TicketLineInfo;
import com.openbravo.pos.ticket.UserInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Envoi des nouvelles lignes en cuisine depuis le telephone, SANS DOUBLE
 * IMPRESSION, en distinguant ce qui est certain de ce qui ne l'est pas.
 *
 * <h3>Statuts d'un envoi</h3>
 * <ul>
 *   <li>{@code envoye} : le bon a ete transmis a l'imprimante sans erreur,
 *       mais l'imprimante ne sait pas confirmer qu'il est sorti ;</li>
 *   <li>{@code confirme} : l'imprimante confirme (affichage ecran) ;</li>
 *   <li>{@code a_verifier} : erreur ou delai depasse PENDANT l'impression
 *       (ou caisse arretee au milieu) : le bon a pu sortir ou non ;</li>
 *   <li>{@code confirme_manuellement} / {@code reimpression_demandee} : choix
 *       explicite du serveur apres verification.</li>
 * </ul>
 *
 * <h3>Garanties</h3>
 * <ul>
 *   <li>Les lignes sont marquees "a verifier" et l'envoi enregistre AVANT
 *       d'imprimer : si la caisse s'arrete pendant l'impression, rien ne sera
 *       reimprime automatiquement.</li>
 *   <li>Une ligne "a verifier" n'est plus jamais incluse dans un envoi
 *       automatique : seul le serveur peut dire "le bon est sorti" ou
 *       "reimprimer".</li>
 *   <li>Chaque envoi a un {@code sendId} choisi par le telephone : renvoye
 *       apres une coupure Wi-Fi, il est reconnu et n'imprime rien.</li>
 *   <li>Erreur AVANT impression (imprimante non configuree) : rien n'est
 *       imprime ni modifie, le message le dit clairement.</li>
 * </ul>
 */
final class MobileKitchenService {

    static final String VERIFY = "Verify";                 // sendstatus d'une ligne a impression incertaine
    static final String P_LINE_SEND_ID = "kitchen.sendId";
    /** Ligne remise "a envoyer" par une demande de reimpression : son bon porte DUPLICATA. */
    static final String P_LINE_REPRINT = "kitchen.reprint";
    static final String P_SENDS = "kitchen.sends";
    static final String PERMISSION = "sales.PrintKitchen";
    static final int SENDS_KEPT = 20;
    private static final Pattern SEND_ID = Pattern.compile("[A-Za-z0-9_-]{8,100}");
    private static final Logger LOG = Logger.getLogger(MobileKitchenService.class.getName());

    private final MobileDataService data;
    private final MobileOrderService orders;
    private final TableTicketStore store;
    private final TableLocks locks;
    private final MobilePrinter printer;
    private final PrintRunner runner;
    private final Set<String> inProgress = IN_PROGRESS_GLOBAL;   // envois en cours d'impression

    MobileKitchenService(MobileDataService data, MobileOrderService orders, TableLocks locks,
            MobilePrinter printer, PrintRunner runner) {
        this.data = data;
        this.orders = orders;
        this.store = new TableTicketStore(data.getSession());
        this.locks = locks;
        this.printer = printer;
        this.runner = runner;
    }

    // ================================================================== envoi

    Map<String, Object> send(String tableId, Map<String, Object> body, ApiSessions.ApiSession user)
            throws ApiServer.ApiError, BasicException {
        if (!user.hasPermission(PERMISSION)) {
            throw new ApiServer.ApiError(403, "forbidden", "Permission " + PERMISSION + " requise pour envoyer en cuisine.");
        }
        Object sid = body.get("sendId");
        if (!(sid instanceof String) || !SEND_ID.matcher((String) sid).matches()) {
            throw new ApiServer.ApiError(400, "invalid_send_id", "Champ 'sendId' obligatoire : 8 a 100 caracteres "
                    + "(lettres, chiffres, - ou _), unique par envoi et IDENTIQUE en cas de nouvel essai.");
        }
        String sendId = (String) sid;
        if (printer == null) {
            throw new ApiServer.ApiError(503, "kitchen_printing_unavailable", "Impression cuisine indisponible sur cette caisse.");
        }
        String tableName;
        synchronized (data) {
            tableName = orders.tableName(tableId);
        }

        lockOrFail(tableId, tableName, user);
        try {
            // ---- 1. preparation et "pre-enregistrement" (rien n'est encore imprime)
            MobilePrinter.Prepared prepared;
            List<String> lineUids;
            synchronized (data) {
                TableTicketStore.Snapshot snap = store.load(tableId);
                Map<String, Object> existing = snap == null ? null : findSend(snap.getTicket(), sendId);
                if (existing != null) {
                    // Renvoi de la meme demande (coupure Wi-Fi) : on n'imprime RIEN.
                    return Json.obj("duplicate", true, "send", publicSend(existing),
                            "order", orders.orderJson(tableId, tableName, snap));
                }
                if (snap == null) {
                    throw new ApiServer.ApiError(409, "no_order", "Aucune commande sur " + tableName + ".");
                }
                TicketInfo ticket = snap.getTicket();
                List<TicketLineInfo> selected = new ArrayList<>();
                int toVerify = 0;
                for (TicketLineInfo l : ticket.getLines()) {
                    String st = MobileOrderService.kitchenStatus(l);
                    if ("a_envoyer".equals(st)) {
                        selected.add(l);
                    } else if ("a_verifier".equals(st)) {
                        toVerify++;
                    }
                }
                if (selected.isEmpty()) {
                    throw new ApiServer.ApiError(409, "nothing_to_send", "Aucune nouvelle ligne a envoyer en cuisine sur "
                            + tableName + "." + (toVerify > 0 ? " " + toVerify + " ligne(s) sont \"a verifier\" : "
                            + "indiquez si le bon est sorti ou demandez une reimpression." : ""),
                            Json.obj("order", orders.orderJson(tableId, tableName, snap)));
                }

                // Copie imprimee : seules les lignes choisies ont sendstatus=No (le modele
                // n'imprime que celles-la) ; textes proteges pour le XML du bon.
                lineUids = new ArrayList<>();
                for (TicketLineInfo l : selected) {
                    lineUids.add(l.getProperty(MobileOrderService.P_LINE_UID));
                }
                TicketInfo copy = printableCopy(ticket, lineUids, user);
                try {
                    prepared = printer.prepare(copy, xml(tableName),
                            data.getDataLogicSystem().getResourceAsXML(TillPrinter.KITCHEN_RESOURCE), null);
                } catch (MobilePrinter.NotPrintedException e) {
                    throw new ApiServer.ApiError(503, "kitchen_printer_not_ready", e.getMessage()
                            + " Les lignes restent \"a envoyer\".");
                }

                // Pre-enregistrement : lignes "a verifier" + envoi "en_cours".
                List<Map<String, Object>> items = new ArrayList<>();
                for (TicketLineInfo l : selected) {
                    l.setProperty("sendstatus", VERIFY);
                    l.setProperty(P_LINE_SEND_ID, sendId);
                    items.add(Json.obj("name", l.getProductName(), "quantity", l.getMultiply(),
                            "note", l.getProperty(MobileOrderService.P_LINE_NOTE)));
                }
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("id", sendId);
                rec.put("status", "en_cours");
                rec.put("at", System.currentTimeMillis());
                rec.put("by", user.getUserName());
                rec.put("printer", prepared.printers());
                rec.put("items", items);
                List<Map<String, Object>> sends = sends(ticket);
                sends.add(rec);
                saveSends(ticket, sends);
                try {
                    store.update(tableId, ticket, snap.getVersion(), user.getUserName(), TableLocks.Source.MOBILE.label());
                } catch (OrderConflictException e) {
                    throw new ApiServer.ApiError(409, "version_conflict", "La commande de " + tableName
                            + " vient d'etre modifiee ailleurs. Rien n'a ete imprime ; reessayez.",
                            Json.obj("order", orders.orderJson(tableId, tableName, e.getCurrent())));
                }
                inProgress.add(sendId);
            }

            // ---- 2. impression (hors verrou de base : ne bloque pas les autres tables)
            PrintRunner.Outcome outcome = runner.run(prepared, "le bon cuisine");
            String status = outcome.status;
            String message = outcome.message;
            // jamais parti (impression annulee avant de commencer) : lignes de nouveau "a envoyer"
            String lineStatus = "non_imprime".equals(status) ? "No" : null;

            // ---- 3. resultat enregistre
            synchronized (data) {
                try {
                    return Json.obj("duplicate", false,
                            "send", publicSend(finish(tableId, sendId, status, message, lineStatus, user, false)),
                            "order", orders.orderJson(tableId, tableName, store.load(tableId)));
                } finally {
                    inProgress.remove(sendId);
                }
            }
        } finally {
            locks.releaseApi(tableId);
            locks.fireChanged(tableId);
        }
    }

    // ================================================================== verification manuelle

    /** action "printed" : le bon est sorti ; action "reprint" : remettre les lignes "a envoyer". */
    Map<String, Object> resolve(String tableId, String sendId, Map<String, Object> body, ApiSessions.ApiSession user)
            throws ApiServer.ApiError, BasicException {
        if (!user.hasPermission(PERMISSION)) {
            throw new ApiServer.ApiError(403, "forbidden", "Permission " + PERMISSION + " requise.");
        }
        Object action = body.get("action");
        if (!"printed".equals(action) && !"reprint".equals(action)) {
            throw new ApiServer.ApiError(400, "invalid_action", "Champ 'action' : \"printed\" (le bon est sorti) "
                    + "ou \"reprint\" (remettre les lignes a envoyer).");
        }
        String tableName;
        synchronized (data) {
            tableName = orders.tableName(tableId);
        }
        lockOrFail(tableId, tableName, user);
        try {
            synchronized (data) {
                if (inProgress.contains(sendId)) {
                    throw new ApiServer.ApiError(409, "send_in_progress", "Impression en cours, attendez son resultat.");
                }
                boolean printed = "printed".equals(action);
                Map<String, Object> rec = finish(tableId, sendId,
                        printed ? "confirme_manuellement" : "reimpression_demandee",
                        printed ? "Sortie du bon confirmee par " + user.getUserName() + "."
                                : "Reimpression demandee par " + user.getUserName() + " : lignes remises \"a envoyer\".",
                        printed ? "OK" : "No", user, true);
                return Json.obj("send", publicSend(rec), "order", orders.orderJson(tableId, tableName, store.load(tableId)));
            }
        } finally {
            locks.releaseApi(tableId);
            locks.fireChanged(tableId);
        }
    }

    /**
     * Enregistre l'issue d'un envoi. {@code forcedLineStatus} null : OK si le
     * bon est parti, Verify sinon. {@code manual} : verification du serveur,
     * possible seulement pour un envoi non confirme par l'imprimante.
     */
    private Map<String, Object> finish(String tableId, String sendId, String status, String message,
            String forcedLineStatus, ApiSessions.ApiSession user, boolean manual)
            throws ApiServer.ApiError, BasicException {
        for (int attempt = 0; attempt < 3; attempt++) {
            TableTicketStore.Snapshot snap = store.load(tableId);
            Map<String, Object> rec = snap == null ? null : findSend(snap.getTicket(), sendId);
            if (rec == null) {
                throw new ApiServer.ApiError(404, "send_not_found", "Envoi cuisine introuvable (commande encaissee ou annulee ?).");
            }
            String current = displayStatus(rec);
            if (manual && !"a_verifier".equals(current) && !"envoye".equals(current)) {
                throw new ApiServer.ApiError(409, "send_not_resolvable", "Cet envoi est deja \"" + current
                        + "\" : rien a verifier.", Json.obj("send", publicSend(rec)));
            }
            String lineStatus = forcedLineStatus != null ? forcedLineStatus
                    : ("a_verifier".equals(status) ? VERIFY : "OK");
            TicketInfo t = snap.getTicket();
            for (TicketLineInfo l : t.getLines()) {
                if (sendId.equals(l.getProperty(P_LINE_SEND_ID))
                        && (VERIFY.equals(l.getProperty("sendstatus")) || (manual && "OK".equals(l.getProperty("sendstatus"))))) {
                    l.setProperty("sendstatus", lineStatus);
                    if ("No".equals(lineStatus) && manual) {
                        l.setProperty(P_LINE_REPRINT, "1");          // prochain bon : DUPLICATA
                    } else if ("OK".equals(lineStatus)) {
                        l.getProperties().remove(P_LINE_REPRINT);
                    }
                }
            }
            List<Map<String, Object>> sends = sends(t);
            for (Map<String, Object> s : sends) {
                if (sendId.equals(s.get("id"))) {
                    s.put("status", status);
                    s.put("message", message);
                    s.put(manual ? "resolvedBy" : "finishedAt", manual ? user.getUserName() : (Object) System.currentTimeMillis());
                    if (manual) {
                        s.put("resolvedAt", System.currentTimeMillis());
                    }
                    rec = s;
                }
            }
            saveSends(t, sends);
            try {
                store.update(tableId, t, snap.getVersion(), user.getUserName(), TableLocks.Source.MOBILE.label());
                return rec;
            } catch (OrderConflictException e) {
                // modifiee sur un autre poste entre-temps : on relit et on recommence
            }
        }
        throw new ApiServer.ApiError(409, "version_conflict", "La commande change sans cesse : reessayez.");
    }

    // ================================================================== outils

    private void lockOrFail(String tableId, String tableName, ApiSessions.ApiSession user) throws ApiServer.ApiError {
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
                        + h.getWho() + ". Rien n'a ete imprime : reessayez quand elle sera refermee.", extra);
            }
            throw new ApiServer.ApiError(409, "table_busy", "La table " + tableName
                    + " est en cours de modification depuis un autre telephone. Rien n'a ete imprime : reessayez.", extra);
        }
    }

    /** Copie de la commande pour le bon cuisine : lignes choisies "No", textes echappes pour le XML. */
    private static TicketInfo printableCopy(TicketInfo ticket, List<String> uids, ApiSessions.ApiSession user)
            throws ApiServer.ApiError {
        TicketInfo copy = escapedCopy(ticket, user);
        for (TicketLineInfo l : copy.getLines()) {
            if (uids.contains(l.getProperty(MobileOrderService.P_LINE_UID))) {
                l.setProperty("sendstatus", "No");
            } else if ("No".equals(l.getProperty("sendstatus"))) {
                l.setProperty("sendstatus", "-");   // jamais imprimee dans cet envoi
            }
        }
        return copy;
    }

    /**
     * Copie independante de la commande, prete a etre imprimee : serveur = celui
     * du telephone, textes (produits, notes, attributs) proteges pour le XML du bon.
     */
    static TicketInfo escapedCopy(TicketInfo ticket, ApiSessions.ApiSession user) throws ApiServer.ApiError {
        TicketInfo copy;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(ticket);
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                copy = (TicketInfo) in.readObject();
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new ApiServer.ApiError(500, "internal_error", "Copie de la commande impossible.");
        }
        copy.setUser(new UserInfo(user.getUserId(), xml(user.getUserName())));
        for (TicketLineInfo l : copy.getLines()) {
            for (String key : new String[] {"product.name", MobileOrderService.P_LINE_NOTE, "product.attsetdesc"}) {
                String v = l.getProperty(key);
                if (v != null) {
                    l.setProperty(key, xml(v));
                }
            }
        }
        return copy;
    }

    /** Protege un texte insere dans le XML du bon (TicketParser). */
    static String xml(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '$': sb.append("&#36;"); break;    // pas d'interpretation Velocity
                case '#': sb.append("&#35;"); break;
                default: sb.append(Character.isISOControl(c) ? ' ' : c);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> sends(TicketInfo t) {
        String raw = t.getProperty(P_SENDS);
        List<Map<String, Object>> l = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            try {
                for (Object o : (List<Object>) Json.parse(raw)) {
                    l.add((Map<String, Object>) o);
                }
            } catch (Json.ParseException | ClassCastException e) {
                LOG.log(Level.WARNING, "Historique cuisine illisible, ignore", e);
            }
        }
        return l;
    }

    private static void saveSends(TicketInfo t, List<Map<String, Object>> sends) {
        while (sends.size() > SENDS_KEPT) {
            sends.remove(0);
        }
        t.setProperty(P_SENDS, Json.write(sends));
    }

    private static Map<String, Object> findSend(TicketInfo t, String sendId) {
        for (Map<String, Object> s : sends(t)) {
            if (sendId.equals(s.get("id"))) {
                return s;
            }
        }
        return null;
    }

    /** "en_cours" enregistre mais plus en cours (caisse arretee pendant l'impression) = a verifier. */
    private String displayStatus(Map<String, Object> rec) {
        String st = (String) rec.get("status");
        return "en_cours".equals(st) && !inProgress.contains(rec.get("id")) ? "a_verifier" : st;
    }

    private Map<String, Object> publicSend(Map<String, Object> rec) {
        Map<String, Object> m = new LinkedHashMap<>(rec);
        m.put("status", displayStatus(rec));
        if ("en_cours".equals(rec.get("status")) && "a_verifier".equals(m.get("status"))) {
            m.put("message", "Impression interrompue (caisse arretee ?) : verifiez si le bon est sorti.");
        }
        return m;
    }

    /** Historique pour l'affichage (le plus recent d'abord). Appele par MobileOrderService. */
    static List<Object> sendsJson(TicketInfo t) {
        List<Object> out = new ArrayList<>();
        List<Map<String, Object>> l = sends(t);
        for (int i = l.size() - 1; i >= 0; i--) {
            Map<String, Object> m = new LinkedHashMap<>(l.get(i));
            if ("en_cours".equals(m.get("status")) && !IN_PROGRESS_GLOBAL.contains(m.get("id"))) {
                m.put("status", "a_verifier");
                m.put("message", "Impression interrompue (caisse arretee ?) : verifiez si le bon est sorti.");
            }
            out.add(m);
        }
        return out;
    }

    // Envois en cours dans ce programme (pour l'affichage depuis MobileOrderService).
    static final Set<String> IN_PROGRESS_GLOBAL = Collections.synchronizedSet(new HashSet<String>());
}
