package com.firstchoice.busreservation.service;

import com.firstchoice.busreservation.db.Db;
import com.firstchoice.busreservation.db.Row;
import com.firstchoice.busreservation.mail.Notifier;
import com.firstchoice.busreservation.util.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** Accounts: register, login/logout, sessions, forgot/reset password, email verification, profile. */
public final class AuthService {
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$");
    private static final int MAX_FAILS = 5, LOCK_MINUTES = 15, SESSION_DAYS = 30;

    private final Db db;
    private final Notifier mail;
    private final RateLimiter limiter;

    public AuthService(Db db, Notifier mail, RateLimiter limiter) { this.db = db; this.mail = mail; this.limiter = limiter; }

    // ---------- validation ----------
    static String normEmail(String e) {
        String s = e == null ? "" : e.trim().toLowerCase();
        if (s.length() > 160 || !EMAIL.matcher(s).matches()) throw ApiException.bad("Enter a valid email address.");
        return s;
    }

    public static String normPhone(String p) {
        String d = p == null ? "" : p.replaceAll("[^0-9]", "");
        if (d.length() == 12 && d.startsWith("91")) d = d.substring(2);
        if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
        if (!d.matches("[6-9][0-9]{9}")) throw ApiException.bad("Enter a valid 10-digit mobile number.");
        return d;
    }

    static void checkPassword(String pw) {
        if (pw == null || pw.length() < 8 || pw.length() > 100 || !pw.matches(".*[A-Za-z].*") || !pw.matches(".*[0-9].*"))
            throw ApiException.bad("Password must be at least 8 characters and include a letter and a number.");
    }

