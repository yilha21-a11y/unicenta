//    OLPOS - licences (code commun a OLPOS et a OLPOS License Manager)
//    Licence GPL v3 (voir LICENSE.md).
package com.olpos.license;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Etat d'une licence a un instant donne (comparaisons en UTC).
 */
public final class LicenseEvaluation {

    public enum Status {
        /** Aucune licence installee. */
        NONE,
        /** Valable. */
        VALID,
        /** Pas encore valable (date de debut dans le futur). */
        NOT_YET_VALID,
        /** Expiree. */
        EXPIRED,
        /** Licence liee a un autre poste. */
        WRONG_MACHINE,
        /** Horloge du PC reculee : dates non verifiables. */
        CLOCK_ROLLBACK,
        /** Trop de postes pour cette licence. */
        TOO_MANY_STATIONS,
        /** Licence refusee (signature, format). */
        INVALID
    }

    /** Seuils d'avertissement avant expiration (jours). */
    public static final int[] WARNING_DAYS = {30, 7, 1};

    private final Status status;
    private final License license;
    private final long daysLeft;
    private final String message;

    private LicenseEvaluation(Status status, License license, long daysLeft, String message) {
        this.status = status;
        this.license = license;
        this.daysLeft = daysLeft;
        this.message = message;
    }

    public static LicenseEvaluation none() {
        return new LicenseEvaluation(Status.NONE, null, 0,
                "OLPOS n'est pas active : saisissez un code d'activation ou importez un fichier de licence.");
    }

    public static LicenseEvaluation invalid(String message) {
        return new LicenseEvaluation(Status.INVALID, null, 0, message);
    }

    public static LicenseEvaluation clockRollback(License l, Instant lastSeen, ZoneId zone) {
        return new LicenseEvaluation(Status.CLOCK_ROLLBACK, l, 0,
                "La date ou l'heure de ce PC est anterieure a la derniere heure connue ("
                + format(lastSeen, zone) + "). Corrigez la date et l'heure de Windows puis redemarrez OLPOS.");
    }

    public static LicenseEvaluation tooManyStations(License l, int max) {
        return new LicenseEvaluation(Status.TOO_MANY_STATIONS, l, 0,
                "Cette licence autorise " + max + " poste(s), deja utilise(s) par d'autres caisses. "
                + "Liberez un poste dans l'ecran Activation / Licence, ou demandez une licence plus large.");
    }

    /**
     * Evalue dates et poste.
     * @param machineCode code du poste actuel (pour une licence liee a un poste)
     */
    public static LicenseEvaluation evaluate(License l, Instant now, String machineCode) {
        if (l.getMachine() != null && !l.getMachine().equalsIgnoreCase(machineCode)) {
            return new LicenseEvaluation(Status.WRONG_MACHINE, l, 0, "Cette licence est liee a un autre poste ("
                    + l.getMachine() + "). Code de ce poste : " + machineCode
                    + ". En cas de remplacement du PC, demandez un transfert de licence.");
        }
        if (now.isBefore(l.getStart())) {
            return new LicenseEvaluation(Status.NOT_YET_VALID, l, 0, "Licence valable a partir du "
                    + l.getValidFrom() + " (00:00 UTC).");
        }
        if (!now.isBefore(l.getEnd())) {
            return new LicenseEvaluation(Status.EXPIRED, l, 0, "Licence expiree depuis le " + l.getValidUntil()
                    + " (fin de journee UTC). Importez la licence renouvelee : aucune donnee n'est perdue.");
        }
        long left = daysLeft(now, l.getEnd());
        return new LicenseEvaluation(Status.VALID, l, left, left <= 1
                ? "La licence expire dans moins d'un jour." : "Licence valable encore " + left + " jour(s).");
    }

    /** Jours restants, arrondis au jour superieur (1 = expire dans moins de 24 h). */
    public static long daysLeft(Instant now, Instant end) {
        long ms = Duration.between(now, end).toMillis();
        if (ms <= 0) {
            return 0;
        }
        return (ms + 86_400_000L - 1) / 86_400_000L;
    }

    /**
     * Seuil d'avertissement atteint (30, 7 ou 1 jour), ou 0.
     */
    public int warningThreshold() {
        if (status != Status.VALID) {
            return 0;
        }
        int t = 0;
        for (int w : WARNING_DAYS) {
            if (daysLeft <= w) {
                t = w;
            }
        }
        return t;
    }

    /** Les NOUVELLES ventes sont autorisees (les commandes en cours peuvent toujours etre terminees). */
    public boolean allowsNewSales() {
        return status == Status.VALID && license.hasFeature(License.FEATURE_SALES);
    }

    public boolean allows(String feature) {
        return status == Status.VALID && license.hasFeature(feature);
    }

    public Status getStatus() {
        return status;
    }

    public License getLicense() {
        return license;
    }

    public long getDaysLeft() {
        return daysLeft;
    }

    public String getMessage() {
        return message;
    }

    public static String format(Instant i, ZoneId zone) {
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE).withZone(zone).format(i);
    }

    /** Ex. "jusqu'au 11/11/2027 inclus (fin le 11/11/2027 a 15:59, heure locale)". */
    public static String describeEnd(License l, ZoneId zone) {
        Instant last = l.getEnd().minusSeconds(60);
        return "jusqu'au " + DateTimeFormatter.ofPattern("dd/MM/yyyy").format(l.getValidUntil())
                + " inclus (fin le " + format(last, zone) + ", heure locale)";
    }
}
