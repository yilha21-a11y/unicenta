//    OLPOS (derive d'uniCenta oPOS) - API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.openbravo.pos.api;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * File d'impression unique des bons demandes depuis les telephones (cuisine,
 * addition) : un bon a la fois, avec un delai maximal.
 *
 * <p>Issue d'une impression :</p>
 * <ul>
 *   <li>{@code confirme} : l'imprimante confirme (affichage ecran) ;</li>
 *   <li>{@code envoye} : transmis sans erreur, sans confirmation materielle ;</li>
 *   <li>{@code a_verifier} : erreur ou delai depasse PENDANT l'impression ;</li>
 *   <li>{@code non_imprime} : l'impression n'a jamais commence (imprimante
 *       occupee par un bon bloque) ; elle est annulee, rien ne sortira.</li>
 * </ul>
 */
final class PrintRunner {

    /** Resultat d'une impression. */
    static final class Outcome {
        final String status;
        final String message;

        Outcome(String status, String message) {
            this.status = status;
            this.message = message;
        }

        boolean printedForSure() {
            return "confirme".equals(status) || "envoye".equals(status);
        }
    }

    private static final Logger LOG = Logger.getLogger(PrintRunner.class.getName());
    private final long timeoutMillis;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "api-mobile-impression");
            t.setDaemon(true);
            return t;
        }
    });

    PrintRunner(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    void shutdown() {
        thread.shutdownNow();
    }

    Outcome run(final MobilePrinter.Prepared prepared, String what) {
        final AtomicBoolean started = new AtomicBoolean();
        Future<Void> f = thread.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                started.set(true);
                prepared.print();
                return null;
            }
        });
        try {
            f.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return prepared.confirmsPrinting()
                    ? new Outcome("confirme", "Impression confirmee.")
                    : new Outcome("envoye", "Bon transmis a l'imprimante (l'imprimante ne confirme pas la sortie du papier).");
        } catch (TimeoutException e) {
            // Annuler AVANT de regarder si l'impression a commence : une tache
            // annulee qui n'avait pas commence ne demarrera jamais.
            f.cancel(false);
            if (!started.get()) {
                return new Outcome("non_imprime", "L'imprimante est occupee par un bon precedent qui ne repond pas : "
                        + what + " n'a PAS ete imprime(e). Verifiez l'imprimante puis recommencez.");
            }
            return new Outcome("a_verifier", "L'imprimante n'a pas repondu a temps : verifiez si " + what
                    + " est sorti(e). Rien ne sera reimprime automatiquement.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome("a_verifier", "Impression interrompue : verifiez si " + what + " est sorti(e).");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOG.log(Level.WARNING, "Impression incertaine : " + what, cause);
            return new Outcome("a_verifier", "Erreur pendant l'impression (" + cause.getMessage() + ") : verifiez si "
                    + what + " est sorti(e). Rien ne sera reimprime automatiquement.");
        }
    }
}
