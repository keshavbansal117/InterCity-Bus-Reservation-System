package com.firstchoice.busreservation.web;

import com.firstchoice.busreservation.dao.*;
import com.firstchoice.busreservation.exception.BookingException;
import com.firstchoice.busreservation.model.*;
import com.firstchoice.busreservation.service.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Web front end for First Choice. Serves the static site from ./web and a JSON API under /api.
 * Uses only the JDK's built-in HTTP server - no libraries needed.
 *
 * Environment variables (all optional):  PORT (default 8080), WEB_DIR (default "web"), ADMIN_PASSWORD.
 */
public class WebApp {
    private static final class ApiException extends Exception {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }

    private final AuthService auth;
    private final AdminService admin;
    private final BookingService booking;
    private final Map<String, User> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Path webRoot;

    public WebApp(Path webRoot) throws Exception {
        this.webRoot = webRoot;
        UserDAO userDAO = new InMemoryUserDAO();
        ScheduleDAO scheduleDAO = new InMemoryScheduleDAO();
        BookingDAO bookingDAO = new InMemoryBookingDAO();
        auth = new AuthService(userDAO);
        admin = new AdminService(scheduleDAO);
        booking = new BookingService(scheduleDAO, bookingDAO);
        String adminPw = System.getenv().getOrDefault("ADMIN_PASSWORD", "admin123");
        DataSeeder.seed(auth, admin, adminPw);
    }

