package com.firstchoice.busreservation.service;

import com.firstchoice.busreservation.db.Db;
import com.firstchoice.busreservation.db.Row;
import com.firstchoice.busreservation.util.ApiException;
import com.firstchoice.busreservation.util.Security;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Everything the admin panel does: operators, buses, services, trips, coupons, reports. */
public final class AdminService {
    private final Db db;
    public AdminService(Db db) { this.db = db; }

    private static String req(Object v, String what, int max) {
        String s = v == null ? "" : v.toString().trim();
        if (s.isEmpty()) throw ApiException.bad(what + " is required.");
        if (s.length() > max) throw ApiException.bad(what + " is too long.");
        return s;
    }

    private static long paise(Object rupees, String what) {
        try {
            double d = rupees instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(rupees).trim());
            if (d < 0 || d > 1_000_000) throw new NumberFormatException();
            return Math.round(d * 100);
        } catch (NumberFormatException e) { throw ApiException.bad("Enter a valid amount for " + what + "."); }
    }

    // ---------- operators ----------
    public List<Row> operators() { return db.query("SELECT * FROM operators ORDER BY name"); }

    public String addOperator(Map<String, Object> b) {
        String id = Security.id();
        db.exec("INSERT INTO operators(id,name,phone,about,active) VALUES (?,?,?,?,?)", id, req(b.get("name"), "Operator name", 120),
                b.get("phone") == null ? null : b.get("phone").toString().trim(), b.get("about") == null ? null : b.get("about").toString().trim(), true);
        return id;
    }

    // ---------- buses ----------
    public List<Row> buses() {
        return db.query("SELECT b.*, o.name operator_name FROM buses b JOIN operators o ON o.id=b.operator_id ORDER BY o.name, b.name");
    }

    public String addBus(Map<String, Object> b) {
        String type = req(b.get("busType"), "Bus type", 10).toUpperCase(), layout = req(b.get("layout"), "Layout", 10).toUpperCase();
        if (!List.of("AC", "NON_AC").contains(type)) throw ApiException.bad("Bus type must be AC or NON_AC.");
        if (!List.of("SEATER", "SLEEPER").contains(layout)) throw ApiException.bad("Layout must be SEATER or SLEEPER.");
        int cap;
        try { cap = (int) Double.parseDouble(String.valueOf(b.get("capacity"))); } catch (NumberFormatException e) { throw ApiException.bad("Enter the number of seats."); }
        if (cap < 8 || cap > 60) throw ApiException.bad("Seats must be between 8 and 60.");
        String ladies = b.get("ladiesSeats") == null ? "" : b.get("ladiesSeats").toString().trim().toUpperCase();
        Set<String> valid = SeatLayout.build(layout, cap, "").labels();
        for (String l : ladies.split(",")) if (!l.isBlank() && !valid.contains(l.trim())) throw ApiException.bad("Ladies seat " + l.trim() + " does not exist in this layout.");
        if (db.one("SELECT id FROM operators WHERE id=?", String.valueOf(b.get("operatorId"))) == null) throw ApiException.bad("Choose an operator.");
        String id = Security.id();
        try {
            db.exec("INSERT INTO buses(id,operator_id,reg_number,name,bus_type,layout,capacity,amenities,ladies_seats,active) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id, b.get("operatorId"), req(b.get("regNumber"), "Registration number", 30).toUpperCase(), req(b.get("name"), "Bus name", 80), type, layout, cap,
                    b.get("amenities") == null ? "" : b.get("amenities").toString().trim(), ladies, true);
        } catch (RuntimeException e) {
            if (Db.isDuplicate(e)) throw ApiException.conflict("A bus with this registration number already exists.");
            throw e;
        }
        return id;
    }

    // ---------- services (recurring timetable) ----------
    public List<Map<String, Object>> services() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row s : db.query("SELECT s.*, b.name bus_name, o.name operator_name FROM services s JOIN buses b ON b.id=s.bus_id JOIN operators o ON o.id=s.operator_id ORDER BY s.origin, s.destination, s.depart_time")) {
            Map<String, Object> m = new LinkedHashMap<>(s);
            m.put("fare", s.lng("fare_paise") / 100.0);
            m.remove("fare_paise");
            out.add(m);
        }
        return out;
    }

    public String addService(Map<String, Object> b) {
        Row bus = db.one("SELECT * FROM buses WHERE id=?", String.valueOf(b.get("busId")));
        if (bus == null) throw ApiException.bad("Choose a bus.");
        String origin = req(b.get("origin"), "From city", 80), dest = req(b.get("destination"), "To city", 80);
        if (origin.equalsIgnoreCase(dest)) throw ApiException.bad("From and To cities must be different.");
        String time = req(b.get("departTime"), "Departure time", 5);
        try { LocalTime.parse(time); } catch (DateTimeParseException e) { throw ApiException.bad("Departure time must look like 07:30."); }
        int dur;
        try { dur = (int) Double.parseDouble(String.valueOf(b.get("durationMin"))); } catch (NumberFormatException e) { throw ApiException.bad("Enter the journey duration in minutes."); }
        if (dur < 15 || dur > 2000) throw ApiException.bad("Duration must be between 15 and 2000 minutes.");
        long fare = paise(b.get("fare"), "fare");
        if (fare < 100) throw ApiException.bad("Fare must be at least Rs. 1.");
        String days = b.get("runDays") == null ? "1111111" : b.get("runDays").toString();
        if (!days.matches("[01]{7}") || days.equals("0000000")) throw ApiException.bad("Choose at least one running day.");
        String id = Security.id();
        db.tx(c -> {
            Db.update(c, "INSERT INTO services(id,operator_id,bus_id,origin,destination,depart_time,duration_min,fare_paise,run_days,active) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id, bus.str("operator_id"), bus.str("id"), origin, dest, time, dur, fare, days, true);
            for (String kind : List.of("boardingPoints", "droppingPoints")) {
                Object list = b.get(kind);
                if (list instanceof List<?> l) for (Object o : l) {
                    if (!(o instanceof Map<?, ?> p)) continue;
                    String name = p.get("name") == null ? "" : p.get("name").toString().trim();
                    if (name.isEmpty()) continue;
                    int off = p.get("offset") == null ? 0 : (int) Double.parseDouble(p.get("offset").toString());
                    Db.update(c, "INSERT INTO service_points(id,service_id,point_type,point_name,offset_min) VALUES (?,?,?,?,?)",
                            Security.id(), id, kind.startsWith("boarding") ? "BOARDING" : "DROPPING", name, off);
                }
            }
            return null;
        });
        TripGenerator.generateFor(db, db.one("SELECT * FROM services WHERE id=?", id));
        return id;
    }

    public void setServiceActive(String id, boolean active) {
        if (db.exec("UPDATE services SET active=? WHERE id=?", active, id) == 0) throw ApiException.notFound("Service not found.");
        if (active) TripGenerator.generateFor(db, db.one("SELECT * FROM services WHERE id=?", id));
    }

    // ---------- trips ----------
    public List<Map<String, Object>> trips(LocalDate date) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row t : db.query("SELECT t.id, t.departure, t.status, t.delay_min, t.fare_paise, s.origin, s.destination, b.name bus_name, b.capacity, o.name operator_name, "
                + "(SELECT COUNT(*) FROM seat_locks l WHERE l.trip_id=t.id AND l.lock_state='BOOKED') sold, "
                + "(SELECT COUNT(*) FROM seat_locks l WHERE l.trip_id=t.id AND l.lock_state='HELD' AND l.expires_at > ?) held "
                + "FROM trips t JOIN services s ON s.id=t.service_id JOIN buses b ON b.id=t.bus_id JOIN operators o ON o.id=s.operator_id "
                + "WHERE t.travel_date=? ORDER BY t.departure", LocalDateTime.now(), date)) {
            Map<String, Object> m = new LinkedHashMap<>(t);
            m.put("departure", t.dt("departure").toString());
            m.put("fare", t.lng("fare_paise") / 100.0);
            m.remove("fare_paise");
            out.add(m);
        }
        return out;
    }

    public void setDelay(String tripId, int minutes) {
        if (minutes < 0 || minutes > 720) throw ApiException.bad("Delay must be between 0 and 720 minutes.");
        if (db.exec("UPDATE trips SET delay_min=? WHERE id=?", minutes, tripId) == 0) throw ApiException.notFound("Trip not found.");
    }

    // ---------- coupons ----------
    public List<Map<String, Object>> coupons() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row c : db.query("SELECT * FROM coupons ORDER BY code")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.str("code")); m.put("description", c.str("description")); m.put("kind", c.str("kind"));
            m.put("amount", "PERCENT".equals(c.str("kind")) ? c.lng("amount") : c.lng("amount") / 100.0);
            m.put("minAmount", c.lng("min_paise") / 100.0); m.put("maxDiscount", c.lng("max_discount_paise") / 100.0);
            m.put("validTo", c.date("valid_to") == null ? null : c.date("valid_to").toString());
            m.put("usageLimit", c.get("usage_limit")); m.put("used", c.integer("used_count")); m.put("active", c.bool("active"));
            out.add(m);
        }
        return out;
    }

    public void addCoupon(Map<String, Object> b) {
        String code = req(b.get("code"), "Coupon code", 30).toUpperCase();
        if (!code.matches("[A-Z0-9]{3,30}")) throw ApiException.bad("Coupon codes use letters and numbers only (3 to 30 characters).");
        String kind = req(b.get("kind"), "Type", 8).toUpperCase();
        if (!List.of("PERCENT", "FLAT").contains(kind)) throw ApiException.bad("Type must be PERCENT or FLAT.");
        long amount = "PERCENT".equals(kind) ? (long) Double.parseDouble(String.valueOf(b.get("amount"))) : paise(b.get("amount"), "discount");
        if (amount <= 0 || ("PERCENT".equals(kind) && amount > 90)) throw ApiException.bad("Percent discounts must be between 1 and 90.");
        LocalDate to = b.get("validTo") == null || b.get("validTo").toString().isBlank() ? null : LocalDate.parse(b.get("validTo").toString());
        Integer limit = b.get("usageLimit") == null || b.get("usageLimit").toString().isBlank() ? null : (int) Double.parseDouble(b.get("usageLimit").toString());
        try {
            db.exec("INSERT INTO coupons(code,description,kind,amount,min_paise,max_discount_paise,valid_from,valid_to,usage_limit,used_count,active) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    code, req(b.get("description"), "Description", 160), kind, amount, paise(b.get("minAmount") == null ? 0 : b.get("minAmount"), "minimum amount"),
                    paise(b.get("maxDiscount") == null ? 0 : b.get("maxDiscount"), "maximum discount"), null, to, limit, 0, true);
        } catch (RuntimeException e) {
            if (Db.isDuplicate(e)) throw ApiException.conflict("This coupon code already exists.");
            throw e;
        }
    }

    public void setCouponActive(String code, boolean active) {
        if (db.exec("UPDATE coupons SET active=? WHERE code=?", active, code) == 0) throw ApiException.notFound("Coupon not found.");
    }

    // ---------- bookings, outbox ----------
    public List<Map<String, Object>> bookings(String pnr, String status, LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("SELECT b.pnr, b.status, b.total_paise, b.refund_paise, b.created_at, b.contact_email, b.contact_phone, u.name user_name, "
                + "s.origin, s.destination, t.departure, (SELECT COUNT(*) FROM booking_seats x WHERE x.booking_pnr=b.pnr AND x.seat_status='ACTIVE') seats "
                + "FROM bookings b JOIN users u ON u.id=b.user_id JOIN trips t ON t.id=b.trip_id JOIN services s ON s.id=t.service_id WHERE b.status IN ('CONFIRMED','CANCELLED','FAILED') ");
        List<Object> p = new ArrayList<>();
        if (pnr != null && !pnr.isBlank()) { sql.append("AND b.pnr LIKE ? "); p.add("%" + pnr.trim().toUpperCase() + "%"); }
        if (status != null && !status.isBlank()) { sql.append("AND b.status=? "); p.add(status.toUpperCase()); }
        if (from != null) { sql.append("AND b.created_at >= ? "); p.add(from.atStartOfDay()); }
        if (to != null) { sql.append("AND b.created_at < ? "); p.add(to.plusDays(1).atStartOfDay()); }
        sql.append("ORDER BY b.created_at DESC LIMIT 200");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row r : db.query(sql.toString(), p.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pnr", r.str("pnr")); m.put("status", r.str("status")); m.put("user", r.str("user_name"));
            m.put("email", r.str("contact_email")); m.put("phone", r.str("contact_phone"));
            m.put("source", r.str("origin")); m.put("destination", r.str("destination")); m.put("departure", r.dt("departure").toString());
            m.put("seats", r.integer("seats")); m.put("total", r.lng("total_paise") / 100.0); m.put("refunded", r.lng("refund_paise") / 100.0);
            m.put("createdAt", r.dt("created_at").toString());
            out.add(m);
        }
        return out;
    }

    public List<Row> outbox() { return db.query("SELECT * FROM outbox ORDER BY created_at DESC LIMIT 100"); }

    public List<Row> failedRefunds() {
        return db.query("SELECT * FROM refunds WHERE refund_status IN ('FAILED','MANUAL') ORDER BY created_at DESC LIMIT 100");
    }

    // ---------- reports ----------
    public Map<String, Object> report(LocalDate from, LocalDate to) {
        List<Row> rows = db.query("SELECT b.total_paise, b.refund_paise, s.origin, s.destination, "
                + "(SELECT COUNT(*) FROM booking_seats x WHERE x.booking_pnr=b.pnr) seats "
                + "FROM bookings b JOIN trips t ON t.id=b.trip_id JOIN services s ON s.id=t.service_id "
                + "WHERE b.confirmed_at >= ? AND b.confirmed_at < ?", from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        long gross = 0, refunded = 0, seats = 0;
        Map<String, long[]> byRoute = new TreeMap<>();
        for (Row r : rows) {
            gross += r.lng("total_paise"); refunded += r.lng("refund_paise"); seats += r.lng("seats");
            long[] a = byRoute.computeIfAbsent(r.str("origin") + " to " + r.str("destination"), k -> new long[4]);
            a[0]++; a[1] += r.lng("seats"); a[2] += r.lng("total_paise"); a[3] += r.lng("refund_paise");
        }
        List<Object> routes = new ArrayList<>();
        byRoute.forEach((k, a) -> routes.add(Map.of("route", k, "bookings", a[0], "seats", a[1], "gross", a[2] / 100.0, "refunded", a[3] / 100.0, "net", (a[2] - a[3]) / 100.0)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from.toString()); m.put("to", to.toString());
        m.put("bookings", rows.size()); m.put("seats", seats); m.put("gross", gross / 100.0);
        m.put("refunded", refunded / 100.0); m.put("net", (gross - refunded) / 100.0);
        m.put("routes", routes);
        m.put("users", db.count("SELECT COUNT(*) n FROM users"));
        return m;
    }
}
