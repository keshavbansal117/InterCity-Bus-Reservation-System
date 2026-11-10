package com.firstchoice.busreservation.util;

import java.net.URI;

/** All settings come from environment variables, so the same build runs locally and in the cloud. */
public final class Config {
    private Config() { }

    public static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    public static final int PORT = Integer.parseInt(env("PORT", "8080"));
    public static final String APP_TZ = env("APP_TZ", "Asia/Kolkata");
    public static final String BASE_URL = env("APP_BASE_URL", "http://localhost:" + PORT).replaceAll("/+$", "");
    public static final String WEB_DIR = env("WEB_DIR", "web");
    public static final String SCHEMA_FILE = env("SCHEMA_FILE", "sql/schema.sql");

    // database: JDBC_URL wins; else DATABASE_URL (postgres://user:pass@host/db, as hosts provide it); else local H2 file.
    public static final String JDBC_URL;
    public static final String JDBC_USER;
    public static final String JDBC_PASSWORD;
    static {
        String url = env("JDBC_URL", "");
        String user = env("JDBC_USER", "");
        String pass = env("JDBC_PASSWORD", "");
        String dbUrl = env("DATABASE_URL", "");
        if (url.isEmpty() && !dbUrl.isEmpty()) {
            URI u = URI.create(dbUrl);
            if (u.getUserInfo() != null) {
                String[] up = u.getUserInfo().split(":", 2);
                user = up[0];
                pass = up.length > 1 ? up[1] : "";
            }
            boolean local = "localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost());
            url = "jdbc:postgresql://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getPath()
                    + (local ? "" : "?sslmode=require");
        }
        if (url.isEmpty())
            url = "jdbc:h2:file:./data/firstchoice;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000";
        JDBC_URL = url; JDBC_USER = user; JDBC_PASSWORD = pass;
    }

    public static final String ADMIN_EMAIL = env("ADMIN_EMAIL", "admin@firstchoice.local").toLowerCase();
    public static final String ADMIN_PASSWORD = env("ADMIN_PASSWORD", "");
    public static final boolean SEED_SAMPLE_DATA = !env("SEED_SAMPLE_DATA", "true").equalsIgnoreCase("false");
    public static final boolean TRUST_PROXY = env("TRUST_PROXY", "false").equalsIgnoreCase("true");

    public static final int HOLD_MINUTES = Integer.parseInt(env("HOLD_MINUTES", "10"));
    public static final int DAYS_AHEAD = Integer.parseInt(env("DAYS_AHEAD", "30"));
    public static final int BOOKING_CLOSE_MIN = Integer.parseInt(env("BOOKING_CLOSE_MIN", "15"));

    // email (leave SMTP_HOST empty to run in log mode: emails are written to the console and the admin Outbox)
    public static final String SMTP_HOST = env("SMTP_HOST", "");
    public static final int SMTP_PORT = Integer.parseInt(env("SMTP_PORT", "587"));
    public static final String SMTP_USER = env("SMTP_USER", "");
    public static final String SMTP_PASSWORD = env("SMTP_PASSWORD", "");
    public static final String SMTP_FROM = env("SMTP_FROM", SMTP_USER.isEmpty() ? "no-reply@firstchoice.local" : SMTP_USER);
    public static final String SMTP_SECURITY = env("SMTP_SECURITY", "STARTTLS").toUpperCase();   // STARTTLS | SSL | NONE

    // payments (leave empty to run the built-in demo gateway)
    public static final String RAZORPAY_KEY_ID = env("RAZORPAY_KEY_ID", "");
    public static final String RAZORPAY_KEY_SECRET = env("RAZORPAY_KEY_SECRET", "");
}
