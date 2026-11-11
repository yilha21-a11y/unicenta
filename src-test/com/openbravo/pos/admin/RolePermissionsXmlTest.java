package com.openbravo.pos.admin;

import com.openbravo.basic.BasicException;
import com.openbravo.data.user.DirtyManager;
import com.openbravo.format.Formats;
import java.awt.Component;
import java.awt.Container;
import javax.swing.JCheckBox;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class RolePermissionsXmlTest {

    private static final String P = "mobile.Access";
    private static final String ROLE = "<?xml version=\"1.0\"?>\r\n<!-- <class name=\"mobile.Access\"/> en commentaire -->\r\n"
            + "<permissions>\r\n    <class name=\"sales.EditLines\"/>\r\n</permissions>\r\n";

    @Test
    public void ignoreLesCommentaires() {
        assertFalse(RolePermissionsXml.has(ROLE, P));
        assertTrue(RolePermissionsXml.has(ROLE, "sales.EditLines"));
        assertFalse(RolePermissionsXml.has(null, P));
    }

    @Test
    public void ajoutPuisRetraitSansToucherAuReste() {
        String added = RolePermissionsXml.add(ROLE, P);
        assertTrue(RolePermissionsXml.has(added, P));
        assertTrue(added.contains("<permissions>\r\n    <class name=\"mobile.Access\"/>\r\n"));
        assertEquals("ajout idempotent", added, RolePermissionsXml.add(added, P));
        assertEquals("retour exact au texte d'origine", ROLE, RolePermissionsXml.remove(added, P));
        assertTrue("le commentaire est conserve", RolePermissionsXml.remove(added, P).contains("en commentaire"));
    }

    @Test
    public void roleVide() {
        String x = RolePermissionsXml.add("", P);
        assertTrue(x.startsWith("<permissions>"));
        assertTrue(RolePermissionsXml.has(x, P));
    }

    @Test
    public void caseACocherDeLEcranDesRoles() throws BasicException {
        RolesView view = new RolesView(new DirtyManager());
        JCheckBox box = find(view);
        assertNotNull("case a cocher presente", box);
        assertFalse("desactivee sans role ouvert", box.isEnabled());

        view.writeValueEdit(new Object[] {"2", "Employee role", Formats.BYTEA.parseValue(ROLE)});
        assertTrue(box.isEnabled());
        assertFalse(box.isSelected());

        box.doClick();   // l'administrateur coche
        Object[] saved = (Object[]) view.createValue();
        assertTrue(RolePermissionsXml.has(Formats.BYTEA.formatValue(saved[2]), P));

        box.doClick();   // puis decoche
        saved = (Object[]) view.createValue();
        assertEquals(ROLE, Formats.BYTEA.formatValue(saved[2]));

        // un role qui a deja la permission : la case est cochee a l'ouverture
        view.writeValueEdit(new Object[] {"0", "Administrator role",
            Formats.BYTEA.parseValue(RolePermissionsXml.add(ROLE, P))});
        assertTrue(box.isSelected());
    }

    private static JCheckBox find(Container c) {
        for (Component k : c.getComponents()) {
            if (k instanceof JCheckBox) {
                return (JCheckBox) k;
            }
            if (k instanceof Container) {
                JCheckBox b = find((Container) k);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }
}
