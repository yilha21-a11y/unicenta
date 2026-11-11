//    OLPOS (derive d'uniCenta oPOS) - API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.api;

import com.unicenta.pos.ticket.TicketInfo;
import java.util.Map;

/**
 * Impression d'un bon (cuisine, addition) demandee depuis le telephone, en
 * deux temps :
 * <ol>
 *   <li>{@link #prepare} : mise en page et verification des imprimantes.
 *       RIEN n'est imprime ; une erreur ici est sans ambiguite.</li>
 *   <li>{@link Prepared#print()} : envoi a l'imprimante. Une erreur ou un
 *       delai depasse ici rend le resultat INCERTAIN (le bon a pu sortir).</li>
 * </ol>
 */
public interface MobilePrinter {

    /** Erreur avant toute impression (imprimante non configuree ou injoignable, modele absent ou invalide). */
    class NotPrintedException extends Exception {
        private static final long serialVersionUID = 1L;

        public NotPrintedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Bon pret a imprimer. */
    interface Prepared {
        /** true si l'imprimante permet de confirmer l'impression (ex. affichage a l'ecran). */
        boolean confirmsPrinting();

        /** Description des imprimantes utilisees (ex. "2 (ecran)"). */
        String printers();

        /** Envoie le bon a l'imprimante. Toute exception = resultat incertain. */
        void print() throws Exception;
    }

    /**
     * @param ticket    copie de la commande a imprimer (textes deja proteges pour le XML)
     * @param tableName nom de la table (variable {@code $place} du modele)
     * @param template  modele Velocity du bon (null s'il n'existe pas)
     * @param variables variables supplementaires du modele (ex. {@code $taxes}), peut etre null
     */
    Prepared prepare(TicketInfo ticket, String tableName, String template, Map<String, Object> variables)
            throws NotPrintedException;
}
