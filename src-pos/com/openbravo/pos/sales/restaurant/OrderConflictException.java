//    OLPOS (derive d'uniCenta oPOS) - synchronisation caisse / API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.openbravo.pos.sales.restaurant;

/**
 * La commande d'une table a ete modifiee (ou encaissee / supprimee) par
 * quelqu'un d'autre depuis qu'on l'a lue. Rien n'a ete enregistre.
 */
public class OrderConflictException extends Exception {

    private static final long serialVersionUID = 1L;

    private final TableTicketStore.Snapshot current;
    private final long expectedVersion;

    public OrderConflictException(String message, long expectedVersion, TableTicketStore.Snapshot current) {
        super(message);
        this.expectedVersion = expectedVersion;
        this.current = current;
    }

    /** Version que l'appelant croyait modifier. */
    public long getExpectedVersion() {
        return expectedVersion;
    }

    /** Etat actuel enregistre (null si la commande n'existe plus). */
    public TableTicketStore.Snapshot getCurrent() {
        return current;
    }
}
