//    OLPOS License Manager - interface graphique
//    Licence GPL v3 (voir ../LICENSE.md).
package com.olpos.licensemanager;

import com.olpos.license.License;
import com.olpos.license.LicenseCodec;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

/**
 * OLPOS License Manager : programme ADMINISTRATEUR (jamais installe chez le
 * restaurant) pour creer, renouveler et suivre les licences OLPOS.
 */
public final class ManagerApp {

    static final String TITLE = "OLPOS License Manager";
    static final DateTimeFormatter FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final File dir;
    private ManagerStore store;
    private JFrame frame;
    private final DefaultTableModel clientModel = readOnlyModel("Client", "Restaurant", "Contact", "Notes");
    private final DefaultTableModel licenseModel = readOnlyModel("Numero de serie", "Rev.", "Client", "Restaurant",
            "Debut", "Fin (incluse)", "Etat", "Jours", "Fonctions", "Postes", "Poste lie");
    private final List<Map<String, Object>> shownClients = new ArrayList<>();
    private final List<Map<String, Object>> shownLicenses = new ArrayList<>();
    private final JTable clientTable = new JTable(clientModel);
    private final JTable licenseTable = new JTable(licenseModel);
    private final JTextField clientSearch = new JTextField(24);
    private final JTextField licenseSearch = new JTextField(24);
    private final JComboBox<String> licenseFilter = new JComboBox<>(new String[] {"Toutes", "Actives", "Futures", "Expirees"});

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // apparence par defaut
            }
            new ManagerApp(ManagerStore.defaultDir()).start();
        });
    }

    ManagerApp(File dir) {
        this.dir = dir;
    }

    // ================================================================== demarrage

    private void start() {
        store = ManagerStore.exists(dir) ? unlock() : setup();
        if (store == null) {
            System.exit(0);
            return;
        }
        buildFrame();
    }

    private ManagerStore unlock() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            JPasswordField pw = new JPasswordField(20);
            int r = JOptionPane.showConfirmDialog(null, form("Mot de passe du gestionnaire", pw), TITLE,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return null;
            }
            try {
                return ManagerStore.open(dir, pw.getPassword());
            } catch (Vault.WrongPasswordException e) {
                error("Mot de passe incorrect.");
                try {
                    Thread.sleep(1000L * attempt);   // ralentit les essais
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } catch (Exception e) {
                error("Ouverture impossible : " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    private ManagerStore setup() {
        Object[] options = {"Creer une nouvelle cle de signature", "Restaurer une cle sauvegardee", "Annuler"};
        int choice = JOptionPane.showOptionDialog(null, "Premiere configuration d'OLPOS License Manager.\n\n"
                + "Une cle privee de signature va etre creee sur ce PC. Elle seule permet de produire des licences\n"
                + "acceptees par OLPOS : elle est chiffree par votre mot de passe et ne quitte jamais ce programme.\n"
                + "Dossier : " + dir, TITLE, JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                options, options[0]);
        if (choice != 0 && choice != 1) {
            return null;
        }
        File keyBackup = null;
        char[] keyPass = null;
        if (choice == 1) {
            keyBackup = chooseOpen("Sauvegarde de cle OLPOS (*.olposkey)", "olposkey");
            if (keyBackup == null) {
                return null;
            }
            JPasswordField kp = new JPasswordField(20);
            if (JOptionPane.showConfirmDialog(null, form("Phrase de passe de la sauvegarde de cle", kp), TITLE,
                    JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
                return null;
            }
            keyPass = kp.getPassword();
        }
        while (true) {
            JPasswordField p1 = new JPasswordField(20);
            JPasswordField p2 = new JPasswordField(20);
            JPanel f = form("Nouveau mot de passe du gestionnaire (10 caracteres minimum)", p1, "Confirmation", p2);
            if (JOptionPane.showConfirmDialog(null, f, TITLE, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
                return null;
            }
            if (!Arrays.equals(p1.getPassword(), p2.getPassword())) {
                error("Les deux mots de passe sont differents.");
                continue;
            }
            String problem = Vault.checkPassword(p1.getPassword());
            if (problem != null) {
                error(problem);
                continue;
            }
            try {
                ManagerStore s = keyBackup == null ? ManagerStore.create(dir, p1.getPassword())
                        : ManagerStore.createFromKeyBackup(dir, p1.getPassword(), keyBackup, keyPass);
                info("Gestionnaire configure.\n\nIdentifiant de la cle : " + s.getKeyId() + "\nEmpreinte : "
                        + LicenseCodec.fingerprint(s.getPublicKey()) + "\n\nEtape suivante IMPORTANTE : sauvegardez la cle "
                        + "privee (onglet Securite et sauvegardes).\nSans elle, aucun renouvellement ne pourra etre signe.");
                return s;
            } catch (Vault.WrongPasswordException e) {
                error("Phrase de passe de la sauvegarde de cle incorrecte.");
                return null;
            } catch (Exception e) {
                error("Configuration impossible : " + e.getMessage());
                return null;
            }
        }
    }

    // ================================================================== fenetre

    private void buildFrame() {
        frame = new JFrame(TITLE + " - outil administrateur (ne pas installer chez le restaurant)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setIconImages(icons());
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Clients et restaurants", clientsTab());
        tabs.addTab("Licences", licensesTab());
        tabs.addTab("Securite et sauvegardes", securityTab());
        frame.setContentPane(tabs);
        frame.setSize(new Dimension(1150, 650));
        frame.setLocationRelativeTo(null);
        refreshClients();
        refreshLicenses();
        frame.setVisible(true);
    }

    private static List<java.awt.Image> icons() {
        List<java.awt.Image> l = new ArrayList<>();
        java.net.URL u = ManagerApp.class.getResource("/com/olpos/licensemanager/icon.png");
        if (u != null) {
            l.add(Toolkit.getDefaultToolkit().getImage(u));
        }
        return l;
    }

    // ------------------------------------------------------------------ clients

    private JComponent clientsTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Rechercher :"));
        top.add(clientSearch);
        onChange(clientSearch, this::refreshClients);
        p.add(top, BorderLayout.NORTH);
        clientTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        p.add(new JScrollPane(clientTable), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(button("Ajouter", () -> editClient(null)));
        buttons.add(button("Modifier", () -> {
            Map<String, Object> c = selected(clientTable, shownClients);
            if (c != null) {
                editClient(c);
            }
        }));
        buttons.add(button("Supprimer", () -> {
            Map<String, Object> c = selected(clientTable, shownClients);
            if (c != null && confirm("Supprimer " + c.get("customer") + " / " + c.get("restaurant") + " ?")) {
                try {
                    store.deleteClient((String) c.get("id"));
                    refreshClients();
                } catch (Exception e) {
                    error(e.getMessage());
                }
            }
        }));
        buttons.add(button("Nouvelle licence pour ce client", () -> {
            Map<String, Object> c = selected(clientTable, shownClients);
            if (c != null) {
                licenseDialog(null, (String) c.get("id"));
            }
        }));
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private void refreshClients() {
        shownClients.clear();
        shownClients.addAll(store.searchClients(clientSearch.getText()));
        clientModel.setRowCount(0);
        for (Map<String, Object> c : shownClients) {
            clientModel.addRow(new Object[] {c.get("customer"), c.get("restaurant"), c.get("contact"), c.get("notes")});
        }
    }

    private void editClient(Map<String, Object> c) {
        JTextField customer = new JTextField(c == null ? "" : (String) c.get("customer"), 30);
        JTextField restaurant = new JTextField(c == null ? "" : (String) c.get("restaurant"), 30);
        JTextField contact = new JTextField(c == null ? "" : (String) c.get("contact"), 30);
        JTextField notes = new JTextField(c == null ? "" : (String) c.get("notes"), 30);
        if (JOptionPane.showConfirmDialog(frame, form("Client (titulaire de la licence)", customer, "Restaurant", restaurant,
                "Contact (telephone, e-mail)", contact, "Notes", notes), c == null ? "Nouveau client" : "Modifier le client",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            try {
                store.saveClient(c == null ? null : (String) c.get("id"), customer.getText(), restaurant.getText(),
                        contact.getText(), notes.getText());
                refreshClients();
            } catch (Exception e) {
                error(e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ licences

    private JComponent licensesTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Afficher :"));
        top.add(licenseFilter);
        licenseFilter.addActionListener(e -> refreshLicenses());
        top.add(new JLabel("Rechercher :"));
        top.add(licenseSearch);
        onChange(licenseSearch, this::refreshLicenses);
        p.add(top, BorderLayout.NORTH);
        licenseTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        p.add(new JScrollPane(licenseTable), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(button("Nouvelle licence", () -> {
            if (store.clients().isEmpty()) {
                error("Ajoutez d'abord un client (onglet Clients et restaurants).");
            } else {
                licenseDialog(null, null);
            }
        }));
        buttons.add(button("Renouveler / transferer", () -> {
            Map<String, Object> l = selected(licenseTable, shownLicenses);
            if (l != null) {
                licenseDialog(l, (String) l.get("clientId"));
            }
        }));
        buttons.add(button("Code et fichier", () -> {
            Map<String, Object> l = selected(licenseTable, shownLicenses);
            if (l != null) {
                showCode(l);
            }
        }));
        buttons.add(button("Historique", () -> {
            Map<String, Object> l = selected(licenseTable, shownLicenses);
            if (l != null) {
                showHistory((String) l.get("serial"));
            }
        }));
        buttons.add(button("Exporter en CSV...", this::exportCsv));
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private void refreshLicenses() {
        Instant now = Instant.now();
        String q = licenseSearch.getText().trim().toLowerCase(Locale.ROOT);
        int f = licenseFilter.getSelectedIndex();
        shownLicenses.clear();
        licenseModel.setRowCount(0);
        for (Map<String, Object> l : store.latestLicenses()) {
            ManagerStore.State st = ManagerStore.stateOf(l, now);
            if ((f == 1 && st != ManagerStore.State.ACTIVE) || (f == 2 && st != ManagerStore.State.FUTURE)
                    || (f == 3 && st != ManagerStore.State.EXPIRED)) {
                continue;
            }
            String hay = (l.get("serial") + " " + l.get("customer") + " " + l.get("restaurant") + " " + l.get("notes"))
                    .toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !hay.contains(q)) {
                continue;
            }
            shownLicenses.add(l);
            long stations = ManagerStore.num(l.get("maxStations"));
            licenseModel.addRow(new Object[] {l.get("serial"), l.get("revision"), l.get("customer"), l.get("restaurant"),
                FR.format(LocalDate.parse((String) l.get("validFrom"))), FR.format(LocalDate.parse((String) l.get("validUntil"))),
                st == ManagerStore.State.ACTIVE ? "active" : st == ManagerStore.State.FUTURE ? "future" : "expiree",
                st == ManagerStore.State.EXPIRED ? 0 : ManagerStore.daysLeft(l, now),
                String.join(", ", strings(l.get("features"))), stations == 0 ? "illimite" : stations,
                l.get("machine") == null ? "" : l.get("machine")});
        }
    }

    /** Nouvelle licence ({@code base} null) ou renouvellement / transfert de {@code base}. */
    private void licenseDialog(Map<String, Object> base, String clientId) {
        JComboBox<ClientItem> client = new JComboBox<>();
        for (Map<String, Object> c : store.searchClients("")) {
            ClientItem it = new ClientItem(c);
            client.addItem(it);
            if (c.get("id").equals(clientId)) {
                client.setSelectedItem(it);
            }
        }
        client.setEnabled(base == null);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today;
        if (base != null) {
            LocalDate next = LocalDate.parse((String) base.get("validUntil")).plusDays(1);
            start = next.isAfter(today) ? next : today;   // renouvellement sans trou ni chevauchement
        }
        JTextField from = new JTextField(start.toString(), 10);
        JComboBox<String> duration = new JComboBox<>(new String[] {"1 an", "6 mois", "3 mois", "30 jours", "Dates personnalisees"});
        JTextField until = new JTextField(10);
        Runnable recompute = () -> {
            try {
                LocalDate s = LocalDate.parse(from.getText().trim());
                switch (duration.getSelectedIndex()) {
                    case 0: until.setText(s.plusYears(1).minusDays(1).toString()); break;
                    case 1: until.setText(s.plusMonths(6).minusDays(1).toString()); break;
                    case 2: until.setText(s.plusMonths(3).minusDays(1).toString()); break;
                    case 3: until.setText(s.plusDays(29).toString()); break;
                    default: break;
                }
                until.setEditable(duration.getSelectedIndex() == 4);
            } catch (RuntimeException ignored) {
                // date en cours de saisie
            }
        };
        duration.addActionListener(e -> recompute.run());
        onChange(from, recompute);
        recompute.run();
        JCheckBox mobile = new JCheckBox("Prise de commande sur telephone", true);
        JCheckBox kitchen = new JCheckBox("Envoi en cuisine", true);
        JSpinner stations = new JSpinner(new SpinnerNumberModel(0, 0, 999, 1));
        JTextField machine = new JTextField(20);
        JTextField notes = new JTextField(30);
        if (base != null) {
            Set<String> feats = new LinkedHashSet<>(strings(base.get("features")));
            mobile.setSelected(feats.contains(License.FEATURE_MOBILE));
            kitchen.setSelected(feats.contains(License.FEATURE_KITCHEN));
            stations.setValue((int) ManagerStore.num(base.get("maxStations")));
            machine.setText(base.get("machine") == null ? "" : (String) base.get("machine"));
        }
        JPanel f = form("Client / restaurant", client, "Debut (aaaa-mm-jj, 00:00 UTC)", from, "Duree", duration,
                "Fin INCLUSE (aaaa-mm-jj, jusqu'a 23:59:59 UTC)", until, "Fonctions", panel(mobile, kitchen),
                "Postes maximum (0 = sans limite)", stations,
                "Code du poste (vide = non lie ; transfert : code du nouveau PC)", machine, "Notes", notes);
        String title = base == null ? "Nouvelle licence" : "Renouveler / transferer " + base.get("serial");
        if (JOptionPane.showConfirmDialog(frame, f, title, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            LocalDate a = LocalDate.parse(from.getText().trim());
            LocalDate b = LocalDate.parse(until.getText().trim());
            if (b.isBefore(a)) {
                throw new IllegalArgumentException("La date de fin precede la date de debut.");
            }
            String m = machine.getText().trim().toUpperCase(Locale.ROOT);
            if (!m.isEmpty() && !m.matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}")) {
                throw new IllegalArgumentException("Code du poste invalide (forme XXXX-XXXX-XXXX-XXXX, affiche dans "
                        + "Activation / Licence d'OLPOS).");
            }
            Set<String> feats = new LinkedHashSet<>();
            feats.add(License.FEATURE_SALES);
            if (mobile.isSelected()) {
                feats.add(License.FEATURE_MOBILE);
            }
            if (kitchen.isSelected()) {
                feats.add(License.FEATURE_KITCHEN);
            }
            int st = (Integer) stations.getValue();
            Map<String, Object> rec = base == null
                    ? store.issue(((ClientItem) client.getSelectedItem()).id, a, b, feats, st, m, notes.getText())
                    : store.renew((String) base.get("serial"), a, b, feats, st, m, notes.getText());
            refreshLicenses();
            showCode(rec);
        } catch (Exception e) {
            error("Licence non creee : " + e.getMessage());
        }
    }

    private void showCode(Map<String, Object> l) {
        JTextArea code = new JTextArea((String) l.get("code"), 8, 60);
        code.setLineWrap(true);
        code.setEditable(false);
        code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(new JLabel("<html><b>" + l.get("serial") + "</b> (revision " + l.get("revision") + ") - " + l.get("customer")
                + " / " + l.get("restaurant") + "<br>Valable du " + FR.format(LocalDate.parse((String) l.get("validFrom")))
                + " (00:00 UTC) au " + FR.format(LocalDate.parse((String) l.get("validUntil")))
                + " inclus (23:59:59 UTC).<br>Code d'activation a coller dans OLPOS (Activation / Licence) :</html>"),
                BorderLayout.NORTH);
        p.add(new JScrollPane(code), BorderLayout.CENTER);
        Object[] options = {"Copier le code", "Enregistrer le fichier...", "Fermer"};
        int r = JOptionPane.showOptionDialog(frame, p, "Licence", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
                null, options, options[0]);
        if (r == 0) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection((String) l.get("code")), null);
        } else if (r == 1) {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File(l.get("serial") + "-r" + l.get("revision") + LicenseCodec.FILE_EXTENSION));
            if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                try {
                    Files.write(fc.getSelectedFile().toPath(), (l.get("code") + "\r\n").getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    error(e.getMessage());
                }
            }
        }
    }

    private void showHistory(String serial) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> l : store.history(serial)) {
            sb.append("Revision ").append(l.get("revision")).append(" - emise le ").append(l.get("issuedAt"))
                    .append("\n   du ").append(l.get("validFrom")).append(" au ").append(l.get("validUntil"))
                    .append(" inclus ; fonctions : ").append(String.join(", ", strings(l.get("features"))))
                    .append(" ; postes : ").append(ManagerStore.num(l.get("maxStations")) == 0 ? "sans limite" : l.get("maxStations"))
                    .append(l.get("machine") == null ? "" : " ; poste " + l.get("machine"))
                    .append(String.valueOf(l.get("notes")).isEmpty() ? "" : "\n   notes : " + l.get("notes")).append("\n\n");
        }
        JTextArea t = new JTextArea(sb.toString(), 15, 70);
        t.setEditable(false);
        JOptionPane.showMessageDialog(frame, new JScrollPane(t), "Historique " + serial, JOptionPane.PLAIN_MESSAGE);
    }

    private void exportCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("licences-olpos-" + LocalDate.now() + ".csv"));
        if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            try {
                store.exportCsv(fc.getSelectedFile(), new ArrayList<>(shownLicenses), Instant.now());
                info("Liste exportee (" + shownLicenses.size() + " licence(s)) : " + fc.getSelectedFile());
            } catch (Exception e) {
                error(e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ securite

    private JComponent securityTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JTextArea about = new JTextArea("Cle de signature : " + store.getKeyId() + "\nEmpreinte SHA-256 de la cle publique : "
                + LicenseCodec.fingerprint(store.getPublicKey()) + "\nDonnees (chiffrees) : " + store.getFile()
                + "\n\nLa cle PRIVEE ne quitte jamais ce programme : elle est chiffree (AES-256-GCM, cle derivee du mot "
                + "de passe par PBKDF2) dans le fichier de donnees.\nSi elle est perdue, OLPOS n'acceptera plus aucune "
                + "nouvelle licence : gardez une sauvegarde de la cle sur un support hors ligne (cle USB rangee a part).\n\n"
                + "OLPOS doit contenir la cle PUBLIQUE de ce gestionnaire : exportez-la puis placez-la dans\n"
                + "src/main/resources/com/olpos/license/trusted-keys.properties avant de construire l'installateur OLPOS.");
        about.setEditable(false);
        about.setOpaque(false);
        p.add(about, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 0, 4, 0);
        buttons.add(button("Sauvegarder la cle privee (chiffree, phrase de passe separee)...", this::backupKey), c);
        buttons.add(button("Exporter la cle publique (pour OLPOS)...", this::exportPublicKey), c);
        buttons.add(button("Sauvegarder toutes les donnees...", this::backupAll), c);
        buttons.add(button("Restaurer une sauvegarde des donnees...", this::restoreAll), c);
        buttons.add(button("Changer le mot de passe...", this::changePassword), c);
        p.add(buttons, BorderLayout.CENTER);
        return p;
    }

    private void backupKey() {
        JPasswordField p1 = new JPasswordField(20);
        JPasswordField p2 = new JPasswordField(20);
        if (JOptionPane.showConfirmDialog(frame, form("Phrase de passe de la sauvegarde (a noter et garder a part)", p1,
                "Confirmation", p2), "Sauvegarde de la cle", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        if (!Arrays.equals(p1.getPassword(), p2.getPassword())) {
            error("Les deux phrases de passe sont differentes.");
            return;
        }
        File f = chooseSave("olpos-cle-" + store.getKeyId() + ".olposkey");
        if (f != null) {
            try {
                store.exportPrivateKey(f, p1.getPassword());
                info("Cle sauvegardee (chiffree) : " + f + "\nRangez ce fichier hors ligne, a part de ce PC.");
            } catch (Exception e) {
                error(e.getMessage());
            }
        }
    }

    private void exportPublicKey() {
        File f = chooseSave("trusted-keys.properties");
        if (f != null) {
            try {
                store.exportPublicKey(f);
                info("Cle publique exportee : " + f);
            } catch (Exception e) {
                error(e.getMessage());
            }
        }
    }

    private void backupAll() {
        File f = chooseSave("olpos-license-manager-" + LocalDate.now() + ".olposlm");
        if (f != null) {
            try {
                store.backup(f);
                info("Sauvegarde creee (chiffree avec le mot de passe actuel du gestionnaire) : " + f);
            } catch (Exception e) {
                error(e.getMessage());
            }
        }
    }

    private void restoreAll() {
        File f = chooseOpen("Sauvegarde OLPOS License Manager (*.olposlm)", "olposlm");
        if (f == null) {
            return;
        }
        JPasswordField pw = new JPasswordField(20);
        if (JOptionPane.showConfirmDialog(frame, form("Mot de passe de cette sauvegarde", pw), "Restauration",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION
                || !confirm("Remplacer les donnees actuelles par cette sauvegarde ?\n(les donnees actuelles sont gardees a cote)")) {
            return;
        }
        try {
            store = ManagerStore.restore(dir, f, pw.getPassword());
            refreshClients();
            refreshLicenses();
            info("Sauvegarde restauree. Cle : " + store.getKeyId());
        } catch (Vault.WrongPasswordException e) {
            error("Mot de passe incorrect pour cette sauvegarde.");
        } catch (Exception e) {
            error("Restauration impossible : " + e.getMessage());
        }
    }

    private void changePassword() {
        JPasswordField old = new JPasswordField(20);
        JPasswordField p1 = new JPasswordField(20);
        JPasswordField p2 = new JPasswordField(20);
        if (JOptionPane.showConfirmDialog(frame, form("Mot de passe actuel", old, "Nouveau mot de passe", p1,
                "Confirmation", p2), "Mot de passe", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        if (!Arrays.equals(p1.getPassword(), p2.getPassword())) {
            error("Les deux mots de passe sont differents.");
            return;
        }
        try {
            store.changePassword(old.getPassword(), p1.getPassword());
            info("Mot de passe change.");
        } catch (Vault.WrongPasswordException e) {
            error("Mot de passe actuel incorrect.");
        } catch (Exception e) {
            error(e.getMessage());
        }
    }

    // ================================================================== outils

    private static final class ClientItem {
        final String id;
        final String label;

        ClientItem(Map<String, Object> c) {
            id = (String) c.get("id");
            label = c.get("customer") + " - " + c.get("restaurant");
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<Object>) o) {
                out.add(String.valueOf(x));
            }
        }
        return out;
    }

    private static DefaultTableModel readOnlyModel(String... cols) {
        return new DefaultTableModel(cols, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
    }

    private Map<String, Object> selected(JTable t, List<Map<String, Object>> rows) {
        int i = t.getSelectedRow();
        if (i < 0) {
            info("Selectionnez d'abord une ligne.");
            return null;
        }
        return rows.get(t.convertRowIndexToModel(i));
    }

    private static JPanel form(Object... labelsAndFields) {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 3, 3, 3);
        for (int i = 0; i < labelsAndFields.length; i += 2) {
            c.gridy = i / 2;
            c.gridx = 0;
            p.add(new JLabel(labelsAndFields[i] + " :"), c);
            c.gridx = 1;
            p.add((java.awt.Component) labelsAndFields[i + 1], c);
        }
        return p;
    }

    private static JPanel panel(JComponent... items) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (JComponent i : items) {
            p.add(i);
        }
        return p;
    }

    private static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static void onChange(JTextField f, Runnable r) {
        f.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                r.run();
            }
        });
    }

    private File chooseSave(String name) {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(name));
        return fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile() : null;
    }

    private File chooseOpen(String desc, String ext) {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter(desc, ext));
        return fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile() : null;
    }

    private boolean confirm(String text) {
        return JOptionPane.showConfirmDialog(frame, text, TITLE, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    private void info(String text) {
        JOptionPane.showMessageDialog(frame, text, TITLE, JOptionPane.INFORMATION_MESSAGE);
    }

    private void error(String text) {
        JOptionPane.showMessageDialog(frame, text, TITLE, JOptionPane.ERROR_MESSAGE);
    }

    @SuppressWarnings("unused")
    private static JDialog unused() {
        return null;
    }
}
