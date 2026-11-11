//    uniCenta oPOS  - Touch Friendly Point Of Sale
//    Copyright (c) 2009-2014 uniCenta
//    http://www.unicenta.com
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

package com.openbravo.pos.admin;

import com.openbravo.basic.BasicException;
import com.openbravo.data.user.DirtyManager;
import com.openbravo.data.user.EditorRecord;
import com.openbravo.format.Formats;
import com.openbravo.pos.forms.AppLocal;
import java.awt.Component;
import java.util.UUID;

/**
 *
 * @author adrianromero
 */
public final class RolesView extends javax.swing.JPanel implements EditorRecord {
    
    private Object m_oId;

    /** Permission de connexion depuis un telephone (API mobile). */
    static final String MOBILE_ACCESS = "mobile.Access";
    // Case a cocher au-dessus du texte XML : evite d'ecrire la ligne a la main.
    private final javax.swing.JCheckBox m_jMobile = new javax.swing.JCheckBox();
    private boolean syncing;
    
    /** Creates new form RolesEditor
     * @param dirty */
    public RolesView(DirtyManager dirty) {
        initComponents();

        // La zone de texte est placee sous la case a cocher (code genere inchange).
        m_jMobile.setText(AppLocal.getIntString("label.mobileaccess"));
        m_jMobile.setFont(new java.awt.Font("Arial", 0, 12));
        javax.swing.JPanel withMobile = new javax.swing.JPanel(new java.awt.BorderLayout(0, 6));
        withMobile.setOpaque(false);
        ((javax.swing.GroupLayout) getLayout()).replace(jScrollPane1, withMobile);
        withMobile.add(m_jMobile, java.awt.BorderLayout.NORTH);
        withMobile.add(jScrollPane1, java.awt.BorderLayout.CENTER);
        m_jMobile.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (syncing) {
                    return;
                }
                String xml = m_jText.getText();
                syncing = true;
                try {
                    m_jText.setText(m_jMobile.isSelected()
                            ? RolePermissionsXml.add(xml, MOBILE_ACCESS)
                            : RolePermissionsXml.remove(xml, MOBILE_ACCESS));
                    m_jText.setCaretPosition(0);
                } finally {
                    syncing = false;
                }
            }
        });
        // le texte modifie a la main met la case a jour
        m_jText.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                syncMobile();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                syncMobile();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                syncMobile();
            }
        });
        
        m_jName.getDocument().addDocumentListener(dirty);
        m_jText.getDocument().addDocumentListener(dirty);
        
        writeValueEOF();
    }

    private void syncMobile() {
        if (syncing) {
            return;
        }
        syncing = true;
        try {
            m_jMobile.setSelected(RolePermissionsXml.has(m_jText.getText(), MOBILE_ACCESS));
            m_jMobile.setEnabled(m_jText.isEnabled());
        } finally {
            syncing = false;
        }
    }
    
    /**
     *
     */
    @Override
    public void writeValueEOF() {
        m_oId = null;
        m_jName.setText(null);
        m_jText.setText(null);
        m_jName.setEnabled(false);
        m_jText.setEnabled(false);
        syncMobile();
    }
    
    /**
     *
     */
    @Override
    public void writeValueInsert() {
        m_oId = null;
        m_jName.setText(null);
        m_jText.setText(null);
        m_jName.setEnabled(true);
        m_jText.setEnabled(true);
        syncMobile();
    }
    
    /**
     *
     * @param value
     */
    @Override
    public void writeValueDelete(Object value) {

        Object[] role = (Object[]) value;
        m_oId = role[0];
        m_jName.setText(Formats.STRING.formatValue(role[1]));
        m_jText.setText(Formats.BYTEA.formatValue(role[2]));
        m_jText.setCaretPosition(0);
        m_jName.setEnabled(false);
        m_jText.setEnabled(false);
        syncMobile();
    }

    /**
     *
     * @param value
     */
    @Override
    public void writeValueEdit(Object value) {

        Object[] role = (Object[]) value;
        m_oId = role[0];
        m_jName.setText(Formats.STRING.formatValue(role[1]));
        m_jText.setText(Formats.BYTEA.formatValue(role[2]));
        m_jText.setCaretPosition(0);
        m_jName.setEnabled(true);
        m_jText.setEnabled(true);
        syncMobile();
    }

    /**
     *
     * @return
     * @throws BasicException
     */
    @Override
    public Object createValue() throws BasicException {

        Object[] role = new Object[3];
        role[0] = m_oId == null ? UUID.randomUUID().toString() : m_oId;
        role[1] = m_jName.getText();
        role[2] = Formats.BYTEA.parseValue(m_jText.getText());
        return role;
    }
    
    /**
     *
     * @return
     */
    @Override
    public Component getComponent() {
        return this;
    }
    
    /**
     *
     */
    @Override
    public void refresh() {
    }
    
    /** This method is called from within the constructor to
     * initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is
     * always regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jScrollPane1 = new javax.swing.JScrollPane();
        m_jText = new javax.swing.JTextArea();
        jLabel2 = new javax.swing.JLabel();
        m_jName = new javax.swing.JTextField();

        m_jText.setFont(new java.awt.Font("Arial", 0, 12)); // NOI18N
        jScrollPane1.setViewportView(m_jText);

        jLabel2.setFont(new java.awt.Font("Arial", 0, 12)); // NOI18N
        jLabel2.setText(AppLocal.getIntString("Label.Name")); // NOI18N

        m_jName.setFont(new java.awt.Font("Arial", 0, 12)); // NOI18N

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 477, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(jLabel2, javax.swing.GroupLayout.PREFERRED_SIZE, 80, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(m_jName, javax.swing.GroupLayout.PREFERRED_SIZE, 260, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel2, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(m_jName, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(18, 18, 18)
                .addComponent(jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 312, Short.MAX_VALUE)
                .addContainerGap())
        );
    }// </editor-fold>//GEN-END:initComponents
    
    
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel jLabel2;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JTextField m_jName;
    private javax.swing.JTextArea m_jText;
    // End of variables declaration//GEN-END:variables
    
}
