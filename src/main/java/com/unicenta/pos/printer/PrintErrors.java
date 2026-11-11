//    OLPOS (derive d'uniCenta oPOS) - remontee des erreurs d'impression
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.printer;

import java.util.ArrayList;
import java.util.List;

/**
 * Erreurs d'impression rencontrees pendant un bon suivi par un service OLPOS
 * (bon cuisine, addition). Sans suivi actif, les peripheriques gardent le
 * comportement d'uniCenta (message a l'ecran).
 *
 * <p>Utilisation : {@code PrintErrors.begin()} puis impression dans le MEME
 * fil, puis {@code PrintErrors.end()} qui renvoie les erreurs rencontrees.</p>
 */
public final class PrintErrors {

    /** Erreur du pilote d'impression. {@code nothingPrinted} : le travail n'a pas ete accepte. */
    public static final class Failure {
        public final Exception error;
        public final boolean nothingPrinted;

        Failure(Exception error, boolean nothingPrinted) {
            this.error = error;
            this.nothingPrinted = nothingPrinted;
        }
    }

    private static final ThreadLocal<List<Failure>> ACTIVE = new ThreadLocal<>();

    private PrintErrors() {
    }

    public static void begin() {
        ACTIVE.set(new ArrayList<Failure>());
    }

    public static List<Failure> end() {
        List<Failure> l = ACTIVE.get();
        ACTIVE.remove();
        return l == null ? new ArrayList<Failure>() : l;
    }

    /**
     * Signale une erreur.
     * @return true si un service suit ce bon (l'appelant ne doit pas afficher de fenetre)
     */
    public static boolean report(Exception e, boolean nothingPrinted) {
        List<Failure> l = ACTIVE.get();
        if (l == null) {
            return false;
        }
        l.add(new Failure(e, nothingPrinted));
        return true;
    }
}
