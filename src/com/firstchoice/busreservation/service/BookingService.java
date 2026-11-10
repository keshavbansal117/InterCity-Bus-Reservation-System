package com.firstchoice.busreservation.service;

import com.firstchoice.busreservation.db.Db;
import com.firstchoice.busreservation.db.Row;
import com.firstchoice.busreservation.mail.Notifier;
import com.firstchoice.busreservation.pay.PaymentGateway;
import com.firstchoice.busreservation.util.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The booking engine.
 * Flow: create (seats are HELD for a few minutes) -> pay (seats become BOOKED) -> optional cancel / partial cancel.
 * Seats are protected by the primary key of seat_locks(trip_id, seat_label): the database itself refuses a second
 * lock on the same seat, so two people can never get the same seat, even with many simultaneous requests.
 */
public final class BookingService {
    public record SeatReq(String label, String name, int age, String gender) { }

    private static final class SeatLost extends RuntimeException { }
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' hh:mm a");

    private final Db db;
    private final Notifier mail;
    private final PaymentGateway gateway;
    private final CatalogService catalog;

    public BookingService(Db db, Notifier mail, PaymentGateway gateway, CatalogService catalog) {
        this.db = db; this.mail = mail; this.gateway = gateway; this.catalog = catalog;
    }

    public PaymentGateway gateway() { return gateway; }

    /** Cancellation policy: more than 24h before departure = 90% back, more than 6h = 50%, otherwise nothing. */
    public static int refundPercent(Duration timeLeft) {
        if (timeLeft.compareTo(Duration.ofHours(24)) > 0) return 90;
        if (timeLeft.compareTo(Duration.ofHours(6)) > 0) return 50;
        return 0;
    }

    /** Frees seats of unpaid bookings whose hold time ran out. Called on every booking action and once a minute. */
    public void sweep() {
        db.tx(c -> { releaseExpired(c, LocalDateTime.now()); return null; });
    }

    private static void releaseExpired(java.sql.Connection c, LocalDateTime now) throws Exception {
        Db.update(c, "UPDATE bookings SET status='EXPIRED' WHERE status='PENDING_PAYMENT' AND expires_at <= ?", now);
        Db.update(c, "DELETE FROM seat_locks WHERE lock_state='HELD' AND expires_at <= ?", now);
    }

    // =====================================================================
    //  1. create: validate, hold seats, open a payment order
    // =====================================================================
    public Map<String, Object> create(Row user, String tripId, List<SeatReq> seats, String boarding, String dropping,
                                      String phone, String email, String coupon) {
        if (seats == null || seats.isEmpty()) throw ApiException.bad("Select at least one seat.");
        if (seats.size() > 6) throw ApiException.bad("You can book at most 6 seats at a time.");
        Row trip = catalog.tripRow(tripId);
        LocalDateTime now = LocalDateTime.now();
        if (!"SCHEDULED".equals(trip.str("trip_status"))) throw ApiException.conflict("This trip has been cancelled.");
        if (!trip.dt("departure").isAfter(now.plusMinutes(Config.BOOKING_CLOSE_MIN))) throw ApiException.conflict("Booking for this bus has closed.");

        SeatLayout layout = SeatLayout.build(trip.str("layout"), trip.integer("capacity"), trip.str("ladies_seats"));
        Map<String, SeatLayout.Seat> byLabel = new HashMap<>();
        for (SeatLayout.Seat s : layout.seats) byLabel.put(s.label(), s);
        List<SeatReq> clean = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SeatReq r : seats) {
            String label = r.label() == null ? "" : r.label().trim().toUpperCase();
            SeatLayout.Seat seat = byLabel.get(label);
            if (seat == null) throw ApiException.bad("Seat " + label + " does not exist on this bus.");
            if (!seen.add(label)) throw ApiException.bad("Seat " + label + " was selected twice.");
            String name = r.name() == null ? "" : r.name().trim();
            if (name.length() < 2 || name.length() > 100) throw ApiException.bad("Enter the passenger name for seat " + label + ".");
            if (r.age() < 1 || r.age() > 120) throw ApiException.bad("Enter a valid age for seat " + label + ".");
            String g = r.gender() == null ? "" : r.gender().trim().toUpperCase();
            if (!g.matches("[MFO]")) throw ApiException.bad("Choose a gender for seat " + label + ".");
            if (seat.ladies() && !g.equals("F")) throw ApiException.bad("Seat " + label + " is reserved for women passengers.");
            clean.add(new SeatReq(label, name, r.age(), g));
        }

