package com.firstchoice.busreservation.service;

import com.firstchoice.busreservation.db.Db;
import com.firstchoice.busreservation.db.Row;
import com.firstchoice.busreservation.util.ApiException;
import com.firstchoice.busreservation.util.Config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Read side of the system: cities, search, trip details with live seat availability, offers. */
public final class CatalogService {
    private final Db db;
    public CatalogService(Db db) { this.db = db; }

    public static double rupees(long paise) { return paise / 100.0; }

    private static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        if (s != null) for (String x : s.split(",")) if (!x.isBlank()) out.add(x.trim());
        return out;
    }

    public List<String> cities() {
        TreeSet<String> set = new TreeSet<>();
        for (Row r : db.query("SELECT origin, destination FROM services WHERE active=?", true)) { set.add(r.str("origin")); set.add(r.str("destination")); }
        return new ArrayList<>(set);
    }

    private static final String TRIP_SELECT =
            "SELECT t.id trip_id, t.departure, t.arrival, t.fare_paise, t.delay_min, t.status trip_status, t.service_id, "
          + "s.origin, s.destination, s.operator_id, b.name bus_name, b.bus_type, b.layout, b.capacity, b.amenities, b.ladies_seats, b.reg_number, "
          + "o.name operator_name, o.phone operator_phone, "
          + "(SELECT COUNT(*) FROM seat_locks l WHERE l.trip_id=t.id AND (l.lock_state='BOOKED' OR l.expires_at > ?)) used, "
          + "(SELECT AVG(CAST(r.rating AS DOUBLE PRECISION)) FROM reviews r WHERE r.operator_id=o.id) rating, "
          + "(SELECT COUNT(*) FROM reviews r WHERE r.operator_id=o.id) rating_count "
          + "FROM trips t JOIN services s ON s.id=t.service_id JOIN buses b ON b.id=t.bus_id JOIN operators o ON o.id=s.operator_id ";

    private Map<String, Object> tripMap(Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.str("trip_id"));
        m.put("source", r.str("origin")); m.put("destination", r.str("destination"));
        m.put("departure", r.dt("departure").toString()); m.put("arrival", r.dt("arrival").toString());
        m.put("fare", rupees(r.lng("fare_paise")));
        m.put("delayMin", r.integer("delay_min")); m.put("status", r.str("trip_status"));
        m.put("operatorId", r.str("operator_id")); m.put("operatorName", r.str("operator_name"));
        m.put("rating", r.get("rating") == null ? null : Math.round(r.dbl("rating") * 10) / 10.0);
        m.put("ratingCount", r.integer("rating_count"));
        m.put("busName", r.str("bus_name")); m.put("busType", r.str("bus_type")); m.put("layout", r.str("layout"));
        m.put("busNumber", r.str("reg_number")); m.put("amenities", csv(r.str("amenities")));
        m.put("totalSeats", r.integer("capacity")); m.put("freeSeats", Math.max(0, r.integer("capacity") - r.integer("used")));
        return m;
    }

    public List<Map<String, Object>> search(String from, String to, LocalDate date) {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row r : db.query(TRIP_SELECT + "WHERE LOWER(s.origin)=LOWER(?) AND LOWER(s.destination)=LOWER(?) AND t.travel_date=? "
                + "AND t.status='SCHEDULED' AND t.departure > ? ORDER BY t.departure",
                now, from.trim(), to.trim(), date, now.plusMinutes(Config.BOOKING_CLOSE_MIN))) out.add(tripMap(r));
        return out;
    }

    public Row tripRow(String tripId) {
        Row r = db.one(TRIP_SELECT + "WHERE t.id=?", LocalDateTime.now(), tripId);
        if (r == null) throw ApiException.notFound("This trip does not exist.");
        return r;
    }

    public List<Row> points(String serviceId) {
        return db.query("SELECT point_type, point_name, offset_min FROM service_points WHERE service_id=? ORDER BY offset_min", serviceId);
    }

    public Map<String, Object> tripDetail(String tripId) {
        Row t = tripRow(tripId);
        Map<String, Object> m = tripMap(t);
        m.put("operatorPhone", t.str("operator_phone"));
        m.put("holdMinutes", Config.HOLD_MINUTES);
        m.put("bookingOpen", "SCHEDULED".equals(t.str("trip_status")) && t.dt("departure").isAfter(LocalDateTime.now().plusMinutes(Config.BOOKING_CLOSE_MIN)));

        Set<String> taken = new HashSet<>();
        for (Row l : db.query("SELECT seat_label FROM seat_locks WHERE trip_id=? AND (lock_state='BOOKED' OR expires_at > ?)", tripId, LocalDateTime.now()))
            taken.add(l.str("seat_label"));
        SeatLayout layout = SeatLayout.build(t.str("layout"), t.integer("capacity"), t.str("ladies_seats"));
        List<Object> seats = new ArrayList<>();
        for (SeatLayout.Seat s : layout.seats) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("label", s.label()); sm.put("deck", s.deck()); sm.put("row", s.row()); sm.put("col", s.col());
            sm.put("ladies", s.ladies()); sm.put("available", !taken.contains(s.label()));
            seats.add(sm);
        }
        m.put("seatPlan", Map.of("type", layout.type, "cols", layout.cols, "seats", seats));

        List<Object> boarding = new ArrayList<>(), dropping = new ArrayList<>();
        for (Row p : points(t.str("service_id"))) {
            boolean board = "BOARDING".equals(p.str("point_type"));
            LocalDateTime when = (board ? t.dt("departure") : t.dt("arrival")).plusMinutes(p.integer("offset_min"));
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.str("point_name")); pm.put("time", when.toString());
            (board ? boarding : dropping).add(pm);
        }
        m.put("boardingPoints", boarding); m.put("droppingPoints", dropping);

        List<Object> reviews = new ArrayList<>();
        for (Row r : db.query("SELECT r.rating, r.review_text, r.created_at, u.name FROM reviews r JOIN users u ON u.id=r.user_id WHERE r.operator_id=? ORDER BY r.created_at DESC LIMIT 5", t.str("operator_id"))) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("rating", r.integer("rating")); rm.put("text", r.str("review_text"));
            rm.put("name", r.str("name").split(" ")[0]); rm.put("date", r.dt("created_at").toLocalDate().toString());
            reviews.add(rm);
        }
        m.put("reviews", reviews);
        return m;
    }

    // ---------- offers / coupons ----------
    public List<Map<String, Object>> offers() {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row c : db.query("SELECT * FROM coupons WHERE active=? ORDER BY code", true)) {
            if (!couponUsable(c, today)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.str("code")); m.put("description", c.str("description"));
            m.put("validTo", c.date("valid_to") == null ? null : c.date("valid_to").toString());
            out.add(m);
        }
        return out;
    }

    private static boolean couponUsable(Row c, LocalDate today) {
        if (!c.bool("active")) return false;
        if (c.date("valid_from") != null && today.isBefore(c.date("valid_from"))) return false;
        if (c.date("valid_to") != null && today.isAfter(c.date("valid_to"))) return false;
        return c.get("usage_limit") == null || c.integer("used_count") < c.integer("usage_limit");
    }

    /** Discount in paise for a coupon code on a base amount; throws a friendly error if it cannot be used. */
    public long couponDiscount(String code, long basePaise) {
        Row c = db.one("SELECT * FROM coupons WHERE code=?", code.trim().toUpperCase());
        if (c == null || !couponUsable(c, LocalDate.now())) throw ApiException.bad("This coupon code is not valid or has expired.");
        if (basePaise < c.lng("min_paise")) throw ApiException.bad("This coupon needs a minimum booking of ₹" + (long) rupees(c.lng("min_paise")) + ".");
        long d = "PERCENT".equals(c.str("kind")) ? basePaise * c.lng("amount") / 100 : c.lng("amount");
        if (c.lng("max_discount_paise") > 0) d = Math.min(d, c.lng("max_discount_paise"));
        return Math.max(0, Math.min(d, basePaise - 100));
    }
}
