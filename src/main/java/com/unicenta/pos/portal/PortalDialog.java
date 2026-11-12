//    OLPOS (derive d'uniCenta oPOS) - reglages du portail Patron sur la caisse
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.portal;

import com.unicenta.basic.BasicException;
import com.unicenta.pos.forms.AppUser;
import com.unicenta.pos.forms.DataLogicSystem;
import com.unicenta.pos.forms.LoginScreen;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Fenetre "Portail Patron" de la caisse. Reservee a un compte avec mot de passe
 * et droit d'administration (verifie ici, sur la caisse) :
 * association avec un code a usage unique, etat de la synchronisation,
 * activation de la double authentification par compte.
 */
public final class PortalDialog {

    /** Ce que la caisse fournit a la fenetre. */
    public interface Control {
        PortalLink link();

        PortalAgent agent();

        /** (Re)demarre la synchronisation apres une association. */
        void restart();

        Map<String, Object> meta();
    }

    private PortalDialog() {
    }

    public static void open(Component parent, DataLogicSystem dl, Control ctl) {
        if (dl == null || ctl == null) {
            JOptionPane.showMessageDialog(parent, "La caisse n'est pas encore prete.", "Portail Patron",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        AppUser admin = askAdmin(parent, dl);
        if (admin == null) {
            return;
        }
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(parent), "Portail Patron (consultation à distance)",
                JDialog.ModalityType.APPLICATION_MODAL);
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        d.setContentPane(content);
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            content.removeAll();
            content.add(build(d, dl, ctl, refresh[0]), BorderLayout.CENTER);
            content.revalidate();
            content.repaint();
        };
        refresh[0].run();
        d.setSize(new Dimension(680, 620));
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
    }

    // ================================================================== administrateur

    private static AppUser askAdmin(Component parent, DataLogicSystem dl) {
        JTextField name = new JTextField(18);
        JPasswordField pwd = new JPasswordField(18);
        JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
        p.add(new JLabel("Réservé à l'administrateur. Identifiant :"));
        p.add(name);
        p.add(new JLabel("Mot de passe :"));
        p.add(pwd);
        int r = JOptionPane.showConfirmDialog(parent, p, "Portail Patron", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return null;
        }
        try {
            for (Object o : dl.listPeopleVisible()) {
                AppUser u = (AppUser) o;
                if (u.getName().equalsIgnoreCase(name.getText().trim())) {
                    if (!PortalAuthenticator.hasPassword(u) || !u.authenticate(new String(pwd.getPassword()))
                            || !PortalAuthenticator.hasPortalRight(u, dl)) {
                        break;
                    }
                    return u;
                }
            }
        } catch (BasicException e) {
            // message ci-dessous
        }
        JOptionPane.showMessageDialog(parent, "Identifiant ou mot de passe incorrect, ou compte sans droit "
                + "d'administration (un mot de passe est obligatoire).", "Portail Patron", JOptionPane.WARNING_MESSAGE);
        return null;
    }

    // ================================================================== contenu