        List<Row> points = catalog.points(trip.str("service_id"));
        if (points.stream().anyMatch(p -> "BOARDING".equals(p.str("point_type"))) && points.stream().noneMatch(p -> "BOARDING".equals(p.str("point_type")) && p.str("point_name").equals(boarding)))
            throw ApiException.bad("Choose a boarding point.");
        if (points.stream().anyMatch(p -> "DROPPING".equals(p.str("point_type"))) && points.stream().noneMatch(p -> "DROPPING".equals(p.str("point_type")) && p.str("point_name").equals(dropping)))
            throw ApiException.bad("Choose a dropping point.");

        String contactPhone = AuthService.normPhone(phone);
        String contactEmail = (email == null || email.isBlank()) ? user.str("email") : AuthService.normEmail(email);

        long fare = trip.lng("fare_paise"), base = fare * clean.size();
        long discount = (coupon == null || coupon.isBlank()) ? 0 : catalog.couponDiscount(coupon, base);
        long total = base - discount;
        String couponCode = discount > 0 ? coupon.trim().toUpperCase() : null;
        LocalDateTime holdUntil = now.plusMinutes(Config.HOLD_MINUTES);

        String pnr = db.tx(c -> {
            releaseExpired(c, now);
            List<String> taken = new ArrayList<>();
            for (SeatReq r : clean)
                if (Db.one(c, "SELECT seat_label FROM seat_locks WHERE trip_id=? AND seat_label=?", tripId, r.label()) != null) taken.add(r.label());
            if (!taken.isEmpty()) throw ApiException.conflict("Seat " + String.join(", ", taken) + " was just taken by someone else. Please choose different seats.");

            String p;
            do { p = Security.pnr(); } while (Db.one(c, "SELECT pnr FROM bookings WHERE pnr=?", p) != null);
            Db.update(c, "INSERT INTO bookings(pnr,user_id,trip_id,status,boarding,dropping,contact_email,contact_phone,base_paise,discount_paise,total_paise,refund_paise,coupon_code,created_at,expires_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", p, user.str("id"), tripId, "PENDING_PAYMENT", boarding, dropping,
                    contactEmail, contactPhone, base, discount, total, 0L, couponCode, now, holdUntil);
            for (SeatReq r : clean) {
                try {
                    Db.update(c, "INSERT INTO seat_locks(trip_id,seat_label,booking_pnr,lock_state,expires_at) VALUES (?,?,?,?,?)", tripId, r.label(), p, "HELD", holdUntil);
                } catch (Exception e) {
                    if (Db.isDuplicate(e)) throw ApiException.conflict("Seat " + r.label() + " was just taken by someone else. Please choose different seats.");
                    throw e;
                }
                Db.update(c, "INSERT INTO booking_seats(booking_pnr,seat_label,passenger_name,age,gender,fare_paise,seat_status,refund_paise) VALUES (?,?,?,?,?,?,?,?)",
                        p, r.label(), r.name(), r.age(), r.gender(), fare, "ACTIVE", 0L);
            }
            return p;
        });

