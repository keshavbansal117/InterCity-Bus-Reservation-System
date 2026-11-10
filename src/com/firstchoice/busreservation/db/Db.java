package com.firstchoice.busreservation.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Minimal JDBC helper: a small connection pool, transactions (commit/rollback), and PreparedStatement helpers.
 * Every query uses '?' parameters, so user input can never change the SQL (no SQL injection).
 */
public final class Db {
    @FunctionalInterface public interface Work<T> { T run(Connection c) throws Exception; }

    private final String url, user, password;
    private final BlockingQueue<Connection> pool;

    public Db(String url, String user, String password, int size) throws SQLException {
        this.url = url; this.user = user; this.password = password;
        this.pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) pool.add(open());
    }

    private Connection open() throws SQLException {
        return user.isEmpty() ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
    }

    private Connection borrow() throws SQLException, InterruptedException {
        Connection c = pool.poll(15, TimeUnit.SECONDS);
        if (c == null) throw new SQLException("Database is busy. Please try again.");
        if (!c.isValid(2)) { try { c.close(); } catch (SQLException ignored) { } c = open(); }
        return c;
    }

    /** Runs the work in one transaction: commit on success, rollback on any exception. */
    public <T> T tx(Work<T> work) {
        Connection c = null;
        try {
            c = borrow();
            c.setAutoCommit(false);
            T result = work.run(c);
            c.commit();
            return result;
        } catch (Exception e) {
            if (c != null) try { c.rollback(); } catch (SQLException ignored) { }
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        } finally {
            if (c != null) { try { c.setAutoCommit(true); } catch (SQLException ignored) { } pool.offer(c); }
        }
    }

    // ---- one-shot helpers ----
    public List<Row> query(String sql, Object... p) { return tx(c -> query(c, sql, p)); }
    public Row one(String sql, Object... p) { return tx(c -> one(c, sql, p)); }
    public int exec(String sql, Object... p) { return tx(c -> update(c, sql, p)); }
    public long count(String sql, Object... p) { Row r = one(sql, p); return r == null ? 0 : r.lng("n"); }

    // ---- connection-level helpers (use inside tx) ----
    public static List<Row> query(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, p);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Row> rows = new ArrayList<>();
                while (rs.next()) {
                    Row r = new Row();
                    for (int i = 1; i <= md.getColumnCount(); i++) r.put(md.getColumnLabel(i).toLowerCase(), Row.convert(rs.getObject(i)));
                    rows.add(r);
                }
                return rows;
            }
        }
    }

    public static Row one(Connection c, String sql, Object... p) throws SQLException {
        List<Row> rows = query(c, sql, p);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static int update(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, p);
            return ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, Object[] p) throws SQLException {
        for (int i = 0; i < p.length; i++) {
            if (p[i] == null) ps.setNull(i + 1, Types.NULL); else ps.setObject(i + 1, p[i]);
        }
    }

    /** True when the failure was a UNIQUE / PRIMARY KEY violation (SQLSTATE 23505 on H2 and PostgreSQL). */
    public static boolean isDuplicate(Throwable t) {
        for (; t != null; t = t.getCause()) if (t instanceof SQLException s && "23505".equals(s.getSQLState())) return true;
        return false;
    }

    /** Creates tables from sql/schema.sql (safe to run on every start). */
    public void initSchema(Path schemaFile) throws IOException {
        String sql = Files.readString(schemaFile);
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\n")) if (!line.trim().startsWith("--")) cleaned.append(line).append('\n');
        tx(c -> {
            for (String stmt : cleaned.toString().split(";")) {
                if (stmt.isBlank()) continue;
                try (Statement st = c.createStatement()) { st.execute(stmt.trim()); }
            }
            return null;
        });
    }
}
