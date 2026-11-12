//    OLPOS (derive d'uniCenta oPOS) - ecran de connexion
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.forms;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;

/**
 * Ecran de connexion OLPOS : presentation courte, panneau "Connexion",
 * version, et fenetre "A propos / Licences" qui regroupe les mentions
 * detaillees (uniCenta, Openbravo, GPL v3, bibliotheques).
 *
 * <p>Avis juridiques (GPL v3, art. 5 d) : l'ecran de demarrage garde une ligne
 * visible avec le copyright d'origine, l'absence de garantie et le moyen de
 * lire la licence (bouton "A propos / Licences"). Aucun copyright ni fichier de
 * licence n'est supprime.</p>
 */
public final class LoginScreen {

    public static final Color NAVY = new Color(0x05, 0x1C, 0x4A);
    public static final Color TEAL = new Color(0x00, 0xC2, 0xA5);
    static final Color TEXT = new Color(0x3A, 0x47, 0x63);
    static final Color MUTED = new Color(0x6B, 0x75, 0x8A);
    static final Color BG = new Color(0xF4, 0xF7, 0xFA);

    public static final String TITLE = "OLPOS — Votre restaurant, simplement connecté.";
    public static final String DESCRIPTION = "Gérez vos ventes, prenez les commandes sur téléphone et "
            + "transmettez-les en cuisine depuis une seule solution.";
    /** Avis toujours visible au demarrage (copyright d'origine, garantie, licence). */
    public static final String NOTICE = "Version dérivée d'uniCenta oPOS © 2009-2018 uniCenta "
            + "et Openbravo POS — logiciel libre (GNU GPL v3), fourni sans aucune garantie. "
            + "Détails : « À propos / Licences ».";

    private final JLabel logo = new JLabel();
    private final JLabel description = new JLabel();
    private final JPanel hero = new JPanel(new GridBagLayout());

