//    OLPOS Portail Patron - stockage
//    Licence GPL v3 (voir ../LICENSE.md).
package com.olpos.portal;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base du portail (Apache Derby embarque). TOUTES les donnees de vente portent
 * la colonne {@code rid} (restaurant) et toutes les lectures du patron sont
 * filtrees par le restaurant de SA session, jamais par une valeur envoyee par
 * le navigateur.
 *
 * <p>Aucun mot de passe de caisse, aucune empreinte de mot de passe, aucun
 * secret de double authentification n'est stocke ici.</p>
 */
public final class PortalDb implements AutoCloseable {

    private final Connection c;

    private static final String[] SCHEMA = {
        "CREATE TABLE restaurants (rid VARCHAR(40) PRIMARY KEY, slug VARCHAR(60) NOT NULL UNIQUE,"
            + " name VARCHAR(200) NOT NULL, created BIGINT NOT NULL)",
        "CREATE TABLE pairing_codes (code_hash VARCHAR(64) PRIMARY KEY, rid VARCHAR(40) NOT NULL,"
            + " expires BIGINT NOT NULL, used BIGINT)",
        "CREATE TABLE devices (rid VARCHAR(40) PRIMARY KEY, token_hash VARCHAR(64) NOT NULL UNIQUE,"
            + " sign_key VARCHAR(200) NOT NULL, enc_key VARCHAR(800) NOT NULL, name VARCHAR(200),"
            + " paired BIGINT NOT NULL, last_seen BIGINT NOT NULL, last_sync BIGINT NOT NULL,"
            + " currency VARCHAR(10), locale VARCHAR(20), timezone VARCHAR(60), version VARCHAR(40))",
        "CREATE TABLE users_state (rid VARCHAR(40) NOT NULL, uid VARCHAR(255) NOT NULL, name VARCHAR(255),"
            + " stamp VARCHAR(80) NOT NULL, eligible SMALLINT NOT NULL, PRIMARY KEY (rid, uid))",
        "CREATE TABLE sessions (token_hash VARCHAR(64) PRIMARY KEY, rid VARCHAR(40) NOT NULL,"
            + " uid VARCHAR(255) NOT NULL, name VARCHAR(255), stamp VARCHAR(80) NOT NULL,"
            + " created BIGINT NOT NULL, last_used BIGINT NOT NULL, expires BIGINT NOT NULL)",
        "CREATE TABLE auth_requests (id VARCHAR(40) PRIMARY KEY, rid VARCHAR(40) NOT NULL, nonce VARCHAR(80) NOT NULL,"
            + " ciphertext VARCHAR(2000), created BIGINT NOT NULL, status VARCHAR(20) NOT NULL,"
            + " result CLOB, delivered SMALLINT NOT NULL DEFAULT 0)",
        "CREATE TABLE login_failures (k VARCHAR(400) PRIMARY KEY, n INT NOT NULL, first_at BIGINT NOT NULL,"
            + " locked_until BIGINT NOT NULL)",
        "CREATE TABLE batches (rid VARCHAR(40) NOT NULL, batch_id VARCHAR(80) NOT NULL, received BIGINT NOT NULL,"
            + " PRIMARY KEY (rid, batch_id))",
        "CREATE TABLE tickets (rid VARCHAR(40) NOT NULL, id VARCHAR(255) NOT NULL, ticket_no INT NOT NULL,"
            + " ticket_type INT NOT NULL, ts BIGINT NOT NULL, person VARCHAR(255), person_name VARCHAR(255),"
            + " money VARCHAR(255), place VARCHAR(255), order_number INT, base DOUBLE NOT NULL, tax DOUBLE NOT NULL,"
            + " total DOUBLE NOT NULL, discounts DOUBLE NOT NULL, json CLOB NOT NULL, PRIMARY KEY (rid, id))",
        "CREATE INDEX tickets_ts ON tickets (rid, ts)",
        "CREATE TABLE closures (rid VARCHAR(40) NOT NULL, money VARCHAR(255) NOT NULL, host VARCHAR(255),"
            + " seq INT, date_start BIGINT, date_end BIGINT, json CLOB NOT NULL, PRIMARY KEY (rid, money))",
        "CREATE TABLE open_orders (rid VARCHAR(40) PRIMARY KEY, ts BIGINT NOT NULL, json CLOB NOT NULL)",
    };

    public PortalDb(File dir) throws SQLException {
        System.setProperty("derby.stream.error.file",
                System.getProperty("derby.stream.error.file", new File(dir, "derby.log").getAbsolutePath()));
        c = DriverManager.getConnection("jdbc:derby:" + new File(dir, "portal-db").getAbsolutePath()
                .replace(File.separatorChar, '/') + ";create=true");
        c.setAutoCommit(true);
        if (!tableExists("RESTAURANTS")) {
            try (Statement st = c.createStatement()) {
                for (String sql : SCHEMA) {
                    st.executeUpdate(sql);
                }
            }
        }
        // versions des donnees (un lot plus ancien n'ecrase jamais une version plus recente)
        ensureColumn("TICKETS", "REV", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn("CLOSURES", "REV", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn("OPEN_ORDERS", "REV", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn("DEVICES", "LAST_SEQ", "BIGINT NOT NULL DEFAULT 0");
    }

    private void ensureColumn(String table, String column, String type) throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(null, null, table, column)) {
            if (rs.next()) {
                return;
            }
        }
        try (Statement st = c.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    private boolean tableExists(String name) throws SQLException {
        try (ResultSet rs = c.getMetaData().getTables(null, null, name, null)) {
            return rs.next();
        }
    }

    public Connection connection() {
        return c;
    }

    /** Execute une modification ; renvoie le nombre de lignes. */
    public synchronized int update(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    /** Lignes du resultat (noms de colonnes en minuscules). */
    public synchronized List<Map<String, Object>> query(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        Object v = rs.getObject(i);
                        if (v instanceof java.sql.Clob) {
                            java.sql.Clob cl = (java.sql.Clob) v;
                            v = cl.getSubString(1, (int) cl.length());
                        }
                        row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(), v);
                    }
                    out.add(row);
                }
                return out;
            }
        }
    }

    public Map<String, Object> one(String sql, Object... args) throws SQLException {
        List<Map<String, Object>> l = query(sql, args);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Transaction : tout ou rien. */
    public synchronized <T> T tx(SqlWork<T> work) throws SQLException {
        c.setAutoCommit(false);
        try {
            T r = work.run();
            c.commit();
            return r;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    public interface SqlWork<T> {
        T run() throws SQLException;
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof String && ((String) a).length() > 30000) {
                ps.setCharacterStream(i + 1, new java.io.StringReader((String) a), ((String) a).length());
            } else {
                ps.setObject(i + 1, a);
            }
        }
    }

    @Override
    public void close() {
        try {
            c.close();
        } catch (SQLException e) {
            // fermeture
        }
    }
}
