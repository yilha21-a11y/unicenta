//    OLPOS (derive d'uniCenta oPOS) - API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.openbravo.pos.api;

import com.openbravo.basic.BasicException;
import com.openbravo.data.loader.Datas;
import com.openbravo.data.loader.SerializerReadBasic;
import com.openbravo.data.loader.Session;
import com.openbravo.data.loader.StaticSentence;
import com.openbravo.format.Formats;
import com.openbravo.pos.forms.AppUser;
import com.openbravo.pos.forms.DataLogicSales;
import com.openbravo.pos.forms.DataLogicSystem;
import com.openbravo.pos.sales.TaxesLogic;
import com.openbravo.pos.ticket.CategoryInfo;
import com.openbravo.pos.ticket.ProductInfoExt;
import com.openbravo.pos.ticket.TaxInfo;
import com.openbravo.pos.util.RoundUtils;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Acces aux donnees pour l'API mobile, en REUTILISANT les classes de la caisse :
 * <ul>
 *   <li>serveurs et mots de passe : {@link DataLogicSystem#listPeopleVisible()},
 *       {@link AppUser#authenticate(String)} ;</li>
 *   <li>permissions : {@link AppUser#fillPermissions(DataLogicSystem)} /
 *       {@link AppUser#hasPermission(String)} (memes roles qu'a la caisse) ;</li>
 *   <li>catalogue : {@link DataLogicSales#getRootCategories()},
 *       {@link DataLogicSales#getSubcategories(String)},
 *       {@link DataLogicSales#getProductCatalog(String)} (memes regles d'affichage
 *       que l'ecran de vente) ;</li>
 *   <li>prix TTC : {@link TaxesLogic#getTaxInfo(String)} +
 *       {@link ProductInfoExt#getPriceSellTax(TaxInfo)}.</li>
 * </ul>
 * Une seule connexion dediee a l'API ; les acces sont serialises (synchronized).
 * Lecture seule a ce stade.
 */
public class MobileDataService {

    /** Permissions transmises au telephone (les autres ne le concernent pas). */
    static final String[] MOBILE_PERMISSIONS = {
        ApiConfig.PERMISSION_ACCESS,
        "sales.EditLines",
        "sales.PrintKitchen",
        "sales.PrintTicket",
    };

    private final Session session;
    private final DataLogicSystem dlSystem;
    private final DataLogicSales dlSales;

    public MobileDataService(Session session) {
        this.session = session;
        this.dlSystem = new DataLogicSystem();
        this.dlSystem.init(session);
        this.dlSales = new DataLogicSales();
        this.dlSales.init(session);
    }

    public Session getSession() {
        return session;
    }

    DataLogicSales getDataLogicSales() {
        return dlSales;
    }

    DataLogicSystem getDataLogicSystem() {
        return dlSystem;
    }

    // ------------------------------------------------------------------ serveurs

    /** Resultat d'une tentative de connexion. */
    public static final class LoginResult {
        public enum Status { OK, UNKNOWN_USER, BAD_PASSWORD, NO_PASSWORD, NO_ACCESS }

        public final Status status;
        public final AppUser user;
        public final Set<String> permissions;

        LoginResult(Status status, AppUser user, Set<String> permissions) {
            this.status = status;
            this.user = user;
            this.permissions = permissions;
        }
    }

    /** Un serveur peut utiliser le telephone s'il a un mot de passe ET la permission mobile.Access. */
    public synchronized List<Map<String, Object>> listMobileUsers() throws BasicException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object o : dlSystem.listPeopleVisible()) {
            AppUser u = (AppUser) o;
            if (hasPassword(u) && permissionsOf(u).contains(ApiConfig.PERMISSION_ACCESS)) {
                result.add(Json.obj("id", u.getId(), "name", u.getName()));
            }
        }
        return result;
    }

    public synchronized LoginResult login(String userId, String password) throws BasicException {
        AppUser found = null;
        for (Object o : dlSystem.listPeopleVisible()) {
            AppUser u = (AppUser) o;
            if (u.getId().equals(userId)) {
                found = u;
                break;
            }
        }
        if (found == null) {
            return new LoginResult(LoginResult.Status.UNKNOWN_USER, null, null);
        }
        // Un compte sans mot de passe ne doit pas etre utilisable a distance.
        if (!hasPassword(found)) {
            return new LoginResult(LoginResult.Status.NO_PASSWORD, found, null);
        }
        if (password == null || !found.authenticate(password)) {
            return new LoginResult(LoginResult.Status.BAD_PASSWORD, found, null);
        }
        Set<String> perms = permissionsOf(found);
        if (!perms.contains(ApiConfig.PERMISSION_ACCESS)) {
            return new LoginResult(LoginResult.Status.NO_ACCESS, found, perms);
        }
        return new LoginResult(LoginResult.Status.OK, found, perms);
    }

    private static boolean hasPassword(AppUser u) {
        String p = u.getPassword();
        return p != null && !p.isEmpty() && !p.startsWith("empty:");
    }

    /** Permissions du role (meme lecture XML que la caisse), filtrees pour le mobile. */
    private Set<String> permissionsOf(AppUser u) {
        u.fillPermissions(dlSystem);
        Set<String> perms = new LinkedHashSet<>();
        for (String p : MOBILE_PERMISSIONS) {
            if (u.hasPermission(p)) {
                perms.add(p);
            }
        }
        return perms;
    }

    // ------------------------------------------------------------------ catalogue

    /** Arbre des categories a plat (parentId), dans l'ordre d'affichage de la caisse. */
    public synchronized List<Map<String, Object>> listCategories() throws BasicException {
        List<Map<String, Object>> result = new ArrayList<>();
        Deque<Object[]> todo = new ArrayDeque<>();   // {CategoryInfo, parentId}
        for (CategoryInfo c : dlSales.getRootCategories()) {
            todo.addLast(new Object[] {c, null});
        }
        Set<String> seen = new HashSet<>();          // protection contre un cycle dans PARENTID
        while (!todo.isEmpty()) {
            Object[] item = todo.removeFirst();
            CategoryInfo c = (CategoryInfo) item[0];
            if (!seen.add(c.getID())) {
                continue;
            }
            result.add(Json.obj("id", c.getID(), "name", c.getName(), "parentId", item[1]));
            for (CategoryInfo sub : dlSales.getSubcategories(c.getID())) {
                todo.addLast(new Object[] {sub, c.getID()});
            }
        }
        return result;
    }

    /** Produits du catalogue de vente d'une categorie, avec prix HT, TTC et affiche. */
    public synchronized List<Map<String, Object>> listProducts(String categoryId) throws BasicException {
        return toJson(dlSales.getProductCatalog(categoryId), newTaxesLogic());
    }

    /** Catalogue complet : categories + produits de chaque categorie. */
    public synchronized Map<String, Object> catalog() throws BasicException {
        List<Map<String, Object>> categories = listCategories();
        TaxesLogic taxes = newTaxesLogic();
        List<Map<String, Object>> products = new ArrayList<>();
        for (Map<String, Object> c : categories) {
            products.addAll(toJson(dlSales.getProductCatalog((String) c.get("id")), taxes));
        }
        return Json.obj("categories", categories, "products", products);
    }

    @SuppressWarnings("unchecked")
    private TaxesLogic newTaxesLogic() throws BasicException {
        return new TaxesLogic(dlSales.getTaxList().list());
    }

    private List<Map<String, Object>> toJson(List<ProductInfoExt> products, TaxesLogic taxes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProductInfoExt p : products) {
            TaxInfo tax = taxes.getTaxInfo(p.getTaxCategoryID());
            double rate = tax == null ? 0.0 : tax.getRate();
            double taxed = tax == null ? p.getPriceSell() : p.getPriceSellTax(tax);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getID());
            m.put("reference", p.getReference());
            m.put("code", p.getCode());
            m.put("name", p.getName());
            m.put("categoryId", p.getCategoryID());
            m.put("taxCategoryId", p.getTaxCategoryID());
            m.put("priceSell", p.getPriceSell());          // HT, tel que stocke
            m.put("taxRate", rate);
            m.put("priceSellTaxed", RoundUtils.round(taxed));
            m.put("priceDisplay", Formats.CURRENCY.formatValue(taxed));
            m.put("kitchen", p.isKitchen());
            m.put("service", p.isService());
            m.put("variablePrice", p.isVprice());
            m.put("scale", p.isScale());
            m.put("hasAttributes", p.getAttributeSetID() != null);
            m.put("textTip", p.getTextTip());
            result.add(m);
        }
        return result;
    }

    // ------------------------------------------------------------------ salles et tables

    /**
     * Salles et tables (memes tables FLOORS / PLACES que le plan de salle de la
     * caisse). Une table est occupee quand une commande en cours existe dans
     * SHAREDTICKETS avec l'identifiant de la table (cf. JTicketsBagRestaurantMap).
     */
    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> listFloors() throws BasicException {
        List<Object[]> floors = new StaticSentence(session,
                "SELECT ID, NAME FROM FLOORS ORDER BY NAME", null,
                new SerializerReadBasic(new Datas[] {Datas.STRING, Datas.STRING})).list();
        List<Object[]> places = new StaticSentence(session,
                "SELECT ID, NAME, X, Y, FLOOR, CUSTOMER, WAITER FROM PLACES ORDER BY FLOOR", null,
                new SerializerReadBasic(new Datas[] {Datas.STRING, Datas.STRING, Datas.INT, Datas.INT,
                    Datas.STRING, Datas.STRING, Datas.STRING})).list();
        List<Object[]> open = new StaticSentence(session,
                "SELECT ID, CONTENT FROM SHAREDTICKETS", null,
                new SerializerReadBasic(new Datas[] {Datas.STRING, Datas.SERIALIZABLE})).list();
        Set<String> occupied = new HashSet<>();
        Map<String, Integer> numbers = new java.util.HashMap<>();
        for (Object[] r : open) {
            occupied.add((String) r[0]);
            if (r[1] instanceof com.openbravo.pos.ticket.TicketInfo) {
                numbers.put((String) r[0], com.openbravo.pos.sales.OrderNumbers.numberOf((com.openbravo.pos.ticket.TicketInfo) r[1]));
            }
        }

        Map<String, List<Map<String, Object>>> tablesByFloor = new LinkedHashMap<>();
        for (Object[] r : places) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", r[0]);
            t.put("name", r[1]);
            t.put("x", r[2]);
            t.put("y", r[3]);
            t.put("occupied", occupied.contains((String) r[0]));
            t.put("orderNumber", numbers.get((String) r[0]));
            t.put("customer", r[5]);
            t.put("waiter", r[6]);
            List<Map<String, Object>> list = tablesByFloor.get((String) r[4]);
            if (list == null) {
                list = new ArrayList<>();
                tablesByFloor.put((String) r[4], list);
            }
            list.add(t);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] f : floors) {
            List<Map<String, Object>> tables = tablesByFloor.get((String) f[0]);
            if (tables == null) {
                tables = new ArrayList<>();
            }
            Collections.sort(tables, NATURAL_NAME);
            result.add(Json.obj("id", f[0], "name", f[1], "tables", tables));
        }
        return result;
    }

    /** Tri "naturel" : Table 2 avant Table 10. */
    static final Comparator<Map<String, Object>> NATURAL_NAME = new Comparator<Map<String, Object>>() {
        @Override
        public int compare(Map<String, Object> a, Map<String, Object> b) {
            return naturalCompare(String.valueOf(a.get("name")), String.valueOf(b.get("name")));
        }
    };

    static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) {
                    return na.length() - nb.length();
                }
                int c = na.compareTo(nb);
                if (c != 0) {
                    return c;
                }
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) {
                    return c;
                }
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
