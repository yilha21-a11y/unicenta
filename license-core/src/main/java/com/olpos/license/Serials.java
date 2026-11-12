//    OLPOS - licences (code commun a OLPOS et a OLPOS License Manager)
//    Licence GPL v3 (voir LICENSE.md).
package com.olpos.license;

import java.security.SecureRandom;

/**
 * Identifiants de serie : {@code OLP-XXXX-XXXX-XXXX-XXXX-C}, 80 bits aleatoires
 * (generateur cryptographique) en base 32 de Crockford (sans I, L, O, U) et un
 * caractere de controle contre les fautes de frappe.
 *
 * <p>Le numero de serie n'est PAS un secret et ne suffit pas a activer OLPOS :
 * seule la licence signee compte. Il n'existe aucun calcul permettant de
 * fabriquer un code valable a partir d'un numero de serie.</p>
 */
public final class Serials {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private Serials() {
    }

    public static String newSerial(SecureRandom random) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            body.append(ALPHABET.charAt(random.nextInt(32)));
        }
        String b = body.toString();
        return "OLP-" + b.substring(0, 4) + "-" + b.substring(4, 8) + "-" + b.substring(8, 12) + "-"
                + b.substring(12, 16) + "-" + check(b);
    }

    /** Verifie la forme et le caractere de controle. */
    public static boolean isWellFormed(String serial) {
        if (serial == null || !serial.matches("OLP-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]")) {
            return false;
        }
        String b = serial.substring(4, 23).replace("-", "");
        for (char c : b.toCharArray()) {
            if (ALPHABET.indexOf(c) < 0) {
                return false;
            }
        }
        return check(b) == serial.charAt(serial.length() - 1);
    }

    private static char check(String b) {
        int sum = 0;
        for (int i = 0; i < b.length(); i++) {
            sum = (sum * 31 + ALPHABET.indexOf(b.charAt(i)) * (i + 1)) % 32;
        }
        return ALPHABET.charAt(sum);
    }
}
