//    uniCenta oPOS  - Touch Friendly Point Of Sale
//    Copyright (c) 2009-2018 uniCenta & previous Openbravo POS works
//    https://unicenta.com
//
//    This file is part of uniCenta oPOS
//
//    uniCenta oPOS is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//   uniCenta oPOS is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with uniCenta oPOS.  If not, see <http://www.gnu.org/licenses/>.

//
//    OLPOS : suivi du resultat de chaque bon (voir beginJob / endJob). Les
//    donnees partent toujours dans un fil d'arriere-plan ; une erreur n'est plus
//    seulement ecrite dans le journal : elle est rendue a l'appelant qui attend
//    le resultat (bon cuisine, addition), sans jamais reimprimer.

package com.unicenta.pos.printer.escpos;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 * @author JG uniCenta
 */
public abstract class PrinterWritter {

    /**
     * OLPOS : echec d'un bon. {@link #isNothingSent()} = aucune donnee n'a
     * atteint l'imprimante (echec certain, rien n'est sorti) ; sinon le
     * resultat est incertain (le bon a pu sortir en partie ou en entier).
     */
    public static class JobException extends Exception {
        private static final long serialVersionUID = 1L;
        private final boolean nothingSent;

        public JobException(String message, Throwable cause, boolean nothingSent) {
            super(message, cause);
            this.nothingSent = nothingSent;
        }

        public boolean isNothingSent() {
            return nothingSent;
        }
    }

    private boolean initialized = false;

    private ExecutorService exec;

    // etat du bon en cours (modifie uniquement dans le fil d'envoi)
    private Exception jobError;
    private boolean jobSent;

    /**
     *
     */
    public PrinterWritter() {
        exec = Executors.newSingleThreadExecutor();
    }

    /**
     *
     * @param data
     */
    protected abstract void internalWrite(byte[] data);

    /**
     *
     */
    protected abstract void internalFlush();

    /**
     *
     */
    protected abstract void internalClose();

    /** OLPOS : a appeler par l'implementation quand des donnees ont ete transmises. */
    protected void reportSent() {
        jobSent = true;
    }

    /** OLPOS : a appeler par l'implementation en cas d'erreur d'envoi. */
    protected void reportError(Exception e) {
        if (jobError == null) {
            jobError = e;
        }
    }

    /** OLPOS : debut d'un bon suivi (efface le resultat du bon precedent). */
    public void beginJob() {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                jobError = null;
                jobSent = false;
            }
        });
    }

    /**
     * OLPOS : attend que toutes les donnees du bon soient passees et renvoie
     * son resultat.
     * @throws JobException     erreur d'envoi (voir {@link JobException#isNothingSent()})
     * @throws TimeoutException l'imprimante n'a pas fini dans le delai (resultat incertain)
     */
    public void endJob(long timeoutMillis) throws JobException, TimeoutException, InterruptedException {
        Future<Object[]> f = exec.submit(new Callable<Object[]>() {
            @Override
            public Object[] call() {
                Object[] r = {jobError, jobSent};
                jobError = null;
                jobSent = false;
                return r;
            }
        });
        Object[] r;
        try {
            r = f.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new JobException("Erreur interne d'impression", e.getCause(), false);
        }
        Exception error = (Exception) r[0];
        if (error != null) {
            boolean nothing = !((Boolean) r[1]);
            throw new JobException((nothing ? "Rien n'a ete transmis a l'imprimante : "
                    : "Erreur pendant l'envoi a l'imprimante : ") + error.getMessage(), error, nothing);
        }
    }

    /**
     *
     * @param data
     */
    public void init(final byte[] data) {
        if (!initialized) {
            write(data);
            initialized = true;
        }
    }

    /**
     *
     * @param sValue
     */
    public void write(String sValue) {
        write(sValue.getBytes());
    }

    /**
     *
     * @param data
     */
    public void write(final byte[] data) {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    internalWrite(data);
                } catch (RuntimeException e) {
                    reportError(e);   // OLPOS : rendu a l'appelant (endJob)
                }
            }
        });
    }

    /**
     *
     */
    public void flush() {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    internalFlush();
                } catch (RuntimeException e) {
                    reportError(e);
                }
            }
        });
    }

    /**
     *
     */
    public void close() {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                internalClose();
            }
        });
        exec.shutdown();
    }
}
