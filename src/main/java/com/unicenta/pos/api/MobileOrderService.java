//    OLPOS (derive d'uniCenta oPOS) - API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.api;

import com.unicenta.basic.BasicException;
import com.unicenta.data.loader.Datas;
import com.unicenta.data.loader.PreparedSentence;
import com.unicenta.data.loader.SerializerReadBasic;
import com.unicenta.data.loader.SerializerWriteString;
import com.unicenta.format.Formats;
import com.unicenta.pos.forms.DataLogicSales;
import com.unicenta.pos.forms.DataLogicSystem;
import com.unicenta.pos.sales.TaxesException;
import com.unicenta.pos.sales.TaxesLogic;
import com.unicenta.pos.sales.restaurant.OrderConflictException;
import com.unicenta.pos.sales.restaurant.TableLocks;
import com.unicenta.pos.sales.restaurant.TableTicketStore;
import com.unicenta.pos.ticket.ProductInfoExt;
import com.unicenta.pos.ticket.TaxInfo;
import com.unicenta.pos.ticket.TicketInfo;
import com.unicenta.pos.ticket.TicketLineInfo;
import com.unicenta.pos.util.RoundUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Creation et modification des commandes d'une table depuis le telephone.
 *
 * <p>Regles :</p>
 * <ul>
 *   <li><b>Prix et taxes calcules par la caisse</b> : le telephone envoie un
 *       produit et une quantite, jamais un prix. Ligne creee comme a la caisse
 *       ({@code new TicketLineInfo(produit, qte, prixVente, taxe, attributs)},
 *       taxe via {@link TaxesLogic}).</li>
 *   <li><b>Permissions</b> : mobile.Access pour ajouter des produits ;
 *       sales.EditLines (comme a la caisse) pour modifier ou supprimer une ligne.</li>
 *   <li><b>Pas d'ecrasement</b> : chaque demande indique la version de la
 *       commande sur laquelle elle s'appuie ({@code baseVersion}). Si la
 *       commande a change entre-temps, rien n'est enregistre et la reponse
 *       contient la commande a jour (409).</li>
 *   <li><b>Table ouverte a la caisse</b> : refus immediat (423) avec le nom de
 *       l'utilisateur de la caisse.</li>
 *   <li><b>Requete repetee</b> (coupure Wi-Fi) : meme {@code requestId} =>
 *       reconnue, pas appliquee une seconde fois.</li>
 *   <li><b>Tout ou rien</b> : si une operation d'une demande est invalide,
 *       aucune n'est appliquee.</li>
 *   <li>Lignes deja envoyees en cuisine : non modifiables depuis le telephone.</li>
 * </ul>
 */
final class MobileOrderService {