        Map<String, Object> order;
        try {
            order = gateway.createOrder(total, pnr);
        } catch (Exception e) {
            System.err.println("Payment order failed: " + e.getMessage());
            release(pnr);
            throw new ApiException(502, "The payment service is not reachable right now. You have not been charged. Please try again in a moment.");
        }
        db.exec("INSERT INTO payments(id,booking_pnr,provider,provider_order_id,provider_payment_id,amount_paise,pay_status,created_at) VALUES (?,?,?,?,?,?,?,?)",
                Security.id(), pnr, gateway.name(), String.valueOf(order.get("orderId")), null, total, "CREATED", now);
        return view(pnr);
    }

    private void release(String pnr) {
        db.tx(c -> {
            Db.update(c, "UPDATE bookings SET status='EXPIRED' WHERE pnr=? AND status='PENDING_PAYMENT'", pnr);
            Db.update(c, "DELETE FROM seat_locks WHERE booking_pnr=? AND lock_state='HELD'", pnr);
            return null;
        });
    }

    /** The passenger left the checkout page: free the seats right away. */
    public void abandon(Row user, String pnr) { owned(user, pnr); release(pnr); }

    // =====================================================================
    //  2. pay: verify the payment, then turn the hold into a booking
    // =====================================================================
    public Map<String, Object> pay(Row user, String pnr, Map<String, Object> payload) {
        Row b = owned(user, pnr);
        if ("CONFIRMED".equals(b.str("status"))) return view(pnr);
        if (!"PENDING_PAYMENT".equals(b.str("status")))
            throw ApiException.conflict("This booking is no longer waiting for payment (the seat hold may have expired). Please search again.");
        LocalDateTime now = LocalDateTime.now();
        if (!b.dt("expires_at").isAfter(now)) { sweep(); throw new ApiException(410, "Your seat hold expired. Please select your seats again."); }

        Row pay = db.one("SELECT * FROM payments WHERE booking_pnr=? AND pay_status='CREATED' ORDER BY created_at DESC LIMIT 1", pnr);
        if (pay == null) throw ApiException.conflict("There is no open payment for this booking.");

        String providerPaymentId;
        try {
            providerPaymentId = gateway.verify(pay.str("provider_order_id"), payload);
        } catch (Exception e) {
            throw new ApiException(402, "Payment was not completed: " + e.getMessage() + " Your seats are still held, so you can try again.");
        }

        try {
            db.tx(c -> {
                if (Db.update(c, "UPDATE payments SET pay_status='PAID', provider_payment_id=? WHERE id=? AND pay_status='CREATED'", providerPaymentId, pay.str("id")) == 0)
                    throw ApiException.conflict("This payment was already processed.");
                for (Row s : Db.query(c, "SELECT seat_label FROM booking_seats WHERE booking_pnr=? AND seat_status='ACTIVE'", pnr)) {
                    Row lock = Db.one(c, "SELECT booking_pnr FROM seat_locks WHERE trip_id=? AND seat_label=?", b.str("trip_id"), s.str("seat_label"));
                    if (lock == null) Db.update(c, "INSERT INTO seat_locks(trip_id,seat_label,booking_pnr,lock_state,expires_at) VALUES (?,?,?,?,?)", b.str("trip_id"), s.str("seat_label"), pnr, "BOOKED", null);
                    else if (!pnr.equals(lock.str("booking_pnr"))) throw new SeatLost();
                }
                Db.update(c, "UPDATE seat_locks SET lock_state='BOOKED', expires_at=NULL WHERE booking_pnr=?", pnr);
                Db.update(c, "UPDATE bookings SET status='CONFIRMED', confirmed_at=?, expires_at=NULL WHERE pnr=?", now, pnr);
                if (b.str("coupon_code") != null) Db.update(c, "UPDATE coupons SET used_count=used_count+1 WHERE code=?", b.str("coupon_code"));
                return null;
            });
        } catch (SeatLost e) {
            // Extremely rare: the hold lapsed while the customer was paying and someone else took a seat. Refund in full.
            db.tx(c -> {
                Db.update(c, "UPDATE payments SET pay_status='PAID', provider_payment_id=? WHERE id=?", providerPaymentId, pay.str("id"));
                Db.update(c, "UPDATE bookings SET status='FAILED' WHERE pnr=?", pnr);
                Db.update(c, "DELETE FROM seat_locks WHERE booking_pnr=?", pnr);
                return null;
            });
            doRefund(pnr, b.lng("total_paise"), "Seats unavailable after payment");
            throw ApiException.conflict("Sorry, your seats were taken while the payment was processing. Your payment is being refunded in full.");
        }
        emailConfirmation(pnr);
        return view(pnr);
    }

    // =====================================================================
    //  3. cancel (whole booking or some seats) and operator-side trip cancellation
    // =====================================================================
    public Map<String, Object> cancel(Row user, String pnr, List<String> labels) {
        Row b = owned(user, pnr);
        if (!"CONFIRMED".equals(b.str("status"))) throw ApiException.conflict("This booking cannot be cancelled.");
        Row trip = db.one("SELECT departure FROM trips WHERE id=?", b.str("trip_id"));
        LocalDateTime now = LocalDateTime.now();
        if (!trip.dt("departure").isAfter(now)) throw ApiException.conflict("This bus has already departed, so the ticket can no longer be cancelled.");

        List<Row> active = db.query("SELECT * FROM booking_seats WHERE booking_pnr=? AND seat_status='ACTIVE'", pnr);
        List<Row> chosen = new ArrayList<>();
        if (labels == null || labels.isEmpty()) chosen.addAll(active);
        else for (String l : labels) {
            Row s = active.stream().filter(x -> x.str("seat_label").equalsIgnoreCase(l.trim())).findFirst()
                    .orElseThrow(() -> ApiException.bad("Seat " + l + " is not part of this active booking."));
            if (!chosen.contains(s)) chosen.add(s);
        }
        if (chosen.isEmpty()) throw ApiException.bad("Choose at least one seat to cancel.");
        int pct = refundPercent(Duration.between(now, trip.dt("departure")));
        cancelSeats(b, chosen, pct, "Cancelled by passenger", now);
        return view(pnr);
    }

    private long paidShare(Row booking, Row seat) {
        long base = booking.lng("base_paise");
        return base == 0 ? 0 : Math.round((double) booking.lng("total_paise") * seat.lng("fare_paise") / base);
    }

    private void cancelSeats(Row b, List<Row> seats, int pct, String reason, LocalDateTime now) {
        String pnr = b.str("pnr");
        long[] total = {0};
        db.tx(c -> {
            for (Row s : seats) {
                long refund = Math.round(paidShare(b, s) * pct / 100.0);
                if (Db.update(c, "UPDATE booking_seats SET seat_status='CANCELLED', refund_paise=? WHERE booking_pnr=? AND seat_label=? AND seat_status='ACTIVE'", refund, pnr, s.str("seat_label")) == 0)
                    throw ApiException.conflict("These seats were already cancelled.");
                Db.update(c, "DELETE FROM seat_locks WHERE trip_id=? AND seat_label=? AND booking_pnr=?", b.str("trip_id"), s.str("seat_label"), pnr);
                total[0] += refund;
            }
            long remaining = Db.one(c, "SELECT COUNT(*) n FROM booking_seats WHERE booking_pnr=? AND seat_status='ACTIVE'", pnr).lng("n");
            Db.update(c, "UPDATE bookings SET refund_paise = refund_paise + ? WHERE pnr=?", total[0], pnr);
            if (remaining == 0) Db.update(c, "UPDATE bookings SET status='CANCELLED', cancelled_at=? WHERE pnr=?", now, pnr);
            return null;
        });
        doRefund(pnr, total[0], reason);
        emailCancellation(pnr, seats.size(), total[0], reason);
    }

    private void doRefund(String pnr, long amountPaise, String reason) {
        if (amountPaise <= 0) return;
        Row pay = db.one("SELECT * FROM payments WHERE booking_pnr=? AND pay_status='PAID' ORDER BY created_at DESC LIMIT 1", pnr);
        String status = "PROCESSED", ref = null;
        if (pay == null || !gateway.name().equals(pay.str("provider"))) status = "MANUAL";
        else try { ref = gateway.refund(pay.str("provider_payment_id"), amountPaise); }
        catch (Exception e) { status = "FAILED"; System.err.println("Refund for " + pnr + " failed: " + e.getMessage()); }
        db.exec("INSERT INTO refunds(id,booking_pnr,amount_paise,refund_status,provider_ref,reason,created_at) VALUES (?,?,?,?,?,?,?)",
                Security.id(), pnr, amountPaise, status, ref, reason, LocalDateTime.now());
    }

    /** Admin: the operator cancels a whole trip. Everyone is refunded 100% and emailed. Returns the number of bookings cancelled. */
    public int cancelTrip(String tripId, String reason) {
        Row t = db.one("SELECT * FROM trips WHERE id=?", tripId);
        if (t == null) throw ApiException.notFound("Trip not found.");
        if ("CANCELLED".equals(t.str("status"))) throw ApiException.conflict("This trip is already cancelled.");
        db.exec("UPDATE trips SET status='CANCELLED' WHERE id=?", tripId);
        db.tx(c -> {
            Db.update(c, "UPDATE bookings SET status='EXPIRED' WHERE trip_id=? AND status='PENDING_PAYMENT'", tripId);
            Db.update(c, "DELETE FROM seat_locks WHERE trip_id=? AND lock_state='HELD'", tripId);
            return null;
        });
        int n = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Row b : db.query("SELECT * FROM bookings WHERE trip_id=? AND status='CONFIRMED'", tripId)) {
            List<Row> active = db.query("SELECT * FROM booking_seats WHERE booking_pnr=? AND seat_status='ACTIVE'", b.str("pnr"));
            if (!active.isEmpty()) { cancelSeats(b, active, 100, reason == null || reason.isBlank() ? "Trip cancelled by operator" : reason, now); n++; }
        }
        return n;
    }

    // =====================================================================
    //  4. reading: views, my bookings, track, reviews
    // =====================================================================
    private Row owned(Row user, String pnr) {
        Row b = db.one("SELECT * FROM bookings WHERE pnr=?", pnr == null ? "" : pnr.trim().toUpperCase());
        if (b == null || (!b.str("user_id").equals(user.str("id")) && !"ADMIN".equals(user.str("role"))))
            throw ApiException.notFound("No booking found for that PNR.");
        return b;
    }

    public Map<String, Object> viewFor(Row user, String pnr) { return view(owned(user, pnr).str("pnr")); }

    public List<Map<String, Object>> mine(Row user) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row r : db.query("SELECT b.pnr FROM bookings b JOIN trips t ON t.id=b.trip_id WHERE b.user_id=? AND b.status IN ('CONFIRMED','CANCELLED') ORDER BY t.departure DESC LIMIT 200", user.str("id")))
            out.add(view(r.str("pnr")));
        return out;
    }

    public Map<String, Object> track(String pnr, String contact) {
        Row b = db.one("SELECT * FROM bookings WHERE pnr=? AND status IN ('CONFIRMED','CANCELLED')", pnr == null ? "" : pnr.trim().toUpperCase());
        String c = contact == null ? "" : contact.trim().toLowerCase(), digits = c.replaceAll("\\D", "");
        boolean ok = b != null && (c.equals(b.str("contact_email").toLowerCase())
                || (digits.length() >= 10 && b.str("contact_phone").endsWith(digits.substring(digits.length() - 10))));
        if (!ok) throw ApiException.notFound("No booking found with those details. Check the PNR and the email or mobile number used while booking.");
        return view(b.str("pnr"));
    }

    public void review(Row user, String pnr, int rating, String text) {
        Map<String, Object> v = viewFor(user, pnr);
        if (!Boolean.TRUE.equals(v.get("canReview"))) throw ApiException.conflict("You can review a trip after you have travelled.");
        if (rating < 1 || rating > 5) throw ApiException.bad("Choose a rating from 1 to 5 stars.");
        String t = text == null ? "" : text.trim();
        if (t.length() > 500) throw ApiException.bad("Please keep the review under 500 characters.");
        Row op = db.one("SELECT s.operator_id FROM bookings b JOIN trips t ON t.id=b.trip_id JOIN services s ON s.id=t.service_id WHERE b.pnr=?", v.get("pnr"));
        try {
            db.exec("INSERT INTO reviews(id,booking_pnr,user_id,operator_id,rating,review_text,created_at) VALUES (?,?,?,?,?,?,?)",
                    Security.id(), v.get("pnr"), user.str("id"), op.str("operator_id"), rating, t, LocalDateTime.now());
        } catch (RuntimeException e) {
            if (Db.isDuplicate(e)) throw ApiException.conflict("You have already reviewed this trip.");
            throw e;
        }
    }

    /** Full JSON view of one booking (also used for tickets). */
    public Map<String, Object> view(String pnr) {
        Row b = db.one("SELECT b.*, t.departure, t.arrival, t.status trip_status, t.delay_min, s.origin, s.destination, s.operator_id, "
                + "bu.name bus_name, bu.bus_type, bu.reg_number, o.name operator_name, o.phone operator_phone "
                + "FROM bookings b JOIN trips t ON t.id=b.trip_id JOIN services s ON s.id=t.service_id "
                + "JOIN buses bu ON bu.id=t.bus_id JOIN operators o ON o.id=s.operator_id WHERE b.pnr=?", pnr);
        if (b == null) throw ApiException.notFound("Booking not found.");
        LocalDateTime now = LocalDateTime.now();
        boolean upcoming = b.dt("departure").isAfter(now);
        int pct = upcoming ? refundPercent(Duration.between(now, b.dt("departure"))) : 0;

        List<Object> seats = new ArrayList<>();
        int active = 0;
        for (Row s : db.query("SELECT * FROM booking_seats WHERE booking_pnr=? ORDER BY seat_label", pnr)) {
            boolean isActive = "ACTIVE".equals(s.str("seat_status"));
            if (isActive) active++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", s.str("seat_label")); m.put("name", s.str("passenger_name")); m.put("age", s.integer("age"));
            m.put("gender", s.str("gender")); m.put("fare", CatalogService.rupees(s.lng("fare_paise"))); m.put("status", s.str("seat_status"));
            m.put("refund", CatalogService.rupees(s.lng("refund_paise")));
            m.put("refundIfCancelledNow", CatalogService.rupees(isActive ? Math.round(paidShare(b, s) * pct / 100.0) : 0));
            seats.add(m);
        }
        boolean confirmed = "CONFIRMED".equals(b.str("status"));
        Row review = db.one("SELECT rating, review_text FROM reviews WHERE booking_pnr=?", pnr);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pnr", pnr); m.put("status", b.str("status")); m.put("tripId", b.str("trip_id")); m.put("tripStatus", b.str("trip_status"));
        m.put("source", b.str("origin")); m.put("destination", b.str("destination"));
        m.put("departure", b.dt("departure").toString()); m.put("arrival", b.dt("arrival").toString()); m.put("delayMin", b.integer("delay_min"));
        m.put("operatorName", b.str("operator_name")); m.put("operatorPhone", b.str("operator_phone"));
        m.put("busName", b.str("bus_name")); m.put("busType", b.str("bus_type")); m.put("busNumber", b.str("reg_number"));
        m.put("boarding", b.str("boarding")); m.put("dropping", b.str("dropping"));
        m.put("contactEmail", b.str("contact_email")); m.put("contactPhone", b.str("contact_phone"));
        m.put("base", CatalogService.rupees(b.lng("base_paise"))); m.put("discount", CatalogService.rupees(b.lng("discount_paise")));
        m.put("total", CatalogService.rupees(b.lng("total_paise"))); m.put("refunded", CatalogService.rupees(b.lng("refund_paise")));
        m.put("coupon", b.str("coupon_code"));
        m.put("createdAt", b.dt("created_at").toString());
        m.put("expiresAt", b.dt("expires_at") == null ? null : b.dt("expires_at").toString());
        if ("PENDING_PAYMENT".equals(b.str("status"))) {
            Row pay = db.one("SELECT * FROM payments WHERE booking_pnr=? AND pay_status='CREATED' ORDER BY created_at DESC LIMIT 1", pnr);
            if (pay != null) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("provider", pay.str("provider")); p.put("orderId", pay.str("provider_order_id"));
                p.put("demo", "DEMO".equals(pay.str("provider")));
                p.put("keyId", "RAZORPAY".equals(pay.str("provider")) ? Config.RAZORPAY_KEY_ID : null);
                p.put("amountPaise", pay.lng("amount_paise")); p.put("currency", "INR");
                m.put("payment", p);
            }
            m.put("secondsLeft", Math.max(0, Duration.between(now, b.dt("expires_at")).getSeconds()));
        }
        m.put("seats", seats);
        m.put("canCancel", confirmed && upcoming && active > 0);
        m.put("refundPercentNow", pct);
        m.put("canReview", confirmed && !upcoming && active > 0 && review == null);
        m.put("review", review == null ? null : Map.of("rating", review.integer("rating"), "text", review.str("review_text") == null ? "" : review.str("review_text")));
        return m;
    }

    // =====================================================================
    //  emails
    // =====================================================================
    private static String rs(Object amount) { return "Rs. " + String.format("%.2f", ((Number) amount).doubleValue()); }

    private void emailConfirmation(String pnr) {
        try {
            Map<String, Object> v = view(pnr);
            StringBuilder seats = new StringBuilder();
            for (Object o : (List<?>) v.get("seats")) { Map<?, ?> s = (Map<?, ?>) o; seats.append("  Seat ").append(s.get("label")).append(" - ").append(s.get("name")).append(" (").append(s.get("age")).append(")\n"); }
            mail.send(String.valueOf(v.get("contactEmail")), "Your ticket is confirmed - PNR " + pnr,
                    "Your booking is confirmed.\n\nPNR: " + pnr + "\n" + v.get("source") + " to " + v.get("destination") + "\n"
                    + "Departure: " + LocalDateTime.parse((String) v.get("departure")).format(WHEN) + "\n"
                    + "Bus: " + v.get("busName") + " (" + v.get("busNumber") + "), operated by " + v.get("operatorName") + "\n"
                    + "Boarding: " + v.get("boarding") + "\nDropping: " + v.get("dropping") + "\n\nPassengers:\n" + seats
                    + "\nTotal paid: " + rs(v.get("total")) + "\n\nView or cancel your ticket: " + Config.BASE_URL + "/#/ticket/" + pnr
                    + "\n\nCancellation: more than 24h before departure = 90% refund, more than 6h = 50%, less than 6h = no refund.\n\nHave a safe journey,\nFirst Choice", false);
        } catch (RuntimeException e) { System.err.println("Confirmation email failed: " + e.getMessage()); }
    }

    private void emailCancellation(String pnr, int seatCount, long refundPaise, String reason) {
        try {
            Map<String, Object> v = view(pnr);
            mail.send(String.valueOf(v.get("contactEmail")), "Cancellation for PNR " + pnr,
                    "We cancelled " + seatCount + " seat(s) on your booking.\n\nPNR: " + pnr + "\n" + v.get("source") + " to " + v.get("destination")
                    + " on " + LocalDateTime.parse((String) v.get("departure")).format(WHEN) + "\nReason: " + reason + "\n"
                    + (refundPaise > 0 ? "Refund: " + rs(CatalogService.rupees(refundPaise)) + " to your original payment method (usually 5-7 working days).\n" : "No refund applies for this cancellation.\n")
                    + "\nFirst Choice", false);
        } catch (RuntimeException e) { System.err.println("Cancellation email failed: " + e.getMessage()); }
    }
}
