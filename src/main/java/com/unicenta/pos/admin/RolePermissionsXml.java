//    OLPOS (derive d'uniCenta oPOS) - edition simple des permissions d'un role
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.admin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ajoute, retire ou detecte une permission ({@code <class name="..."/>}) dans
 * le XML d'un role, sans toucher au reste du texte. Les permissions placees
 * dans un commentaire {@code <!-- ... -->} ne comptent pas (comme pour la
 * caisse, qui ignore les commentaires).
 */
public final class RolePermissionsXml {

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern PERMISSIONS_OPEN = Pattern.compile("<permissions\\b[^>]*>");

    private RolePermissionsXml() {
    }

    private static Pattern classTag(String permission) {
        return Pattern.compile("<class\\s+name\\s*=\\s*\"" + Pattern.quote(permission) + "\"\\s*/>");
    }

    /** true si la permission est active (hors commentaires). */
    public static boolean has(String xml, String permission) {
        if (xml == null) {
            return false;
        }
        String active = COMMENT.matcher(xml).replaceAll("");
        return classTag(permission).matcher(active).find();
    }

    /** Ajoute la permission juste apres {@code <permissions>} (cree la balise si le role est vide). */
    public static String add(String xml, String permission) {
        if (has(xml, permission)) {
            return xml;
        }
        String line = "<class name=\"" + permission + "\"/>";
        String text = xml == null ? "" : xml;
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        Matcher m = PERMISSIONS_OPEN.matcher(text);
        // premiere balise <permissions> hors commentaire
        while (m.find()) {
            if (!insideComment(text, m.start())) {
                return text.substring(0, m.end()) + nl + "    " + line + text.substring(m.end());
            }
        }
        if (text.trim().isEmpty()) {
            return "<permissions>" + nl + "    " + line + nl + "</permissions>" + nl;
        }
        // XML sans balise <permissions> : on ajoute la ligne a la fin
        return text + (text.endsWith(nl) ? "" : nl) + line + nl;
    }

    /** Retire toutes les occurrences actives de la permission (ligne entiere si elle est seule). */
    public static String remove(String xml, String permission) {
        if (xml == null) {
            return null;
        }
        Pattern line = Pattern.compile("[ \\t]*" + classTag(permission).pattern() + "[ \\t]*(\\r?\\n)?");
        StringBuilder out = new StringBuilder();
        Matcher m = line.matcher(xml);
        int last = 0;
        while (m.find()) {
            if (insideComment(xml, m.start())) {
                continue;
            }
            out.append(xml, last, m.start());
            last = m.end();
        }
        out.append(xml.substring(last));
        return out.toString();
    }

    private static boolean insideComment(String text, int pos) {
        int open = text.lastIndexOf("<!--", pos);
        if (open < 0) {
            return false;
        }
        int close = text.indexOf("-->", open);
        return close < 0 || close > pos;
    }
}