    static final String PERMISSION_EDIT_LINES = "sales.EditLines";
    static final String P_LINE_UID = "line.uid";
    static final String P_LINE_ADDED_BY = "line.addedBy";
    static final String P_LINE_NOTE = "notes";                 // meme propriete que script.AddLineNote
    static final String P_REQUESTS = "mobile.requests";
    static final int MAX_OPERATIONS = 50;
    static final int MAX_QUANTITY = 999;
    static final int MAX_NOTE = 200;
    static final int REQUESTS_KEPT_IN_ORDER = 30;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,100}");

    private final MobileDataService data;      // son moniteur protege la connexion de l'API
    private final TableTicketStore store;
    private final TableLocks locks;
    private final ProcessedRequests processed = new ProcessedRequests(5000, 24L * 3600 * 1000);
    private final long lockWaitMillis;

    MobileOrderService(MobileDataService data, TableLocks locks, long lockWaitMillis) {
        this.data = data;
        this.store = new TableTicketStore(data.getSession());
        this.locks = locks;
        this.lockWaitMillis = lockWaitMillis;
    }

    // ================================================================== lecture

    Map<String, Object> getOrder(String tableId) throws ApiServer.ApiError, BasicException {
        synchronized (data) {
            String tableName = tableName(tableId);
            TableTicketStore.Snapshot snap = store.load(tableId);
            return Json.obj("order", orderJson(tableId, tableName, snap));
        }
    }

    // ================================================================== modification

    /** Operation demandee par le telephone, deja validee. */
    private static final class Op {
        String type;        // add | setQuantity | setNote | remove
        String productId;
        String lineId;
        Integer quantity;
        String note;
        boolean hasNote;
        int index;
    }

    Map<String, Object> applyChanges(String tableId, Map<String, Object> body, ApiSessions.ApiSession user)
            throws ApiServer.ApiError, BasicException {

        // ---- 1. validation de la demande (avant tout verrou)
        Object rid = body.get("requestId");
        if (!(rid instanceof String) || !REQUEST_ID.matcher((String) rid).matches()) {
            throw bad("invalid_request_id", "Champ 'requestId' obligatoire : 8 a 100 caracteres (lettres, chiffres, - ou _), "
                    + "unique pour chaque modification et IDENTIQUE en cas de nouvel essai.");
        }
        String requestId = (String) rid;
        Object bv = body.get("baseVersion");
        if (!(bv instanceof Long) || (Long) bv < 0) {
            throw bad("invalid_base_version", "Champ 'baseVersion' obligatoire : version de la commande lue "
                    + "(0 si la table etait libre).");
        }
        long baseVersion = (Long) bv;
        // OLPOS : la commande visee est identifiee par son identifiant ET sa version.
        Object bo = body.get("baseOrderId");
        String baseOrderId = bo instanceof String && !((String) bo).isEmpty() ? (String) bo : null;
        if (baseVersion > 0 && baseOrderId == null) {
            throw bad("invalid_base_order_id", "Champ 'baseOrderId' obligatoire quand baseVersion > 0 : "
                    + "identifiant de la commande lue (une table peut porter une nouvelle commande).");
        }
        List<Op> ops = parseOperations(body.get("operations"));
        for (Op op : ops) {
            if (!"add".equals(op.type) && !user.hasPermission(PERMISSION_EDIT_LINES)) {
                throw new ApiServer.ApiError(403, "forbidden",
                        "Permission " + PERMISSION_EDIT_LINES + " requise pour modifier ou supprimer une ligne.");
            }
        }
        String fingerprint = fingerprint(tableId, body);

        String tableName;
        synchronized (data) {
            tableName = tableName(tableId);
        }

        // ---- 2. requete deja traitee ? (reponse immediate, sans verrou)
        Map<String, Object> dup = duplicateIfKnown(requestId, tableId, tableName, fingerprint);
        if (dup != null) {
            return dup;
        }

        // ---- 3. verrou de la table
        TableLocks.Holder holder;
        try {
            holder = locks.tryAcquireApi(tableId, user.getUserName(), lockWaitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiServer.ApiError(503, "interrupted", "Requete interrompue, reessayez.");
        }
        if (holder != null) {
            Map<String, Object> extra = Json.obj("lockedBy", holderJson(holder));
            if (holder.getSource() == TableLocks.Source.CAISSE) {
                throw new ApiServer.ApiError(423, "table_locked", "La table " + tableName
                        + " est ouverte a la caisse par " + holder.getWho() + " depuis " + time(holder.getSince())
                        + ". Reessayez quand elle sera refermee a la caisse. Rien n'a ete enregistre.", extra);
            }
            throw new ApiServer.ApiError(409, "table_busy", "La table " + tableName
                    + " est en cours de modification depuis un autre telephone. Reessayez. Rien n'a ete enregistre.", extra);
        }

        Map<String, Object> result;
        try {
            synchronized (data) {
                // un nouvel essai de la meme requete a pu se terminer pendant l'attente du verrou
                dup = duplicateIfKnown(requestId, tableId, tableName, fingerprint);
                if (dup != null) {
                    return dup;
                }
                result = applyLocked(tableId, tableName, requestId, fingerprint, baseOrderId, baseVersion, ops, user);
            }
        } finally {
            locks.releaseApi(tableId);
        }
        locks.fireChanged(tableId);   // rafraichit le plan de salle de la caisse
        return result;
    }

    private Map<String, Object> applyLocked(String tableId, String tableName, String requestId, String fingerprint,
            String baseOrderId, long baseVersion, List<Op> ops, ApiSessions.ApiSession user)
            throws ApiServer.ApiError, BasicException {

        TableTicketStore.Snapshot snap = store.load(tableId);

        // Deuxieme protection contre les doublons : la liste est dans la commande
        // elle-meme (survit a un redemarrage de la caisse).
        if (snap != null && requestIds(snap.getTicket()).contains(requestId)) {
            processed.put(requestId, tableId, fingerprint, snap.getVersion());
            return Json.obj("applied", false, "duplicate", true, "order", orderJson(tableId, tableName, snap));
        }

        long current = snap == null ? 0 : snap.getVersion();
        if (baseVersion != current) {
            throw conflict(tableId, tableName, baseVersion, snap);
        }
        if (snap != null && !snap.getOrderId().equals(baseOrderId)) {
            // meme numero de version, mais ce n'est plus la meme commande (table reutilisee)
            throw replaced(tableId, tableName, snap);
        }

        TicketInfo ticket = snap == null ? new TicketInfo() : snap.getTicket();
        if (snap == null) {
            // comme a la caisse : le nom de la commande commence par le serveur
            ticket.setUser(new com.unicenta.pos.ticket.UserInfo(user.getUserId(), user.getUserName()));
        }
        DataLogicSales dlSales = data.getDataLogicSales();
        TaxesLogic taxes = taxesLogic(dlSales);

        // Les lignes visees sont resolues AVANT d'appliquer quoi que ce soit
        // (supprimer une ligne decale les positions des suivantes).
        Map<Op, TicketLineInfo> targets = new IdentityHashMap<>();
        for (Op op : ops) {
            if (op.lineId != null) {
                TicketLineInfo line = findLine(ticket, op.lineId);
                if (line == null) {
                    throw bad("unknown_line", "Operation " + op.index + " : ligne '" + op.lineId + "' introuvable dans la commande.");
                }
                targets.put(op, line);
            }
        }

        List<Object[]> removedLog = new ArrayList<>();
        for (Op op : ops) {
            switch (op.type) {
                case "add":
                    ticket.addLine(newLine(dlSales, taxes, ticket, op, user));
                    break;
                case "setQuantity": {
                    TicketLineInfo line = editable(targets.get(op), op);
                    line.setMultiply(op.quantity);
                    break;
                }
                case "setNote": {
                    TicketLineInfo line = editable(targets.get(op), op);
                    setNote(line, op.note);
                    break;
                }
                case "remove": {
                    TicketLineInfo line = editable(targets.get(op), op);
                    int i = ticket.getLines().indexOf(line);
                    if (i < 0) {
                        throw bad("unknown_line", "Operation " + op.index + " : ligne deja supprimee dans cette demande.");
                    }
                    removedLog.add(new Object[] {user.getUserName(), "No Sale", line.getProductID(),
                        line.getProductName(), line.getMultiply()});
                    boolean com = line.isProductCom();
                    ticket.removeLine(i);
                    // comme a la caisse : supprimer un produit supprime aussi ses lignes "auxiliaires"
                    while (!com && i < ticket.getLinesCount() && ticket.getLine(i).isProductCom()) {
                        ticket.removeLine(i);
                    }
                    break;
                }
                default:
                    throw bad("invalid_operation", "Operation inconnue : " + op.type);
            }
        }

        // identifiants stables pour toutes les lignes (y compris celles saisies a la caisse)
        for (TicketLineInfo line : ticket.getLines()) {
            if (line.getProperty(P_LINE_UID) == null) {
                line.setProperty(P_LINE_UID, UUID.randomUUID().toString());
            }
        }
        rememberRequest(ticket, requestId);
        try {
            taxes.calculateTaxes(ticket);
        } catch (TaxesException e) {
            throw new ApiServer.ApiError(500, "tax_error", "Calcul des taxes impossible : " + e.getMessage());
        }

        long newVersion;
        try {
            newVersion = snap == null
                    ? store.create(tableId, ticket, user.getUserName(), TableLocks.Source.MOBILE.label())
                    : store.update(tableId, ticket, snap.getOrderId(), current, user.getUserName(),
                            TableLocks.Source.MOBILE.label());
        } catch (OrderConflictException e) {
            // modifiee sur un autre poste entre la lecture et l'ecriture
            throw conflict(tableId, tableName, baseVersion, e.getCurrent());
        }

        if (snap == null) {
            // comme la caisse a l'ouverture d'une table : serveur et ticket affiches sur la table
            updatePlace(tableId, user.getUserName(), ticket.getId());
        }
        DataLogicSystem dlSystem = data.getDataLogicSystem();
        for (Object[] removed : removedLog) {
            dlSystem.execLineRemoved(removed);   // meme journal LINEREMOVED que la caisse
        }
        processed.put(requestId, tableId, fingerprint, newVersion);

        TableTicketStore.Snapshot saved = store.load(tableId);
        return Json.obj("applied", true, "duplicate", false, "order", orderJson(tableId, tableName, saved));
    }

    private Map<String, Object> duplicateIfKnown(String requestId, String tableId, String tableName, String fingerprint)
            throws ApiServer.ApiError, BasicException {
        ProcessedRequests.Entry e = processed.get(requestId);
        if (e == null) {
            return null;
        }
        if (!e.tableId.equals(tableId) || !e.fingerprint.equals(fingerprint)) {
            throw new ApiServer.ApiError(409, "request_id_reused", "Ce requestId a deja servi pour une autre demande. "
                    + "Generez un nouvel identifiant pour chaque nouvelle modification.");
        }
        synchronized (data) {
            return Json.obj("applied", false, "duplicate", true,
                    "order", orderJson(tableId, tableName, store.load(tableId)));
        }
    }

    private ApiServer.ApiError conflict(String tableId, String tableName, long baseVersion, TableTicketStore.Snapshot cur)
            throws BasicException {
        Map<String, Object> extra = Json.obj("order", orderJson(tableId, tableName, cur));
        if (cur == null) {
            return new ApiServer.ApiError(409, "order_closed", "La commande de " + tableName
                    + " n'existe plus (encaissee, deplacee ou annulee a la caisse). Vos modifications n'ont pas ete enregistrees.",
                    extra);
        }
        String who = cur.describeLastChange();
        if (baseVersion == 0) {
            return new ApiServer.ApiError(409, "version_conflict", "Une commande existe deja sur " + tableName
                    + (who.isEmpty() ? "" : " (" + who + ")") + ". Affichez-la puis refaites vos ajouts. "
                    + "Vos modifications n'ont pas ete enregistrees.", extra);
        }
        return new ApiServer.ApiError(409, "version_conflict", "La commande de " + tableName + " a ete modifiee"
                + (who.isEmpty() ? "" : " " + who) + " depuis votre derniere lecture. Affichez la version a jour "
                + "puis refaites vos modifications. Rien n'a ete enregistre.", extra);
    }

    private ApiServer.ApiError replaced(String tableId, String tableName, TableTicketStore.Snapshot cur)
            throws BasicException {
        return new ApiServer.ApiError(409, "order_replaced", "La commande que vous modifiiez sur " + tableName
                + " a ete encaissee ou annulee, et une NOUVELLE commande est ouverte sur cette table. "
                + "Rien n'a ete enregistre : affichez la nouvelle commande avant de la modifier.",
                Json.obj("order", orderJson(tableId, tableName, cur)));
    }

    // ================================================================== operations

    private List<Op> parseOperations(Object raw) throws ApiServer.ApiError {
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            throw bad("invalid_operations", "Champ 'operations' obligatoire : liste non vide.");
        }
        List<?> list = (List<?>) raw;
        if (list.size() > MAX_OPERATIONS) {
            throw bad("invalid_operations", "Au plus " + MAX_OPERATIONS + " operations par demande.");
        }
        List<Op> ops = new ArrayList<>();
        int index = 0;
        for (Object o : list) {
            if (!(o instanceof Map)) {
                throw bad("invalid_operation", "Operation " + index + " : objet attendu.");
            }
            Map<?, ?> m = (Map<?, ?>) o;
            Op op = new Op();
            op.index = index;
            Object type = m.get("op");
            if (!(type instanceof String)) {
                throw bad("invalid_operation", "Operation " + index + " : champ 'op' obligatoire (add, setQuantity, setNote, remove).");
            }
            op.type = (String) type;
            switch (op.type) {
                case "add":
                    op.productId = requiredString(m, "productId", index);
                    op.quantity = m.containsKey("quantity") ? quantity(m.get("quantity"), index) : 1;
                    if (m.containsKey("note")) {
                        op.note = note(m.get("note"), index);
                        op.hasNote = true;
                    }
                    break;
                case "setQuantity":
                    op.lineId = requiredString(m, "lineId", index);
                    op.quantity = quantity(m.get("quantity"), index);
                    break;
                case "setNote":
                    op.lineId = requiredString(m, "lineId", index);
                    op.note = note(m.get("note"), index);
                    op.hasNote = true;
                    break;
                case "remove":
                    op.lineId = requiredString(m, "lineId", index);
                    break;
                default:
                    throw bad("invalid_operation", "Operation " + index + " : '" + op.type
                            + "' inconnue (add, setQuantity, setNote, remove).");
            }
            ops.add(op);
            index++;
        }
        return ops;
    }

    private TicketLineInfo newLine(DataLogicSales dlSales, TaxesLogic taxes, TicketInfo ticket, Op op,
            ApiSessions.ApiSession user) throws BasicException, ApiServer.ApiError {
        ProductInfoExt product = findProduct(op.productId);
        if (product == null) {
            throw bad("unknown_product", "Operation " + op.index + " : produit '" + op.productId + "' introuvable.");
        }
        String unsupported = null;
        if (product.isVprice()) {
            unsupported = "a prix variable";
        } else if (product.isScale()) {
            unsupported = "vendu au poids";
        } else if (product.isCom()) {
            unsupported = "auxiliaire (rattache a une autre ligne)";
        } else if (product.isVerpatrib()) {
            unsupported = "avec attributs obligatoires";
        }
        if (unsupported != null) {
            throw new ApiServer.ApiError(422, "product_not_supported", "Operation " + op.index + " : le produit '"
                    + product.getName() + "' est " + unsupported + " ; il doit etre saisi a la caisse.");
        }
        // Meme construction que JPanelTicket.addTicketLine : prix de vente HT de la base,
        // taxe selon la categorie de taxe du produit et le client du ticket.
        TaxInfo tax = taxes.getTaxInfo(product.getTaxCategoryID(), ticket.getCustomer());
        TicketLineInfo line = new TicketLineInfo(product, op.quantity, product.getPriceSell(), tax,
                (Properties) product.getProperties().clone());
        line.setProperty(P_LINE_UID, UUID.randomUUID().toString());
        line.setProperty(P_LINE_ADDED_BY, user.getUserName());
        if (op.hasNote) {
            setNote(line, op.note);
        }
        return line;
    }

    /** Produit par identifiant (uniCenta 5.0 : lecture directe dans la table products). */
    private ProductInfoExt findProduct(String productId) throws BasicException {
        return data.getDataLogicSales().getProductInfo(productId);
    }

    private static TicketLineInfo editable(TicketLineInfo line, Op op) throws ApiServer.ApiError {
        if (isSent(line)) {
            throw new ApiServer.ApiError(409, "line_already_sent", "Operation " + op.index + " : la ligne '"
                    + line.getProductName() + "' a deja ete envoyee en cuisine. Ajoutez une nouvelle ligne, "
                    + "ou faites la correction a la caisse. Rien n'a ete enregistre.");
        }
        return line;
    }

    /** Ligne deja partie en cuisine (voir KitchenService). */
    static boolean isSent(TicketLineInfo line) {
        return KitchenService.isSent(line);
    }

    /** Statut cuisine d'une ligne pour le telephone : null (pas un plat cuisine), a_envoyer, envoye, a_verifier. */
    static String kitchenStatus(TicketLineInfo line) {
        return KitchenService.kitchenStatus(line);
    }

    private static void setNote(TicketLineInfo line, String note) {
        if (note == null || note.isEmpty()) {
            line.getProperties().remove(P_LINE_NOTE);
        } else {
            line.setProperty(P_LINE_NOTE, note);
        }
    }

    /** Identifiant de ligne : line.uid, ou "idx:N" pour une ligne saisie a la caisse et pas encore numerotee. */
    static String lineId(TicketInfo t, int index) {
        String uid = t.getLine(index).getProperty(P_LINE_UID);
        return uid != null ? uid : "idx:" + index;
    }

    private static TicketLineInfo findLine(TicketInfo t, String id) {
        for (int i = 0; i < t.getLinesCount(); i++) {
            if (id.equals(lineId(t, i))) {
                return t.getLine(i);
            }
        }
        return null;
    }

    private static List<String> requestIds(TicketInfo t) {
        String s = t.getProperty(P_REQUESTS);
        return s == null || s.isEmpty() ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }

    private static void rememberRequest(TicketInfo t, String requestId) {
        List<String> ids = requestIds(t);
        ids.add(requestId);
        while (ids.size() > REQUESTS_KEPT_IN_ORDER) {
            ids.remove(0);
        }
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        t.setProperty(P_REQUESTS, sb.toString());
    }

    // ================================================================== JSON

    Map<String, Object> orderJson(String tableId, String tableName, TableTicketStore.Snapshot snap)
            throws BasicException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tableId", tableId);
        m.put("tableName", tableName);
        TableLocks.Holder h = locks.holder(tableId);
        m.put("lockedBy", h == null || h.getSource() != TableLocks.Source.CAISSE ? null : holderJson(h));
        if (snap == null) {
            m.put("exists", false);
            m.put("orderNumber", null);
            m.put("version", 0L);
            m.put("lines", new ArrayList<Object>());
            m.put("subtotal", 0.0);
            m.put("taxes", 0.0);
            m.put("total", 0.0);
            m.put("totalDisplay", Formats.CURRENCY.formatValue(0.0));
            m.put("kitchenSends", new ArrayList<Object>());
            return m;
        }
        TicketInfo t = snap.getTicket();
        try {
            taxesLogic(data.getDataLogicSales()).calculateTaxes(t);
        } catch (TaxesException | RuntimeException e) {
            // affichage seulement : les totaux par ligne restent corrects
        }
        m.put("exists", true);
        // numero visible 1-100 (reutilise) : jamais utilise pour retrouver la commande,
        // identifiee par la table et la version
        m.put("orderNumber", com.unicenta.pos.sales.OrderNumbers.numberOf(t));
        m.put("orderId", t.getId());
        m.put("orderDate", t.getDate() == null ? null : t.getDate().getTime());
        m.put("version", snap.getVersion());
        m.put("updatedBy", snap.getUpdatedBy());
        m.put("updatedSource", snap.getUpdatedSource());
        m.put("updatedAt", snap.getUpdatedAt());
        List<Object> lines = new ArrayList<>();
        for (int i = 0; i < t.getLinesCount(); i++) {
            TicketLineInfo l = t.getLine(i);
            Map<String, Object> lj = new LinkedHashMap<>();
            lj.put("lineId", lineId(t, i));
            lj.put("productId", l.getProductID());
            lj.put("name", l.getProductName());
            lj.put("quantity", l.getMultiply());
            lj.put("unitPrice", RoundUtils.round(l.getPrice()));
            lj.put("unitPriceTaxed", RoundUtils.round(l.getPriceTax()));
            lj.put("taxRate", l.getTaxRate());
            lj.put("total", RoundUtils.round(l.getValue()));
            lj.put("note", l.getProperty(P_LINE_NOTE));
            lj.put("kitchen", l.isProductKitchen());
            lj.put("sent", isSent(l));
            lj.put("kitchenStatus", kitchenStatus(l));
            lj.put("kitchenSendId", l.getProperty(KitchenService.P_LINE_SEND_ID));
            lj.put("addedBy", l.getProperty(P_LINE_ADDED_BY));
            lines.add(lj);
        }
        m.put("lines", lines);
        m.put("subtotal", RoundUtils.round(t.getSubTotal()));
        m.put("taxes", RoundUtils.round(t.getTax()));
        m.put("total", RoundUtils.round(t.getTotal()));
        m.put("totalDisplay", Formats.CURRENCY.formatValue(t.getTotal()));
        m.put("kitchenSends", KitchenService.sendsJson(t));
        return m;
    }

    static Map<String, Object> holderJson(TableLocks.Holder h) {
        return Json.obj("source", h.getSource().label(), "who", h.getWho(), "since", h.getSince());
    }

    // ================================================================== outils

    @SuppressWarnings("unchecked")
    private static TaxesLogic taxesLogic(DataLogicSales dlSales) throws BasicException {
        return new TaxesLogic(dlSales.getTaxList().list());
    }

    String tableName(String tableId) throws ApiServer.ApiError, BasicException {
        Object[] row = (Object[]) new PreparedSentence(data.getSession(),
                "SELECT NAME FROM PLACES WHERE ID = ?", SerializerWriteString.INSTANCE,
                new SerializerReadBasic(new Datas[] {Datas.STRING})).find(tableId);
        if (row == null) {
            throw new ApiServer.ApiError(404, "table_not_found", "Table inconnue : " + tableId);
        }
        return (String) row[0];
    }

    private void updatePlace(String tableId, String waiter, String ticketId) {
        try (PreparedStatement ps = data.getSession().getConnection().prepareStatement(
                "UPDATE PLACES SET WAITER = ?, TICKETID = ? WHERE ID = ?")) {
            ps.setString(1, waiter);
            ps.setString(2, ticketId);
            ps.setString(3, tableId);
            ps.executeUpdate();
        } catch (SQLException e) {
            // information d'affichage seulement : la commande est deja enregistree
        }
    }

    private static String requiredString(Map<?, ?> m, String key, int index) throws ApiServer.ApiError {
        Object v = m.get(key);
        if (!(v instanceof String) || ((String) v).isEmpty() || ((String) v).length() > 256) {
            throw bad("invalid_operation", "Operation " + index + " : champ '" + key + "' obligatoire.");
        }
        return (String) v;
    }

    private static int quantity(Object v, int index) throws ApiServer.ApiError {
        if (v instanceof Long && (Long) v >= 1 && (Long) v <= MAX_QUANTITY) {
            return ((Long) v).intValue();
        }
        throw bad("invalid_quantity", "Operation " + index + " : 'quantity' doit etre un nombre entier de 1 a "
                + MAX_QUANTITY + " (pour supprimer une ligne, utilisez 'remove').");
    }

    private static String note(Object v, int index) throws ApiServer.ApiError {
        if (v == null) {
            return null;
        }
        if (!(v instanceof String)) {
            throw bad("invalid_note", "Operation " + index + " : 'note' doit etre un texte.");
        }
        StringBuilder sb = new StringBuilder();
        for (char c : ((String) v).toCharArray()) {
            sb.append(Character.isISOControl(c) ? ' ' : c);   // pas de caracteres de controle sur le bon cuisine
        }
        String s = sb.toString().trim();
        if (s.length() > MAX_NOTE) {
            throw bad("invalid_note", "Operation " + index + " : note trop longue (" + MAX_NOTE + " caracteres au plus).");
        }
        return s;
    }

    private static String fingerprint(String tableId, Map<String, Object> body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((tableId + "\n" + Json.write(body)).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String time(long millis) {
        return new SimpleDateFormat("HH:mm").format(new Date(millis));
    }

    private static ApiServer.ApiError bad(String code, String message) {
        return new ApiServer.ApiError(400, code, message);
    }
}
