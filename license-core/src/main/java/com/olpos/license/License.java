//    OLPOS - licences (code commun a OLPOS et a OLPOS License Manager)
//    Licence GPL v3 (voir LICENSE.md).
package com.olpos.license;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contenu d'une licence OLPOS (avant signature).
 *
 * <h3>Dates (definition precise)</h3>
 * <ul>
 *   <li>{@code validFrom} et {@code validUntil} sont des DATES (aaaa-mm-jj)
 *       comparees en UTC, toutes deux INCLUSES.</li>
 *   <li>La licence commence au DEBUT de {@code validFrom} (00:00:00 UTC) et
 *       expire a la FIN de {@code validUntil} (23:59:59,999 UTC), c'est-a-dire
 *       a {@code validUntil + 1 jour} 00:00:00 UTC.</li>
 *   <li>L'affichage se fait en heure locale du PC (ex. a Vancouver, UTC-8,
 *       une licence "jusqu'au 11/11/2027" se termine le 11/11/2027 a 15:59).</li>
 * </ul>
 *
 * <h3>Forme canonique (signee)</h3>
 * Lignes {@code cle=valeur} dans un ordre fixe, valeurs encodees en URL
 * (UTF-8), fin de ligne {@code \n}. La signature porte sur ces octets exacts.
 */
public final class License {

    public static final String FORMAT = "OLPOS-LICENSE-1";

    /** Fonctions connues (une licence peut en autoriser d'autres plus tard). */
    public static final String FEATURE_SALES = "ventes";
    public static final String FEATURE_MOBILE = "mobile";
    public static final String FEATURE_KITCHEN = "cuisine";

    private final String serial;
    private final int revision;
    private final String customer;
    private final String restaurant;
    private final LocalDate validFrom;
    private final LocalDate validUntil;
    private final Set<String> features;
    private final int maxStations;      // 0 = pas de limite
    private final String machine;       // null = licence non liee a un poste
    private final Instant issuedAt;
    private final String keyId;

    public License(String serial, int revision, String customer, String restaurant, LocalDate validFrom,
            LocalDate validUntil, Set<String> features, int maxStations, String machine, Instant issuedAt, String keyId) {
        this.serial = require(serial, "serial");
        this.revision = revision;
        this.customer = require(customer, "client");
        this.restaurant = require(restaurant, "restaurant");
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        if (validFrom == null || validUntil == null || validUntil.isBefore(validFrom)) {
            throw new IllegalArgumentException("Dates de validite incoherentes");
        }
        this.features = Collections.unmodifiableSet(new LinkedHashSet<>(features));
        if (maxStations < 0) {
            throw new IllegalArgumentException("Nombre de postes invalide");
        }
        this.maxStations = maxStations;
        this.machine = machine == null || machine.trim().isEmpty() ? null : machine.trim();
        this.issuedAt = issuedAt;
        this.keyId = require(keyId, "keyId");
    }

    private static String require(String v, String name) {
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("Champ obligatoire : " + name);
        }
        return v.trim();
    }

    public String getSerial() {
        return serial;
    }

    /** Revision : 1 a la creation, +1 a chaque renouvellement (meme numero de serie). */
    public int getRevision() {
        return revision;
    }

    public String getCustomer() {
        return customer;
    }

    public String getRestaurant() {
        return restaurant;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    /** Debut de validite : validFrom 00:00:00 UTC. */
    public Instant getStart() {
        return validFrom.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Fin de validite (exclue) : lendemain de validUntil, 00:00:00 UTC. */
    public Instant getEnd() {
        return validUntil.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public Set<String> getFeatures() {
        return features;
    }

    public boolean hasFeature(String f) {
        return features.contains(f);
    }

    public int getMaxStations() {
        return maxStations;
    }

    /** Code du poste autorise (licence liee a un PC), ou null. */
    public String getMachine() {
        return machine;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public String getKeyId() {
        return keyId;
    }

    // ------------------------------------------------------------------ forme canonique

    public byte[] canonicalBytes() {
        return canonical().getBytes(StandardCharsets.UTF_8);
    }

    public String canonical() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("key", keyId);
        m.put("serial", serial);
        m.put("revision", Integer.toString(revision));
        m.put("customer", customer);
        m.put("restaurant", restaurant);
        m.put("validFrom", validFrom.toString());
        m.put("validUntil", validUntil.toString());
        m.put("features", String.join(",", features));
        m.put("maxStations", Integer.toString(maxStations));
        m.put("machine", machine == null ? "" : machine);
        m.put("issuedAt", issuedAt.toString());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(e.getKey()).append('=').append(enc(e.getValue())).append('\n');
        }
        return sb.toString();
    }

    public static License parseCanonical(byte[] bytes) throws LicenseException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new LicenseException(LicenseException.Reason.MALFORMED, "Ligne de licence invalide");
            }
            m.put(line.substring(0, eq), dec(line.substring(eq + 1)));
        }
        if (!FORMAT.equals(m.get("format"))) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "Format de licence inconnu");
        }
        try {
            Set<String> features = new LinkedHashSet<>();
            for (String f : m.getOrDefault("features", "").split(",")) {
                if (!f.trim().isEmpty()) {
                    features.add(f.trim());
                }
            }
            License l = new License(m.get("serial"), Integer.parseInt(m.get("revision")), m.get("customer"),
                    m.get("restaurant"), LocalDate.parse(m.get("validFrom")), LocalDate.parse(m.get("validUntil")),
                    features, Integer.parseInt(m.get("maxStations")), m.get("machine"),
                    Instant.parse(m.get("issuedAt")), m.get("key"));
            if (!l.canonical().equals(text)) {
                // toute variation (ordre, espaces, champ en plus) est refusee
                throw new LicenseException(LicenseException.Reason.MALFORMED, "Licence non canonique");
            }
            return l;
        } catch (NullPointerException | IllegalArgumentException | DateTimeParseException e) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "Licence incomplete ou invalide : " + e.getMessage());
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    /** Liste triee des fonctions pour l'affichage. */
    public List<String> featureList() {
        return new ArrayList<>(features);
    }
}
