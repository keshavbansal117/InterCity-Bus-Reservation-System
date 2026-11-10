package com.firstchoice.busreservation.web;

import com.firstchoice.busreservation.db.Db;
import com.firstchoice.busreservation.db.Row;
import com.firstchoice.busreservation.mail.Notifier;
import com.firstchoice.busreservation.mail.SmtpMailer;
import com.firstchoice.busreservation.pay.DemoGateway;
import com.firstchoice.busreservation.pay.PaymentGateway;
import com.firstchoice.busreservation.pay.RazorpayGateway;
import com.firstchoice.busreservation.service.*;
import com.firstchoice.busreservation.util.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** First Choice web server: static website + JSON API under /api. Runs on the JDK's built-in HTTP server. */
public final class WebApp {
    private final Db db;
    private final AuthService auth;
    private final CatalogService catalog;
    private final BookingService bookings;
    private final AdminService admin;
    private final Notifier notifier;
    private final RateLimiter limiter = new RateLimiter();
    private final Path webRoot;

    private WebApp(Db db, Notifier notifier, PaymentGateway gateway, Path webRoot) {
        this.db = db; this.notifier = notifier; this.webRoot = webRoot;
        this.auth = new AuthService(db, notifier, limiter);
        this.catalog = new CatalogService(db);
        this.bookings = new BookingService(db, notifier, gateway, catalog);
        this.admin = new AdminService(db);
    }

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone(Config.APP_TZ));
        Path web = Path.of(Config.WEB_DIR).toAbsolutePath().normalize();
        if (!Files.isRegularFile(web.resolve("index.html"))) {
            System.err.println("Cannot find " + web.resolve("index.html") + ". Run this from the project folder.");
            System.exit(1);
        }
        Files.createDirectories(Path.of("data"));
        Db db = new Db(Config.JDBC_URL, Config.JDBC_USER, Config.JDBC_PASSWORD, 8);
        db.initSchema(Path.of(Config.SCHEMA_FILE));

        SmtpMailer smtp = Config.SMTP_HOST.isEmpty() ? null
                : new SmtpMailer(Config.SMTP_HOST, Config.SMTP_PORT, Config.SMTP_USER, Config.SMTP_PASSWORD, Config.SMTP_FROM, Config.SMTP_SECURITY);
        Notifier notifier = new Notifier(db, smtp);
        boolean razorpay = !Config.RAZORPAY_KEY_ID.isEmpty() && !Config.RAZORPAY_KEY_SECRET.isEmpty();
        PaymentGateway gateway = razorpay ? new RazorpayGateway(Config.RAZORPAY_KEY_ID, Config.RAZORPAY_KEY_SECRET) : new DemoGateway();

        WebApp app = new WebApp(db, notifier, gateway, web);
        app.auth.ensureAdmin();
        if (Config.SEED_SAMPLE_DATA) SampleData.loadIfEmpty(db);
        TripGenerator.generateAll(db);

        ScheduledExecutorService jobs = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "jobs"); t.setDaemon(true); return t; });
        jobs.scheduleWithFixedDelay(() -> { try { app.bookings.sweep(); } catch (Exception e) { System.err.println("sweep: " + e.getMessage()); } }, 30, 30, TimeUnit.SECONDS);
        jobs.scheduleWithFixedDelay(() -> { try { TripGenerator.generateAll(db); } catch (Exception e) { System.err.println("trips: " + e.getMessage()); } }, 6, 6, TimeUnit.HOURS);

        HttpServer server = HttpServer.create(new InetSocketAddress(Config.PORT), 0);
        server.createContext("/api/", app::handleApi);
        server.createContext("/", app::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(24));
        server.start();
        System.out.println("First Choice is running.  Open  " + Config.BASE_URL + "  in your browser.");
        System.out.println(" Database : " + (Config.JDBC_URL.startsWith("jdbc:h2") ? "H2 file (data/ folder)" : "external SQL database"));
        System.out.println(" Payments : " + (razorpay ? "Razorpay (REAL payments)" : "DEMO gateway (no real money)"));
        System.out.println(" Email    : " + (smtp != null ? "SMTP via " + Config.SMTP_HOST : "LOG MODE (emails are printed here and shown in Admin > Outbox)"));
        System.out.println("Press Ctrl+C to stop.");
    }

    // =====================================================================
    //  request plumbing
    // =====================================================================
    private final class Req {
        final HttpExchange ex; final String method; final String[] p; final Map<String, String> q = new HashMap<>();
        private Map<String, Object> body; private Row user; private boolean userLoaded;
        Req(HttpExchange ex) {
            this.ex = ex; this.method = ex.getRequestMethod();
            this.p = ex.getRequestURI().getPath().substring(5).split("/");
            String raw = ex.getRequestURI().getRawQuery();
            if (raw != null) for (String pair : raw.split("&")) {
                int i = pair.indexOf('=');
                q.put(URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8), i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        String token() { String h = ex.getRequestHeaders().getFirst("Authorization"); return h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null; }
        String ip() {
            String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (Config.TRUST_PROXY && fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
            return ex.getRemoteAddress().getAddress().getHostAddress();
        }
        Map<String, Object> body() throws IOException {
            if (body == null) body = Json.parseObject(new String(ex.getRequestBody().readNBytes(100_000), StandardCharsets.UTF_8));
            return body;
        }
        String str(String k) throws IOException { Object v = body().get(k); return v == null ? "" : v.toString(); }
        Row user() { if (!userLoaded) { user = auth.userForToken(token()); userLoaded = true; } return user; }
        Row requireUser() { Row u = user(); if (u == null) throw ApiException.unauthorized("Please log in to continue."); return u; }
        Row requireAdmin() { Row u = requireUser(); if (!"ADMIN".equals(u.str("role"))) throw ApiException.forbidden("Admin access only."); return u; }
        void method(String m) { if (!method.equals(m)) throw new ApiException(405, "Method not allowed."); }
        LocalDate date(String k, LocalDate fallback) { String v = q.get(k); return v == null || v.isBlank() ? fallback : LocalDate.parse(v); }
    }

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            send(ex, 200, route(new Req(ex)));
        } catch (ApiException e) {
            send(ex, e.status, Map.of("error", e.getMessage()));
        } catch (DateTimeException e) {
            send(ex, 400, Map.of("error", "Invalid date. Use the format yyyy-MM-dd."));
        } catch (IllegalArgumentException | ClassCastException e) {
            send(ex, 400, Map.of("error", "Invalid input. Please check the values and try again."));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, Map.of("error", "Something went wrong on our side. Please try again."));
        }
    }

    private Object route(Req r) throws Exception {
        return switch (r.p[0]) {
            case "auth" -> authRoutes(r);
            case "me" -> meRoutes(r);
            case "config" -> config();
            case "cities" -> catalog.cities();
            case "search" -> search(r);
            case "trips" -> { r.method("GET"); yield catalog.tripDetail(r.p[1]); }
            case "offers" -> catalog.offers();
            case "coupons" -> couponCheck(r);
            case "bookings" -> bookingRoutes(r);
            case "track" -> track(r);
            case "admin" -> adminRoutes(r);
            default -> throw ApiException.notFound("Not found.");
        };
    }

    // =====================================================================
    //  public + account routes
    // =====================================================================
    private Object config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentProvider", bookings.gateway().name());
        m.put("holdMinutes", Config.HOLD_MINUTES);
        m.put("emailLogMode", notifier.isLogMode());
        return m;
    }

    private Object search(Req r) {
        r.method("GET");
        String from = r.q.getOrDefault("from", ""), to = r.q.getOrDefault("to", "");
        if (from.isBlank() || to.isBlank()) throw ApiException.bad("Enter both a starting city and a destination.");
        if (from.trim().equalsIgnoreCase(to.trim())) throw ApiException.bad("Starting city and destination must be different.");
        LocalDate date = LocalDate.parse(r.q.getOrDefault("date", ""));
        if (date.isBefore(LocalDate.now())) throw ApiException.bad("Pick a travel date from today onwards.");
        return catalog.search(from, to, date);
    }

    private Object authRoutes(Req r) throws Exception {
        String what = r.p.length > 1 ? r.p[1] : "";
        r.method("POST");
        switch (what) {
            case "register": return auth.register(r.str("name"), r.str("email"), r.str("phone"), r.str("password"), r.ip());
            case "login": return auth.login(r.str("email"), r.str("password"), r.ip());
            case "logout": auth.logout(r.token()); return Map.of("ok", true);
            case "forgot": auth.forgotPassword(r.str("email"), r.ip());
                return Map.of("ok", true, "message", "If an account exists for that email, we have sent a reset link. It is valid for 30 minutes.");
            case "reset": auth.resetPassword(r.str("token"), r.str("password")); return Map.of("ok", true);
            case "verify": auth.verifyEmail(r.str("token")); return Map.of("ok", true);
            case "resend-verification": auth.resendVerification(r.requireUser(), r.ip()); return Map.of("ok", true);
            default: throw ApiException.notFound("Not found.");
        }
    }

    private Object meRoutes(Req r) throws Exception {
        Row u = r.requireUser();
        if (r.p.length == 1 && r.method.equals("GET")) return AuthService.userMap(u);
        if (r.p.length == 1 && r.method.equals("PUT")) return auth.updateProfile(u.str("id"), r.str("name"), r.str("phone"));
        if (r.p.length == 2 && r.p[1].equals("password") && r.method.equals("POST")) {
            auth.changePassword(u, r.str("current"), r.str("next"), r.token());
            return Map.of("ok", true);
        }
        if (r.p.length == 2 && r.p[1].equals("logout-everywhere") && r.method.equals("POST")) { auth.logoutEverywhere(u.str("id")); return Map.of("ok", true); }
        throw ApiException.notFound("Not found.");
    }

    private Object couponCheck(Req r) throws Exception {
        r.method("POST");
        r.requireUser();
        Row trip = catalog.tripRow(r.str("tripId"));
        int n = Math.max(1, Math.min(6, (int) Double.parseDouble(r.str("seatCount").isEmpty() ? "1" : r.str("seatCount"))));
        long base = trip.lng("fare_paise") * n;
        long discount = catalog.couponDiscount(r.str("code"), base);
        return Map.of("code", r.str("code").trim().toUpperCase(), "discount", discount / 100.0, "total", (base - discount) / 100.0);
    }

    // =====================================================================
    //  bookings
    // =====================================================================
    private Object bookingRoutes(Req r) throws Exception {
        Row u = r.requireUser();
        if (r.p.length == 1) {
            if (r.method.equals("GET")) return bookings.mine(u);
            r.method("POST");
            List<BookingService.SeatReq> seats = new ArrayList<>();
            if (!(r.body().get("seats") instanceof List<?> list)) throw ApiException.bad("Select at least one seat.");
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) throw ApiException.bad("Invalid passenger details.");
                Object age = m.get("age");
                seats.add(new BookingService.SeatReq(str(m.get("label")), str(m.get("name")),
                        age == null ? 0 : (int) Double.parseDouble(age.toString()), str(m.get("gender"))));
            }
            return bookings.create(u, r.str("tripId"), seats, r.str("boarding"), r.str("dropping"), r.str("phone"), r.str("email"), r.str("coupon"));
        }
        String pnr = r.p[1];
        if (r.p.length == 2) { r.method("GET"); return bookings.viewFor(u, pnr); }
        switch (r.p[2]) {
            case "pay": r.method("POST"); return bookings.pay(u, pnr, r.body());
            case "abandon": r.method("POST"); bookings.abandon(u, pnr); return Map.of("ok", true);
            case "cancel": {
                r.method("POST");
                List<String> labels = new ArrayList<>();
                if (r.body().get("seats") instanceof List<?> l) for (Object o : l) labels.add(String.valueOf(o));
                return bookings.cancel(u, pnr, labels);
            }
            case "review": {
                r.method("POST");
                bookings.review(u, pnr, (int) Double.parseDouble(r.str("rating").isEmpty() ? "0" : r.str("rating")), r.str("text"));
                return Map.of("ok", true);
            }
            default: throw ApiException.notFound("Not found.");
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private Object track(Req r) throws Exception {
        r.method("POST");
        if (!limiter.allow("track:" + r.ip(), 20, 600)) throw new ApiException(429, "Too many lookups. Please wait a few minutes.");
        return bookings.track(r.str("pnr"), r.str("contact"));
    }

    // =====================================================================
    //  admin
    // =====================================================================
    private Object adminRoutes(Req r) throws Exception {
        r.requireAdmin();
        String what = r.p.length > 1 ? r.p[1] : "";
        LocalDate today = LocalDate.now();
        switch (what) {
            case "report": return admin.report(r.date("from", today.minusDays(29)), r.date("to", today));
            case "operators":
                if (r.method.equals("POST")) return Map.of("id", admin.addOperator(r.body()));
                return admin.operators();
            case "buses":
                if (r.method.equals("POST")) return Map.of("id", admin.addBus(r.body()));
                return admin.buses();
            case "services":
                if (r.p.length == 4 && r.p[3].equals("active")) { r.method("POST"); admin.setServiceActive(r.p[2], Boolean.parseBoolean(r.str("active"))); return Map.of("ok", true); }
                if (r.method.equals("POST")) return Map.of("id", admin.addService(r.body()));
                return admin.services();
            case "trips":
                if (r.p.length == 4 && r.p[3].equals("cancel")) { r.method("POST"); return Map.of("cancelledBookings", bookings.cancelTrip(r.p[2], r.str("reason"))); }
                if (r.p.length == 4 && r.p[3].equals("delay")) { r.method("POST"); admin.setDelay(r.p[2], (int) Double.parseDouble(r.str("minutes"))); return Map.of("ok", true); }
                return admin.trips(r.date("date", today));
            case "coupons":
                if (r.p.length == 4 && r.p[3].equals("active")) { r.method("POST"); admin.setCouponActive(r.p[2], Boolean.parseBoolean(r.str("active"))); return Map.of("ok", true); }
                if (r.method.equals("POST")) { admin.addCoupon(r.body()); return Map.of("ok", true); }
                return admin.coupons();
            case "bookings": return admin.bookings(r.q.get("pnr"), r.q.get("status"), r.date("from", null), r.date("to", null));
            case "users": return auth.listUsers();
            case "outbox": return admin.outbox();
            case "refunds": return admin.failedRefunds();
            case "generate-trips": r.method("POST"); return Map.of("created", TripGenerator.generateAll(db));
            default: throw ApiException.notFound("Not found.");
        }
    }

    // =====================================================================
    //  responses + static files
    // =====================================================================
    private static void headers(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "same-origin");
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self' https://checkout.razorpay.com; "
                + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; "
                + "img-src 'self' data: https://*.razorpay.com; frame-src https://api.razorpay.com https://checkout.razorpay.com; "
                + "connect-src 'self' https://*.razorpay.com");
    }

    private static void send(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        headers(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = webRoot.resolve(path.substring(1)).normalize();
        headers(ex);
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
            byte[] msg = "Page not found".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(404, msg.length);
            ex.getResponseBody().write(msg);
            ex.close();
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        String n = file.getFileName().toString();
        ex.getResponseHeaders().set("Content-Type", n.endsWith(".html") ? "text/html; charset=utf-8" : n.endsWith(".css") ? "text/css; charset=utf-8"
                : n.endsWith(".js") ? "text/javascript; charset=utf-8" : n.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
