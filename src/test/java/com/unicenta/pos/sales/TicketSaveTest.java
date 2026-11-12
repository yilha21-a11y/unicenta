package com.unicenta.pos.sales;

import com.unicenta.basic.BasicException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression : une vente non enregistree ne doit jamais etre consideree comme encaissee. */
public class TicketSaveTest {

    @Test
    public void echecDEnregistrementArreteLEncaissement() {
        List<BasicException> errors = new ArrayList<>();
        assertFalse(TicketSave.save(() -> {
            throw new BasicException("base indisponible");
        }, errors::add));
        assertEquals(1, errors.size());
        assertFalse(TicketSave.save(() -> {
            throw new IllegalStateException("connexion perdue");
        }, errors::add));
        assertEquals(2, errors.size());
    }

    @Test
    public void enregistrementReussi() {
        List<BasicException> errors = new ArrayList<>();
        assertTrue(TicketSave.save(() -> { }, errors::add));
        assertTrue(errors.isEmpty());
    }

    /**
     * Le code d'encaissement s'arrete (return false) avant toute impression ou
     * cloture si l'enregistrement echoue ; et le paiement d'un compte client aussi.
     */
    @Test
    public void lEncaissementUtiliseLaRegle() throws Exception {
        String panel = new String(java.nio.file.Files.readAllBytes(
                new java.io.File("src/main/java/com/unicenta/pos/sales/JPanelTicket.java").toPath()), "UTF-8");
        int save = panel.indexOf("boolean saved = TicketSave.save(");
        int stop = panel.indexOf("if (!saved) {", save);
        int ret = panel.indexOf("return false;", stop);
        int print = panel.indexOf("printTicket(paymentdialog.isPrintSelected()", save);
        int close = panel.indexOf("\"ticket.close\"", save);
        assertTrue(save > 0 && stop > save && ret > stop);
        assertTrue("arret avant impression", ret < print);
        assertTrue("arret avant cloture", ret < close);
        String customers = new String(java.nio.file.Files.readAllBytes(
                new java.io.File("src/main/java/com/unicenta/pos/customers/CustomersPayment.java").toPath()), "UTF-8");
        assertEquals(2, customers.split("TicketSave.save\\(", -1).length - 1);
        assertFalse(customers.contains("dlsales.saveTicket(ticket, app.getInventoryLocation());\n            } catch"));
    }
}
