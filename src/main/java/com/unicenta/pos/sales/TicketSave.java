//    OLPOS (derive d'uniCenta oPOS) - enregistrement d'une vente
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.sales;

import com.unicenta.basic.BasicException;
import java.util.function.Consumer;

/**
 * Enregistrement d'une vente encaissee. Regle OLPOS : si l'enregistrement en
 * base echoue, l'appelant NE DOIT NI imprimer le ticket, NI cloturer ou
 * supprimer la commande, NI liberer la table : la vente n'existe pas.
 * (Avant OLPOS, uniCenta affichait l'erreur puis continuait comme si la vente
 * etait enregistree.)
 */
public final class TicketSave {

    /** L'enregistrement proprement dit (DataLogicSales.saveTicket). */
    @FunctionalInterface
    public interface Saver {
        void save() throws BasicException;
    }

    private TicketSave() {
    }

    /**
     * @return true seulement si la vente est enregistree ; sinon l'erreur est
     *         signalee a {@code onError} et l'appelant doit s'arreter.
     */
    public static boolean save(Saver saver, Consumer<BasicException> onError) {
        try {
            saver.save();
            return true;
        } catch (BasicException e) {
            onError.accept(e);
            return false;
        } catch (RuntimeException e) {
            onError.accept(new BasicException(e));
            return false;
        }
    }
}
