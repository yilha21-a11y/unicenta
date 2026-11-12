//    OLPOS - licences (code commun a OLPOS et a OLPOS License Manager)
//    Licence GPL v3 (voir LICENSE.md).
package com.olpos.license;

/** Licence refusee (format, signature). */
public final class LicenseException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** Texte illisible, tronque ou modifie dans sa forme. */
        MALFORMED,
        /** Cle de signature inconnue de ce programme. */
        UNKNOWN_KEY,
        /** Signature invalide : licence alteree ou fabriquee. */
        BAD_SIGNATURE
    }

    private final Reason reason;

    public LicenseException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
