package mawrid;

import java.sql.*;
import java.util.*;

/** Data access layer: the only place that talks to MySQL. All SQL is parameterized (NFR-2.1). */
public class Db {
    static String env(String k, String d) { String v = System.getenv(k); return v == null ? d : v; }

    // Works with DB_* variables, or with the MYSQL* variables that hosts like Railway provide.
    static final String URL = env("DB_URL", env("MYSQLHOST", null) != null
            ? "jdbc:mysql://" + env("MYSQLHOST", "") + ":" + env("MYSQLPORT", "3306") + "/" + env("MYSQLDATABASE", "railway") + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
            : "jdbc:mysql://localhost:3306/mawrid?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    static final String USER = env("DB_USER", env("MYSQLUSER", "root")), PASS = env("DB_PASS", env("MYSQLPASSWORD", ""));

    static Connection conn() throws SQLException { return DriverManager.getConnection(URL, USER, PASS); }

    static void bind(PreparedStatement ps, Object... a) throws SQLException {
        for (int i = 0; i < a.length; i++) ps.setObject(i + 1, a[i]);
    }

    static List<Map<String, Object>> rows(Connection c, String sql, Object... a) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, a);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData m = rs.getMetaData();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    for (int i = 1; i <= m.getColumnCount(); i++) {
                        Object o = rs.getObject(i);
                        if (o instanceof java.time.temporal.TemporalAccessor || o instanceof java.util.Date) o = String.valueOf(o);
                        r.put(m.getColumnLabel(i), o);
                    }
                    out.add(r);
                }
                return out;
            }
        }
    }

    static Map<String, Object> one(Connection c, String sql, Object... a) throws SQLException {
        List<Map<String, Object>> l = rows(c, sql, a);
        return l.isEmpty() ? null : l.get(0);
    }

    static int exec(Connection c, String sql, Object... a) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) { bind(ps, a); return ps.executeUpdate(); }
    }

    static long insert(Connection c, String sql, Object... a) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, a);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) { k.next(); return k.getLong(1); }
        }
    }
}