    public static Map<String, Object> userMap(Row u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.str("id")); m.put("name", u.str("name")); m.put("email", u.str("email"));
        m.put("phone", u.str("phone")); m.put("role", u.str("role")); m.put("emailVerified", u.bool("email_verified"));
        return m;
    }

    // ---------- register / login ----------
    public Map<String, Object> register(String name, String email, String phone, String password, String ip) {
        if (!limiter.allow("register:" + ip, 10, 3600)) throw new ApiException(429, "Too many sign-ups from your network. Please try later.");
        String n = name == null ? "" : name.trim();
        if (n.length() < 2 || n.length() > 100) throw ApiException.bad("Enter your full name.");
        String e = normEmail(email), ph = normPhone(phone);
        checkPassword(password);
        String id = Security.id();
        Row user = db.tx(c -> {
            if (Db.one(c, "SELECT id FROM users WHERE email=?", e) != null)
                throw ApiException.conflict("An account with this email already exists. Log in, or use 'Forgot password'.");
            Db.update(c, "INSERT INTO users(id,name,email,phone,password_hash,role,email_verified,failed_logins,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    id, n, e, ph, Security.hashPassword(password), "PASSENGER", false, 0, LocalDateTime.now());
            return Db.one(c, "SELECT * FROM users WHERE id=?", id);
        });
        sendVerification(user);
        return session(user);
    }

    public Map<String, Object> login(String email, String password, String ip) {
        if (!limiter.allow("login:" + ip, 30, 600)) throw new ApiException(429, "Too many attempts. Please wait a few minutes and try again.");
        String e = email == null ? "" : email.trim().toLowerCase();
        Row u = db.one("SELECT * FROM users WHERE email=?", e);
        if (u == null) { Security.verifyPassword(password == null ? "" : password, Security.hashPassword("x")); throw ApiException.unauthorized("Incorrect email or password."); }
        LocalDateTime now = LocalDateTime.now();
        if (u.dt("locked_until") != null && u.dt("locked_until").isAfter(now)) {
            long mins = Math.max(1, Duration.between(now, u.dt("locked_until")).toMinutes() + 1);
            throw new ApiException(429, "Too many wrong passwords. This account is locked for about " + mins + " more minute(s). You can reset your password instead.");
        }
        if (password == null || !Security.verifyPassword(password, u.str("password_hash"))) {
            int fails = u.integer("failed_logins") + 1;
            if (fails >= MAX_FAILS) db.exec("UPDATE users SET failed_logins=0, locked_until=? WHERE id=?", now.plusMinutes(LOCK_MINUTES), u.str("id"));
            else db.exec("UPDATE users SET failed_logins=? WHERE id=?", fails, u.str("id"));
            throw ApiException.unauthorized("Incorrect email or password.");
        }
        db.exec("UPDATE users SET failed_logins=0, locked_until=NULL WHERE id=?", u.str("id"));
        return session(u);
    }

    private Map<String, Object> session(Row user) {
        String token = Security.randomToken(32);
        LocalDateTime now = LocalDateTime.now();
        db.exec("INSERT INTO sessions(token_hash,user_id,created_at,expires_at) VALUES (?,?,?,?)",
                Security.sha256(token), user.str("id"), now, now.plusDays(SESSION_DAYS));
        db.exec("DELETE FROM sessions WHERE expires_at < ?", now);
        Map<String, Object> m = new LinkedHashMap<>(userMap(user));
        m.put("token", token);
        return m;
    }

    /** Finds the user for a bearer token, or null. */
    public Row userForToken(String token) {
        if (token == null || token.length() != 64) return null;
        return db.one("SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at > ?",
                Security.sha256(token), LocalDateTime.now());
    }

    public void logout(String token) { if (token != null) db.exec("DELETE FROM sessions WHERE token_hash=?", Security.sha256(token)); }
    public void logoutEverywhere(String userId) { db.exec("DELETE FROM sessions WHERE user_id=?", userId); }

    // ---------- tokens (reset / verify) ----------
    private String issueToken(String userId, String purpose, int minutes) {
        String raw = Security.randomToken(32);
        db.exec("DELETE FROM auth_tokens WHERE user_id=? AND purpose=?", userId, purpose);
        db.exec("INSERT INTO auth_tokens(token_hash,user_id,purpose,expires_at) VALUES (?,?,?,?)",
                Security.sha256(raw), userId, purpose, LocalDateTime.now().plusMinutes(minutes));
        return raw;
    }

    private String consumeToken(String purpose, String raw) {
        if (raw == null || raw.length() != 64) throw ApiException.bad("This link is invalid or has expired. Please request a new one.");
        String hash = Security.sha256(raw);
        return db.tx(c -> {
            Row t = Db.one(c, "SELECT * FROM auth_tokens WHERE token_hash=? AND purpose=? AND used_at IS NULL AND expires_at > ?", hash, purpose, LocalDateTime.now());
            if (t == null || Db.update(c, "UPDATE auth_tokens SET used_at=? WHERE token_hash=? AND used_at IS NULL", LocalDateTime.now(), hash) == 0)
                throw ApiException.bad("This link is invalid or has expired. Please request a new one.");
            return t.str("user_id");
        });
    }

    // ---------- forgot / reset password ----------
    /** Always answers the same way, so nobody can use this form to discover which emails have accounts. */
    public void forgotPassword(String email, String ip) {
        String e = normEmail(email);
        if (!limiter.allow("forgot-ip:" + ip, 10, 3600) || !limiter.allow("forgot:" + e, 3, 3600))
            throw new ApiException(429, "Too many reset requests. Please try again in an hour.");
        Row u = db.one("SELECT * FROM users WHERE email=?", e);
        if (u == null) return;
        String token = issueToken(u.str("id"), "RESET", 30);
        mail.send(e, "Reset your First Choice password",
                "Hi " + u.str("name") + ",\n\nWe received a request to reset your password. Use this link within 30 minutes:\n\n"
                        + Config.BASE_URL + "/#/reset?token=" + token + "\n\nIf you did not ask for this, you can ignore this email; your password will not change.\n\nFirst Choice", true);
    }

    public void resetPassword(String token, String newPassword) {
        checkPassword(newPassword);
        String userId = consumeToken("RESET", token);
        db.exec("UPDATE users SET password_hash=?, failed_logins=0, locked_until=NULL WHERE id=?", Security.hashPassword(newPassword), userId);
        logoutEverywhere(userId);
        Row u = db.one("SELECT * FROM users WHERE id=?", userId);
        mail.send(u.str("email"), "Your First Choice password was changed",
                "Hi " + u.str("name") + ",\n\nYour password was just changed and you were logged out on all devices. If this was not you, reset it again right away.\n\nFirst Choice", false);
    }

    // ---------- email verification ----------
    private void sendVerification(Row u) {
        String token = issueToken(u.str("id"), "VERIFY", 60 * 24);
        mail.send(u.str("email"), "Welcome to First Choice - confirm your email",
                "Hi " + u.str("name") + ",\n\nWelcome aboard! Please confirm your email so we can send your tickets:\n\n"
                        + Config.BASE_URL + "/#/verify?token=" + token + "\n\nFirst Choice", true);
    }

    public void verifyEmail(String token) {
        db.exec("UPDATE users SET email_verified=? WHERE id=?", true, consumeToken("VERIFY", token));
    }

    public void resendVerification(Row user, String ip) {
        if (user.bool("email_verified")) return;
        if (!limiter.allow("verify:" + user.str("id"), 3, 3600)) throw new ApiException(429, "Please wait before requesting another email.");
        sendVerification(user);
    }

    // ---------- profile ----------
    public Map<String, Object> updateProfile(String userId, String name, String phone) {
        String n = name == null ? "" : name.trim();
        if (n.length() < 2 || n.length() > 100) throw ApiException.bad("Enter your full name.");
        db.exec("UPDATE users SET name=?, phone=? WHERE id=?", n, normPhone(phone), userId);
        return userMap(db.one("SELECT * FROM users WHERE id=?", userId));
    }

    public void changePassword(Row user, String current, String next, String keepToken) {
        if (!Security.verifyPassword(current == null ? "" : current, user.str("password_hash"))) throw ApiException.bad("Your current password is incorrect.");
        checkPassword(next);
        db.exec("UPDATE users SET password_hash=? WHERE id=?", Security.hashPassword(next), user.str("id"));
        db.exec("DELETE FROM sessions WHERE user_id=? AND token_hash<>?", user.str("id"), Security.sha256(keepToken));
    }

    // ---------- admin bootstrap ----------
    public void ensureAdmin() {
        if (db.count("SELECT COUNT(*) n FROM users WHERE role='ADMIN'") > 0) return;
        boolean generated = Config.ADMIN_PASSWORD.isEmpty();
        String pw = generated ? Security.randomPassword(12) : Config.ADMIN_PASSWORD;
        db.exec("INSERT INTO users(id,name,email,phone,password_hash,role,email_verified,failed_logins,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                Security.id(), "Administrator", Config.ADMIN_EMAIL, "9000000000", Security.hashPassword(pw), "ADMIN", true, 0, LocalDateTime.now());
        System.out.println("\n==============================================================");
        System.out.println(" Admin account created:  " + Config.ADMIN_EMAIL);
        if (generated) {
            System.out.println(" Password (shown only once):  " + pw);
            System.out.println(" Log in and change it from Profile. Or set ADMIN_PASSWORD before first start.");
        }
        System.out.println("==============================================================\n");
    }

    public List<Row> listUsers() {
        return db.query("SELECT u.id, u.name, u.email, u.phone, u.role, u.email_verified, u.created_at, "
                + "(SELECT COUNT(*) FROM bookings b WHERE b.user_id=u.id AND b.status IN ('CONFIRMED','CANCELLED')) bookings "
                + "FROM users u ORDER BY u.created_at DESC LIMIT 500");
    }
}