    private static JPanel build(JDialog d, DataLogicSystem dl, Control ctl, Runnable refresh) {
        PortalLink link = ctl.link();
        JPanel box = new WidthTrackingPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        box.add(title("Liaison avec le portail"));
        if (link.isPaired()) {
            PortalAgent a = ctl.agent();
            DateFormat f = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
            String state = a == null ? "synchronisation arrêtée"
                    : (a.getLastContact() > 0 ? "dernier contact : " + f.format(new Date(a.getLastContact()))
                            : "aucun contact pour l'instant");
            box.add(text("<html>Restaurant : <b>" + esc(link.restaurantName()) + "</b> (code <b>" + esc(link.slug())
                    + "</b>)<br>Portail : " + esc(link.url()) + "<br>État : " + esc(state)
                    + (a != null ? "<br>Lots en attente d'envoi : " + a.pendingBatches() : "")
                    + (a != null && a.getLastError() != null ? "<br><span style='color:#B00020'>Dernière erreur : "
                            + esc(a.getLastError()) + "</span>" : "") + "</html>"));
            JButton sync = new JButton("Synchroniser maintenant");
            sync.addActionListener(e -> {
                PortalAgent ag = ctl.agent();
                if (ag == null) {
                    ctl.restart();
                    refresh.run();
                    return;
                }
                new Thread(() -> {
                    try {
                        ag.syncOnce();
                    } catch (Exception ex) {
                        // l'erreur est affichee dans l'etat
                    }
                    SwingUtilities.invokeLater(refresh);
                }, "portail-synchronisation-manuelle").start();
            });
            JButton unpair = new JButton("Dissocier cette caisse");
            unpair.addActionListener(e -> {
                if (JOptionPane.showConfirmDialog(d, "Dissocier cette caisse du portail ?\nLe patron ne pourra plus "
                        + "se connecter tant qu'une nouvelle association n'est pas faite.", "Portail Patron",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    try {
                        PortalAgent ag = ctl.agent();
                        if (ag != null) {
                            ag.stop();
                        }
                        link.unpair();
                    } catch (Exception ex) {
                        error(d, ex);
                    }
                    refresh.run();
                }
            });
            box.add(row(sync, unpair));
        } else {
            box.add(text("<html>Cette caisse n'est pas associée. Demandez à l'opérateur du portail un "
                    + "<b>code d'association</b> (valable 30 minutes, une seule fois).<br>La caisse se connecte "
                    + "elle-même au portail en HTTPS : aucun port n'est ouvert dans le restaurant.</html>"));
            JTextField url = new JTextField("https://");
            JTextField code = new JTextField();
            JPanel g = new JPanel(new GridLayout(0, 1, 2, 2));
            g.add(new JLabel("Adresse du portail :"));
            g.add(url);
            g.add(new JLabel("Code d'association :"));
            g.add(code);
            box.add(g);
            JButton pair = new JButton("Associer");
            LoginScreen.stylePrimary(pair);
            pair.addActionListener(e -> {
                try {
                    Map<String, Object> r = PortalAgent.pair(link, url.getText().trim(), code.getText().trim(),
                            hostName(), ctl.meta());
                    ctl.restart();
                    JOptionPane.showMessageDialog(d, "Caisse associée au restaurant « " + r.get("name")
                            + " ».\nCode restaurant à donner au patron : " + r.get("slug"), "Portail Patron",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    error(d, ex);
                }
                refresh.run();
            });
            box.add(row(pair));
        }

        box.add(Box.createVerticalStrut(12));
        box.add(title("Comptes autorisés (double authentification)"));
        box.add(text("<html>Seuls les comptes avec mot de passe et droit d'administration peuvent être autorisés. "
                + "Le patron se connecte avec son identifiant et son mot de passe OLPOS (vérifiés par cette caisse) "
                + "puis un code à 6 chiffres de son application d'authentification (Google Authenticator, "
                + "Microsoft Authenticator...). Le portail reste en lecture seule.</html>"));
        try {
            for (Object o : dl.listPeopleVisible()) {
                AppUser u = (AppUser) o;
                boolean pass = PortalAuthenticator.hasPassword(u);
                boolean right = PortalAuthenticator.hasPortalRight(u, dl);
                if (!right) {
                    continue;
                }
                boolean enrolled = link.totpSecret(u.getId()) != null;
                JLabel l = new JLabel("<html><b>" + esc(u.getName()) + "</b> — " + (!pass ? "sans mot de passe : refusé"
                        : enrolled ? "autorisé" : "non autorisé") + "</html>");
                JButton b;
                if (enrolled) {
                    b = new JButton("Retirer l'accès");
                    b.addActionListener(e -> {
                        try {
                            link.unenroll(u.getId());
                        } catch (Exception ex) {
                            error(d, ex);
                        }
                        refresh.run();
                    });
                } else {
                    b = new JButton("Autoriser");
                    b.setEnabled(pass);
                    b.addActionListener(e -> {
                        enroll(d, link, u);
                        refresh.run();
                    });
                }
                JPanel r = new JPanel(new BorderLayout(8, 0));
                r.add(l, BorderLayout.CENTER);
                r.add(b, BorderLayout.EAST);
                r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
                r.setAlignmentX(Component.LEFT_ALIGNMENT);
                box.add(r);
            }
        } catch (BasicException e) {
            box.add(text("Liste des comptes indisponible : " + e.getMessage()));
        }
        box.add(Box.createVerticalGlue());
        JButton close = new JButton("Fermer");
        close.addActionListener(e -> d.dispose());
        box.add(row(close));
        JScrollPane sp = new JScrollPane(box, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(sp);
        return wrap;
    }

    /** Activation : secret genere sur la caisse, confirme par un premier code. */
    private static void enroll(JDialog d, PortalLink link, AppUser u) {
        String secret = Totp.newSecret();
        String uri = Totp.uri(secret, u.getName(), "OLPOS " + (link.slug() == null ? "" : link.slug()));
        JTextArea info = new JTextArea("1. Dans l'application d'authentification du patron, ajoutez un compte "
                + "avec la clé ci-dessous (saisie manuelle, type « basé sur le temps »).\n\nClé : " + group(secret)
                + "\n\nAdresse otpauth (pour un générateur de QR code hors ligne) :\n" + uri
                + "\n\n2. Saisissez le code à 6 chiffres affiché par l'application :");
        info.setEditable(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        info.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        info.setColumns(48);
        info.setRows(12);
        JTextField code = new JTextField(8);
        JButton copy = new JButton("Copier la clé");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(secret), null));
        JPanel p = new JPanel(new BorderLayout(4, 8));
        p.add(new JScrollPane(info), BorderLayout.CENTER);
        p.add(row(new JLabel("Code :"), code, copy), BorderLayout.SOUTH);
        while (true) {
            int r = JOptionPane.showConfirmDialog(d, p, "Autoriser " + u.getName(), JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return;
            }
            if (Totp.verify(secret, code.getText().trim(), System.currentTimeMillis())) {
                try {
                    link.enroll(u.getId(), secret);
                    JOptionPane.showMessageDialog(d, u.getName() + " peut maintenant se connecter au portail Patron.",
                            "Portail Patron", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    error(d, ex);
                }
                return;
            }
            JOptionPane.showMessageDialog(d, "Code incorrect. Vérifiez l'heure du téléphone et de la caisse.",
                    "Portail Patron", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ================================================================== utilitaires

    /** Panneau qui suit la largeur de la fenetre (pas de defilement horizontal). */
    private static final class WidthTrackingPanel extends JPanel implements javax.swing.Scrollable {
        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static String group(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i += 4) {
            b.append(i == 0 ? "" : " ").append(s, i, Math.min(s.length(), i + 4));
        }
        return b.toString();
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "Caisse";
        }
    }

    private static JLabel title(String s) {
        JLabel l = new JLabel(s);
        l.setFont(new Font("Segoe UI", Font.BOLD, 16));
        l.setForeground(LoginScreen.NAVY);
        l.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JLabel text(String s) {
        JLabel l = new JLabel(s.startsWith("<html>") ? s.replace("<html>", "<html><div style='width:480px'>")
                .replace("</html>", "</div></html>") : s);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JPanel row(Component... cs) {
        JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 4));
        List<Component> list = new ArrayList<>(List.of(cs));
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                p.add(Box.createHorizontalStrut(8));
            }
            p.add(list.get(i));
        }
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        return p;
    }

    private static void error(Component c, Exception e) {
        JOptionPane.showMessageDialog(c, e.getMessage() == null ? e.toString() : e.getMessage(), "Portail Patron",
                JOptionPane.ERROR_MESSAGE);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

}