    /** Panneau central (logo, titre, description, avis, version, bouton A propos). */
    public JComponent hero() {
        hero.setOpaque(true);
        hero.setBackground(BG);
        JPanel card = new JPanel();
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        setLogo(Branding.icon(Branding.LOGO));
        logo.setAlignmentX(0.5f);
        card.add(logo);
        card.add(Box.createVerticalStrut(28));

        JLabel title = new JLabel("<html><div style='text-align:center;width:520px'>" + TITLE + "</div></html>");
        title.setFont(new Font("Segoe UI", Font.BOLD, 26));
        title.setForeground(NAVY);
        title.setAlignmentX(0.5f);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        card.add(title);
        card.add(Box.createVerticalStrut(14));

        JPanel accent = new JPanel();
        accent.setBackground(TEAL);
        accent.setMaximumSize(new Dimension(72, 4));
        accent.setPreferredSize(new Dimension(72, 4));
        accent.setAlignmentX(0.5f);
        card.add(accent);
        card.add(Box.createVerticalStrut(18));

        setDescription(null);
        description.setFont(new Font("Segoe UI", Font.PLAIN, 17));
        description.setForeground(TEXT);
        description.setAlignmentX(0.5f);
        description.setHorizontalAlignment(SwingConstants.CENTER);
        card.add(description);
        card.add(Box.createVerticalStrut(34));

        JButton about = new JButton("À propos / Licences");
        about.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        about.setForeground(NAVY);
        about.setFocusable(false);
        about.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        about.setPreferredSize(new Dimension(220, 44));
        about.setMaximumSize(new Dimension(220, 44));
        about.setAlignmentX(0.5f);
        about.addActionListener(e -> showAbout(hero));
        card.add(about);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weighty = 1;
        c.anchor = GridBagConstraints.CENTER;
        hero.add(card, c);

        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createEmptyBorder(0, 24, 14, 24));
        JLabel notice = new JLabel("<html><div style='text-align:center'>" + NOTICE + "</div></html>");
        notice.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        notice.setForeground(MUTED);
        notice.setHorizontalAlignment(SwingConstants.CENTER);
        foot.add(notice, BorderLayout.CENTER);
        JLabel version = new JLabel("OLPOS " + Branding.VERSION);
        version.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        version.setForeground(MUTED);
        version.setHorizontalAlignment(SwingConstants.CENTER);
        foot.add(version, BorderLayout.SOUTH);
        c.gridy = 1;
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        hero.add(foot, c);
        return hero;
    }

    /** Logo affiche en conservant ses proportions (hauteur maximale 120 px). */
    public void setLogo(ImageIcon icon) {
        if (icon == null || icon.getIconWidth() <= 0) {
            logo.setIcon(null);
            return;
        }
        int maxW = 420;
        int maxH = 120;
        double r = Math.min(1.0, Math.min(maxW / (double) icon.getIconWidth(), maxH / (double) icon.getIconHeight()));
        int w = (int) Math.round(icon.getIconWidth() * r);
        int h = (int) Math.round(icon.getIconHeight() * r);
        logo.setIcon(new ImageIcon(icon.getImage().getScaledInstance(w, h, Image.SCALE_SMOOTH)));
    }

    /** Description par defaut, ou texte personnalise (start.text : fichier HTML ou texte). */
    public void setDescription(String customHtml) {
        String body = customHtml == null || customHtml.trim().isEmpty() ? DESCRIPTION : customHtml.trim();
        if (body.regionMatches(true, 0, "<html>", 0, 6)) {
            body = body.substring(6);
        }
        description.setText("<html><div style='text-align:center;width:520px'>" + body + "</div></html>");
    }

    /**
     * Panneau des utilisateurs : titre "Connexion", bordure aux couleurs OLPOS.
     * Le contenu (boutons des utilisateurs, Quitter) reste celui d'uniCenta.
     */
    public static void styleUserPanel(JPanel panel, JComponent extraButton, JButton closeButton) {
        // Quitter et Activation / Licence cote a cote, en bas du panneau
        if (closeButton != null) {
            // Le bouton d'origine reste dans sa mise en page NetBeans mais masque ;
            // le nouveau "Quitter" declenche exactement la meme action.
            closeButton.setVisible(false);
            // la largeur du panneau des utilisateurs etait donnee par ce bouton : on la garde
            if (closeButton.getParent() instanceof JComponent) {
                ((JComponent) closeButton.getParent()).setPreferredSize(new Dimension(320, 1));
            }
            JButton quit = new JButton("Quitter", closeButton.getIcon());
            quit.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            quit.setFocusable(false);
            quit.addActionListener(e -> closeButton.doClick());
            JPanel row = new JPanel(new java.awt.GridLayout(1, 2, 10, 0));
            row.setOpaque(false);
            row.setPreferredSize(new Dimension(300, 56));
            if (extraButton != null) {
                extraButton.setFont(new Font("Segoe UI", Font.PLAIN, 14));
                if (extraButton instanceof JButton) {
                    ((JButton) extraButton).setText("<html><center>Activation<br>/ Licence</center></html>");
                }
                row.add(extraButton);
            }
            row.add(quit);
            extraButton = row;
        }
        styleUserPanel(panel, extraButton);
    }

    public static void styleUserPanel(JPanel panel, JComponent extraButton) {
        Border line = BorderFactory.createMatteBorder(4, 0, 0, 0, TEAL);
        panel.setBackground(Color.WHITE);
        panel.setOpaque(true);
        JLabel head = new JLabel("<html><b style='font-size:15px'>Connexion</b><br>"
                + "<span style='color:#6B758A'>Touchez votre nom pour commencer</span></html>");
        head.setForeground(NAVY);
        head.setBorder(BorderFactory.createEmptyBorder(16, 16, 10, 16));
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(Color.WHITE);
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(16, 0, 16, 16),
                BorderFactory.createCompoundBorder(line, BorderFactory.createLineBorder(new Color(0xDD, 0xE3, 0xEA)))));
        java.awt.Container parent = panel.getParent();
        Object constraints = null;
        if (parent != null && parent.getLayout() instanceof BorderLayout) {
            constraints = ((BorderLayout) parent.getLayout()).getConstraints(panel);
            parent.remove(panel);
        }
        wrapper.add(head, BorderLayout.NORTH);
        wrapper.add(panel, BorderLayout.CENTER);
        if (extraButton != null) {
            JPanel south = new JPanel(new BorderLayout());
            south.setOpaque(false);
            south.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
            south.add(extraButton);
            wrapper.add(south, BorderLayout.SOUTH);
        }
        if (parent != null) {
            parent.add(wrapper, constraints == null ? BorderLayout.EAST : constraints);
            parent.setBackground(BG);
        }
    }

    // ================================================================== A propos / Licences

    /** Fenetre "A propos / Licences" : mentions completes, GPL, bibliotheques. */
    public static void showAbout(Component parent) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, "OLPOS — À propos / Licences", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        JTabbedPane tabs = new JTabbedPane();

        JEditorPane about = new JEditorPane("text/html", Branding.aboutHtml());
        about.setEditable(false);
        about.setCaretPosition(0);
        tabs.addTab("À propos", new JScrollPane(about));

        File licences = licencesDir();
        addFileTab(tabs, "GNU GPL v3", licences == null ? null : new File(licences, "LICENCE-GPL-3.0.txt"));
        addFileTab(tabs, "uniCenta", licences == null ? null : new File(licences, "uniCenta-licence.txt"));
        addFileTab(tabs, "Bibliothèques", licences == null ? null : new File(licences, "BIBLIOTHEQUES-TIERCES.txt"));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        if (licences != null && Desktop.isDesktopSupported()) {
            JButton open = new JButton("Ouvrir le dossier des licences");
            open.addActionListener(e -> {
                try {
                    Desktop.getDesktop().open(licences);
                } catch (Exception ex) {
                    // dossier inaccessible : les onglets restent lisibles
                }
            });
            buttons.add(open);
        }
        JButton close = new JButton("Fermer");
        close.addActionListener(e -> d.dispose());
        buttons.add(close);

        JPanel root = new JPanel(new BorderLayout());
        root.add(tabs, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.setSize(820, 600);
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    private static void addFileTab(JTabbedPane tabs, String title, File f) {
        String text;
        try {
            text = f != null && f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
                    : "Fichier fourni avec OLPOS dans le dossier « licences » du programme"
                    + (f == null ? "." : " :\n" + f);
        } catch (java.io.IOException e) {
            text = "Lecture impossible : " + f;
        }
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        tabs.addTab(title, new JScrollPane(area));
    }

    /** Dossier "licences" de l'installation (a cote du dossier app). */
    static File licencesDir() {
        String dirname = System.getProperty("dirname.path");
        if (dirname == null) {
            return null;
        }
        File app = new File(dirname).getAbsoluteFile();
        File d = new File(app.getParentFile(), "licences");
        return d.isDirectory() ? d : null;
    }

    /** Bouton plein, couleurs OLPOS (bleu marine). */
    public static void stylePrimary(JButton b) {
        b.setBackground(NAVY);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Segoe UI", Font.BOLD, 14));
    }

    @SuppressWarnings("unused")
    private static void antialias(Graphics g) {
        ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }
}
