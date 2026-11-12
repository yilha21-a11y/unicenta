//    OLPOS (derive d'uniCenta oPOS) - ecran Activation / Licence
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.license;

import com.olpos.license.License;
import com.olpos.license.LicenseCodec;
import com.olpos.license.LicenseEvaluation;
import com.olpos.license.LicenseException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Ecran "Activation / Licence" : etat, titulaire, dates, jours restants,
 * saisie d'un code ou import d'un fichier signe. Toujours accessible, meme
 * licence expiree (ecran de connexion de la caisse).
 */
public final class LicenseDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final LicenseService service;
    private final JPanel details = new JPanel(new GridBagLayout());
    private final JLabel status = new JLabel();
    private final JTextArea message = new JTextArea(2, 50);
    private final JTextArea code = new JTextArea(5, 50);

    public static void open(Component parent, LicenseService service) {
        Window w = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        LicenseDialog d = new LicenseDialog(w, service);
        d.setVisible(true);
    }

    private LicenseDialog(Window owner, LicenseService service) {
        super(owner, "OLPOS - Activation / Licence", ModalityType.APPLICATION_MODAL);
        this.service = service;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new BorderLayout(4, 4));
        status.setFont(status.getFont().deriveFont(Font.BOLD, 18f));
        top.add(status, BorderLayout.NORTH);
        message.setEditable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setOpaque(false);
        top.add(message, BorderLayout.CENTER);
        top.add(details, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        JPanel input = new JPanel(new BorderLayout(4, 4));
        input.setBorder(BorderFactory.createTitledBorder("Code d'activation (commence par " + LicenseCodec.PREFIX + ")"));
        code.setLineWrap(true);
        code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        input.add(new JScrollPane(code), BorderLayout.CENTER);
        JPanel inputButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton activate = new JButton("Activer ce code");
        activate.addActionListener(e -> installCode(code.getText()));
        JButton importFile = new JButton("Importer un fichier de licence...");
        importFile.addActionListener(e -> importFile());
        inputButtons.add(activate);
        inputButtons.add(importFile);
        input.add(inputButtons, BorderLayout.SOUTH);
        root.add(input, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        JTextArea limits = new JTextArea("Verification hors ligne : la licence est signee (Ed25519) et ses dates sont "
                + "controlees en UTC sur ce PC. Sans serveur de licences, une licence deja delivree ne peut pas etre "
                + "annulee avant sa date de fin, et le controle des postes ne vaut que pour les caisses qui partagent "
                + "cette base. Licence invalide ou expiree : les nouvelles ventes sont bloquees, mais vos donnees, "
                + "rapports, sauvegardes et exports restent accessibles et rien n'est supprime. OLPOS reste un "
                + "logiciel libre (GPL v3).");
        limits.setEditable(false);
        limits.setLineWrap(true);
        limits.setWrapStyleWord(true);
        limits.setOpaque(false);
        limits.setFont(limits.getFont().deriveFont(11f));
        bottom.add(limits, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton copyMachine = new JButton("Copier le code de ce poste");
        copyMachine.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(service.getMachineCode()), null);
            JOptionPane.showMessageDialog(this, "Code du poste copie : " + service.getMachineCode());
        });
        JButton stations = new JButton("Postes...");
        stations.addActionListener(e -> manageStations());
        JButton close = new JButton("Fermer");
        close.addActionListener(e -> dispose());
        buttons.add(copyMachine);
        buttons.add(stations);
        buttons.add(close);
        bottom.add(buttons, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);

        setContentPane(root);
        refreshView(service.refresh());
        setMinimumSize(new Dimension(720, 600));
        pack();
        setLocationRelativeTo(owner);
    }

    private void refreshView(LicenseEvaluation e) {
        switch (e.getStatus()) {
            case VALID:
                status.setText(e.warningThreshold() > 0 ? "Licence valable - expire bientot" : "Licence valable");
                status.setForeground(e.warningThreshold() > 0 ? new Color(0xB36B00) : new Color(0x1B7F3B));
                break;
            case NONE:
                status.setText("OLPOS n'est pas active");
                status.setForeground(new Color(0xB00020));
                break;
            default:
                status.setText("Licence non valable : nouvelles ventes bloquees");
                status.setForeground(new Color(0xB00020));
                break;
        }
        message.setText(e.getMessage());
        details.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 12);
        int row = 0;
        License l = e.getLicense();
        if (l != null) {
            DateTimeFormatter d = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            row = line(c, row, "Numero de serie", l.getSerial() + "  (revision " + l.getRevision() + ")");
            row = line(c, row, "Titulaire", l.getCustomer());
            row = line(c, row, "Restaurant", l.getRestaurant());
            row = line(c, row, "Valable du", d.format(l.getValidFrom()) + " (00:00 UTC)");
            row = line(c, row, "Expiration", LicenseEvaluation.describeEnd(l, service.getZone()));
            if (e.getStatus() == LicenseEvaluation.Status.VALID) {
                row = line(c, row, "Jours restants", Long.toString(e.getDaysLeft()));
            }
            row = line(c, row, "Fonctions", String.join(", ", l.featureList()));
            row = line(c, row, "Postes", l.getMaxStations() == 0 ? "sans limite"
                    : l.getMaxStations() + " au plus (" + service.stations().size() + " enregistre(s))");
            row = line(c, row, "Poste lie", l.getMachine() == null ? "non (licence non liee a un PC)" : l.getMachine());
        }
        row = line(c, row, "Code de ce poste", service.getMachineCode());
        line(c, row, "Cle de verification", service.getTrustedKeys().isEmpty() ? "AUCUNE (activation impossible)"
                : String.join(", ", service.getTrustedKeys().keySet()));
        details.revalidate();
        details.repaint();
    }

    private int line(GridBagConstraints c, int row, String label, String value) {
        c.gridy = row;
        c.gridx = 0;
        JLabel l = new JLabel(label + " :");
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        details.add(l, c);
        c.gridx = 1;
        details.add(new JLabel(value), c);
        return row + 1;
    }

    private void installCode(String text) {
        if (text == null || text.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Collez d'abord le code d'activation.");
            return;
        }
        try {
            LicenseEvaluation e = service.install(text);
            refreshView(e);
            code.setText("");
            JOptionPane.showMessageDialog(this, e.getStatus() == LicenseEvaluation.Status.VALID
                    ? "Licence installee. " + e.getMessage() : "Licence installee mais non valable : " + e.getMessage(),
                    getTitle(), e.getStatus() == LicenseEvaluation.Status.VALID
                            ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        } catch (LicenseException ex) {
            JOptionPane.showMessageDialog(this, "Licence refusee : " + ex.getMessage(), getTitle(), JOptionPane.ERROR_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Enregistrement impossible : " + ex.getMessage(), getTitle(),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void importFile() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Licence OLPOS (*" + LicenseCodec.FILE_EXTENSION + ")",
                LicenseCodec.FILE_EXTENSION.substring(1)));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            try {
                if (f.length() > 64 * 1024) {
                    throw new IOException("fichier trop volumineux pour une licence");
                }
                installCode(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Lecture impossible : " + ex.getMessage(), getTitle(),
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void manageStations() {
        Map<String, String> st = service.stations();
        if (st.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Aucun poste enregistre (licence sans limite de postes).");
            return;
        }
        String[] options = st.keySet().toArray(new String[0]);
        String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            String v = st.get(options[i]);
            String host = v.contains("|") ? v.substring(v.indexOf('|') + 1) : "?";
            labels[i] = options[i] + "  -  " + host + (options[i].equals(service.getMachineCode()) ? "  (ce poste)" : "");
        }
        Object choice = JOptionPane.showInputDialog(this, "Liberer la place d'un poste remplace ou hors service :",
                "Postes", JOptionPane.QUESTION_MESSAGE, null, labels, labels[0]);
        if (choice == null) {
            return;
        }
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(choice) && JOptionPane.showConfirmDialog(this, "Liberer " + options[i] + " ?",
                    "Postes", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                service.freeStation(options[i]);
                refreshView(service.getEvaluation());
            }
        }
    }
}