    public HttpServer startServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path web = Path.of(System.getenv().getOrDefault("WEB_DIR", "web")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(web.resolve("index.html"))) {
            System.err.println("Cannot find " + web.resolve("index.html") + " - run this from the project folder.");
            System.exit(1);
        }
        WebApp app = new WebApp(web);
        HttpServer server = app.startServer(port);
        System.out.println("First Choice is running.  Open  http://localhost:" + port + "  in your browser.");
        System.out.println("Press Ctrl+C to stop.");
    }

    // =====================================================================
    //  API
    // =====================================================================
    private void handleApi(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }

        try {
            String rawPath = ex.getRequestURI().getPath();
            if (rawPath.equals("/api") || rawPath.equals("/api/")) {
                throw new ApiException(404, "Not found.");
            }
            String sub = rawPath.startsWith("/api/") ? rawPath.substring(5) : rawPath.substring(4);
            String[] p = Arrays.stream(sub.split("/")).filter(s -> !s.isBlank()).toArray(String[]::new);
            if (p.length == 0) throw new ApiException(404, "Not found.");

            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            send(ex, 200, route(ex, ex.getRequestMethod(), p, q));
        } catch (ApiException e) {
            send(ex, e.status, map("error", e.getMessage()));
        } catch (BookingException e) {
            send(ex, 400, map("error", e.getMessage()));
        } catch (DateTimeException e) {
            send(ex, 400, map("error", "Invalid date or time. Use the format yyyy-MM-dd."));
        } catch (IllegalArgumentException | ClassCastException e) {
            send(ex, 400, map("error", "Invalid input. Please check the values and try again."));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, map("error", "Server error. Please try again."));
        }
    }

    private Object route(HttpExchange ex, String method, String[] p, Map<String, String> q) throws Exception {
        return switch (p[0]) {
            case "register" -> register(ex, method);
            case "login" -> login(ex, method);
            case "logout" -> logout(ex, method);
            case "me" -> me(ex, method);
            case "cities" -> cities(method);
            case "search" -> search(method, q);
            case "schedules" -> schedule(method, p);
            case "bookings" -> bookings(ex, method, p);
            case "admin" -> adminApi(ex, method, p);
            default -> throw new ApiException(404, "Not found.");
        };
    }

    private Object register(HttpExchange ex, String method) throws Exception {
        need(method, "POST");
        Map<String, Object> b = body(ex);
        User u = auth.register(str(b, "name"), str(b, "email"), str(b, "password"), Role.PASSENGER);
        return newSession(u);
    }

    private Object login(HttpExchange ex, String method) throws Exception {
        need(method, "POST");
        Map<String, Object> b = body(ex);
        return newSession(auth.login(str(b, "email"), str(b, "password")));
    }

    private Object logout(HttpExchange ex, String method) throws Exception {
        need(method, "POST");
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            sessions.remove(h.substring(7).trim());
        }
        return map("success", true);
    }

    private Object me(HttpExchange ex, String method) throws Exception {
        need(method, "GET");
        User u = requireUser(ex);
        return map("name", u.name(), "email", u.email(), "role", u.role());
    }

    private Object cities(String method) throws Exception {
        need(method, "GET");
        TreeSet<String> set = new TreeSet<>();
        for (Schedule s : booking.allSchedules()) { set.add(s.source()); set.add(s.destination()); }
        return set;
    }

    private Object search(String method, Map<String, String> q) throws Exception {
        need(method, "GET");
        String from = q.getOrDefault("from", ""), to = q.getOrDefault("to", "");
        if (from.isBlank() || to.isBlank()) throw new IllegalArgumentException("from/to required");
        LocalDate date = LocalDate.parse(q.getOrDefault("date", ""));
        List<Object> out = new ArrayList<>();
        for (Schedule s : booking.search(from, to, date)) out.add(scheduleMap(s));
        return out;
    }

    private Object schedule(String method, String[] p) throws Exception {
        need(method, "GET");
        if (p.length < 2) throw new ApiException(404, "Not found.");
        Schedule s = booking.getSchedule(Integer.parseInt(p[1]));
        Map<String, Object> m = scheduleMap(s);
        m.put("bookedSeats", new ArrayList<>(booking.bookedSeats(s.id())));
        return m;
    }

    private Object bookings(HttpExchange ex, String method, String[] p) throws Exception {
        User user = requireUser(ex);
        if (p.length == 1 && method.equals("GET")) {
            List<Booking> mine = new ArrayList<>(booking.historyOf(user));
            mine.sort(Comparator.comparing((Booking b) -> b.getSchedule().departure()).reversed());
            List<Object> out = new ArrayList<>();
            mine.forEach(b -> out.add(bookingMap(b)));
            return out;
        }
        if (p.length == 1 && method.equals("POST")) {
            Map<String, Object> b = body(ex);
            if (!(b.get("passengers") instanceof List<?> raw) || raw.isEmpty())
                throw new BookingException("Select at least one seat.");
            List<PassengerSeat> passengers = new ArrayList<>();
            for (Object o : raw) {
                if (!(o instanceof Map<?, ?> pm)) throw new IllegalArgumentException("passenger");
                String g = String.valueOf(pm.get("gender") == null ? "" : pm.get("gender")).trim();
                passengers.add(new PassengerSeat(
                        (int) numOf(pm.get("seatNo")),
                        String.valueOf(pm.get("name") == null ? "" : pm.get("name")).trim(),
                        (int) numOf(pm.get("age")),
                        g.isEmpty() ? 'O' : Character.toUpperCase(g.charAt(0))));
            }
            return bookingMap(booking.book(user, (int) num(b, "scheduleId"), passengers));
        }
        if (p.length == 2 && method.equals("GET")) {
            return bookingMap(findBooking(user, p[1]));
        }
        if (p.length == 3 && p[2].equals("cancel") && method.equals("POST")) {
            return bookingMap(booking.cancel(user, p[1]));
        }
        throw new ApiException(404, "Not found.");
    }

    private Object adminApi(HttpExchange ex, String method, String[] p) throws Exception {
        User user = requireUser(ex);
        if (user.role() != Role.ADMIN) throw new ApiException(403, "Admin access only.");
        String what = p.length > 1 ? p[1] : "";
        switch (what) {
            case "schedules" -> {
                if (method.equals("POST")) {
                    Map<String, Object> b = body(ex);
                    Bus bus = admin.newBus(str(b, "busNumber"), str(b, "busName"),
                            BusType.valueOf(str(b, "busType").toUpperCase()), (int) num(b, "totalSeats"));
                    Schedule s = admin.addSchedule(bus, str(b, "from"), str(b, "to"),
                            LocalDateTime.parse(str(b, "departure")), LocalDateTime.parse(str(b, "arrival")), num(b, "fare"));
                    return scheduleMap(s);
                }
                need(method, "GET");
                List<Object> out = new ArrayList<>();
                booking.allSchedules().forEach(s -> out.add(scheduleMap(s)));
                return out;
            }
            case "bookings" -> {
                need(method, "GET");
                List<Object> out = new ArrayList<>();
                booking.allBookings().forEach(b -> out.add(bookingMap(b)));
                return out;
            }
            case "report" -> {
                need(method, "GET");
                List<Object> rows = new ArrayList<>();
                double total = 0;
                for (Schedule s : booking.allSchedules()) {
                    int sold = booking.bookedSeats(s.id()).size();
                    double revenue = sold * s.fare();
                    total += revenue;
                    Map<String, Object> m = scheduleMap(s);
                    m.put("sold", sold);
                    m.put("revenue", revenue);
                    rows.add(m);
                }
                return map("rows", rows, "totalRevenue", total);
            }
            default -> throw new ApiException(404, "Not found.");
        }
    }

    // ---------- JSON shapes ----------
    private Map<String, Object> scheduleMap(Schedule s) {
        int free = s.bus().totalSeats() - booking.bookedSeats(s.id()).size();
        return map("id", s.id(), "busName", s.bus().name(), "busNumber", s.bus().number(),
                "busType", s.bus().type(), "source", s.source(), "destination", s.destination(),
                "departure", s.departure().toString(), "arrival", s.arrival().toString(),
                "fare", s.fare(), "totalSeats", s.bus().totalSeats(), "freeSeats", free);
    }

    private Map<String, Object> bookingMap(Booking b) {
        List<Object> seats = new ArrayList<>();
        for (PassengerSeat ps : b.getSeats())
            seats.add(map("seatNo", ps.seatNo(), "name", ps.name(), "age", ps.age(), "gender", String.valueOf(ps.gender())));

        Duration left = Duration.between(LocalDateTime.now(), b.getSchedule().departure());
        boolean canCancel = b.getStatus() == BookingStatus.CONFIRMED && left.compareTo(Duration.ZERO) > 0;
        double refundPct = canCancel ? BookingService.refundPercent(left) : 0;
        double refundNow = Math.round(b.getTotalAmount() * refundPct * 100.0) / 100.0;

        Map<String, Object> m = map("pnr", b.getPnr(), "status", b.getStatus(), "totalAmount", b.getTotalAmount(),
                "refundAmount", b.getRefundAmount(), "bookedAt", b.getBookedAt().toString(),
                "canCancel", canCancel, "refundIfCancelledNow", refundNow, "refundPercentNow", Math.round(refundPct * 100));
        m.put("schedule", scheduleMap(b.getSchedule()));
        m.put("seats", seats);
        return m;
    }

    private Booking findBooking(User user, String pnr) throws ApiException {
        List<Booking> pool = user.role() == Role.ADMIN ? booking.allBookings() : booking.historyOf(user);
        return pool.stream().filter(b -> b.getPnr().equalsIgnoreCase(pnr)).findFirst()
                .orElseThrow(() -> new ApiException(404, "No booking found for that PNR."));
    }

    // ---------- sessions & helpers ----------
    private Map<String, Object> newSession(User u) {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        sessions.put(token, u);
        return map("token", token, "name", u.name(), "email", u.email(), "role", u.role());
    }

    private User requireUser(HttpExchange ex) throws ApiException {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        User u = (h != null && h.startsWith("Bearer ")) ? sessions.get(h.substring(7).trim()) : null;
        if (u == null) throw new ApiException(401, "Please log in to continue.");
        return u;
    }

    private static void need(String actual, String expected) throws ApiException {
        if (!actual.equals(expected)) throw new ApiException(405, "Method not allowed.");
    }

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        try (var is = ex.getRequestBody()) {
            byte[] raw = is.readNBytes(100_000);
            return Json.parseObject(new String(raw, StandardCharsets.UTF_8));
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static double numOf(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) return Double.parseDouble(s.trim());
        throw new IllegalArgumentException("number expected");
    }

    private static double num(Map<String, Object> m, String key) { return numOf(m.get(key)); }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null || raw.isEmpty()) return q;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            q.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return q;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static void send(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    // =====================================================================
    //  Static files
    // =====================================================================
    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = webRoot.resolve(path.substring(1)).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
            byte[] msg = "Page not found".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("X-Frame-Options", "DENY");
            ex.sendResponseHeaders(404, msg.length);
            if (!ex.getRequestMethod().equalsIgnoreCase("HEAD")) {
                ex.getResponseBody().write(msg);
            }
            ex.close();
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(file.getFileName().toString()));
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        ex.sendResponseHeaders(200, bytes.length);
        if (!ex.getRequestMethod().equalsIgnoreCase("HEAD")) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }
}
