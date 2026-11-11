//    OLPOS (derive d'uniCenta oPOS) - API mobile
//    Licence GPL v3 (voir LICENSE.md).
package com.openbravo.pos.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON minimal (ecriture + lecture) sans dependance externe.
 *
 * <p>Ecriture : {@link Map} -> objet, {@link Collection}/tableau -> liste,
 * {@link Number}, {@link Boolean}, {@code null}, tout le reste -> chaine.</p>
 *
 * <p>Lecture : renvoie des {@link LinkedHashMap}, {@link ArrayList}, {@link String},
 * {@link Double}/{@link Long}, {@link Boolean} ou {@code null}. La profondeur
 * d'imbrication est limitee pour se proteger des requetes malveillantes.</p>
 */
public final class Json {

    private static final int MAX_DEPTH = 32;

    private Json() {
    }

    // ------------------------------------------------------------------ ecriture

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Collection<Object>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v instanceof Object[]) {
            List<Object> l = new ArrayList<>();
            for (Object o : (Object[]) v) {
                l.add(o);
            }
            write(sb, l);
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(Double.toString(d));
            }
        } else if (v instanceof Number) {
            sb.append(v.toString());
        } else {
            writeString(sb, v.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c == (char) 0x2028 || c == (char) 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** Petit constructeur d'objet ordonne : {@code Json.obj("a", 1, "b", "x")}. */
    public static Map<String, Object> obj(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Nombre d'arguments impair");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ lecture

    /** Erreur de syntaxe JSON. */
    public static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        ParseException(String msg, int pos) {
            super(msg + " (position " + pos + ")");
        }
    }

    public static Object parse(String text) throws ParseException {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != text.length()) {
            throw new ParseException("Caracteres inattendus apres la valeur", p.pos);
        }
        return v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object value(int depth) throws ParseException {
            if (depth > MAX_DEPTH) {
                throw new ParseException("Imbrication trop profonde", pos);
            }
            if (pos >= s.length()) {
                throw new ParseException("Fin inattendue", pos);
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': return literal("true", Boolean.TRUE);
                case 'f': return literal("false", Boolean.FALSE);
                case 'n': return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw new ParseException("Caractere inattendu '" + c + "'", pos);
            }
        }

        private Object literal(String word, Object v) throws ParseException {
            if (!s.startsWith(word, pos)) {
                throw new ParseException("Valeur invalide", pos);
            }
            pos += word.length();
            return v;
        }

        private Map<String, Object> object(int depth) throws ParseException {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw new ParseException("Cle attendue", pos);
                }
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                m.put(key, value(depth + 1));
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        private List<Object> array(int depth) throws ParseException {
            List<Object> l = new ArrayList<>();
            pos++; // [
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value(depth + 1));
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                expect(']');
                return l;
            }
        }

        private String string() throws ParseException {
            pos++; // "
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        break;
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw new ParseException("Sequence \\u incomplete", pos);
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw new ParseException("Sequence \\u invalide", pos);
                            }
                            pos += 4;
                            break;
                        default:
                            throw new ParseException("Echappement invalide", pos);
                    }
                } else if (c < 0x20) {
                    throw new ParseException("Caractere de controle dans une chaine", pos);
                } else {
                    sb.append(c);
                }
            }
            throw new ParseException("Chaine non terminee", pos);
        }

        private Object number() throws ParseException {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            boolean decimal = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    decimal = true;
                    pos++;
                } else {
                    break;
                }
            }
            String n = s.substring(start, pos);
            try {
                return decimal ? (Object) Double.valueOf(n) : (Object) Long.valueOf(n);
            } catch (NumberFormatException ex) {
                throw new ParseException("Nombre invalide", start);
            }
        }

        private void expect(char c) throws ParseException {
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw new ParseException("'" + c + "' attendu", pos);
            }
            pos++;
        }
    }
}
