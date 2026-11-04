import com.firstchoice.busreservation.dao.*;
import com.firstchoice.busreservation.model.*;
import com.firstchoice.busreservation.service.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Run: java -cp out:testout SelfTest */
public class SelfTest {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) { System.out.println((ok ? "PASS  " : "FAIL  ") + name); if (ok) pass++; else fail++; }

    public static void main(String[] a) throws Exception {
        // refund slabs
        check("refund 48h = 90%", BookingService.refundPercent(Duration.ofHours(48)) == 0.90);
        check("refund 10h = 50%", BookingService.refundPercent(Duration.ofHours(10)) == 0.50);
        check("refund 3h  = 0%",  BookingService.refundPercent(Duration.ofHours(3)) == 0.0);

        UserDAO u = new InMemoryUserDAO(); ScheduleDAO s = new InMemoryScheduleDAO(); BookingDAO b = new InMemoryBookingDAO();
        AuthService auth = new AuthService(u); AdminService admin = new AdminService(s); BookingService svc = new BookingService(s, b);
        DataSeeder.seed(auth, admin);
        User user = auth.login("user@demo.com", "user123");

        // bad login
        try { auth.login("user@demo.com", "wrong"); check("wrong password rejected", false); }
        catch (Exception e) { check("wrong password rejected", true); }
        try { auth.login(null, "user123"); check("null email rejected", false); }
        catch (Exception e) { check("null email rejected", true); }
        try { auth.login("user@demo.com", null); check("null password rejected", false); }
        catch (Exception e) { check("null password rejected", true); }

        // passenger validation
        try { svc.book(user, 1, List.of(new PassengerSeat(2, "", 25, 'M'))); check("blank passenger name rejected", false); }
        catch (Exception e) { check("blank passenger name rejected", true); }
        try { svc.book(user, 1, List.of(new PassengerSeat(2, "Test", 0, 'M'))); check("zero age rejected", false); }
        catch (Exception e) { check("zero age rejected", true); }
        try { svc.book(user, 1, List.of(new PassengerSeat(2, "Test", 150, 'M'))); check("excessive age rejected", false); }
        catch (Exception e) { check("excessive age rejected", true); }
        try { svc.book(user, 1, List.of(new PassengerSeat(2, "Test", 25, 'X'))); check("invalid gender rejected", false); }
        catch (Exception e) { check("invalid gender rejected", true); }

        // max seats per booking limit (max 6)
        List<PassengerSeat> sevenSeats = new ArrayList<>();
        for (int i = 1; i <= 7; i++) sevenSeats.add(new PassengerSeat(i, "P" + i, 25, 'M'));
        try { svc.book(user, 1, sevenSeats); check("booking >6 seats rejected", false); }
        catch (Exception e) { check("booking >6 seats rejected", true); }

        // book + cancel (trip is >24h away or ~tomorrow; just check cancel works and frees seat)
        Booking bk = svc.book(user, 1, List.of(new PassengerSeat(1, "A", 20, 'M')));
        check("seat 1 held after booking", svc.bookedSeats(1).contains(1));
        svc.cancel(user, bk.getPnr());
        check("seat 1 freed after cancel", !svc.bookedSeats(1).contains(1));
        check("refund > 0 and <= fare", bk.getRefundAmount() >= 0 && bk.getRefundAmount() <= bk.getTotalAmount());
        try { svc.cancel(user, bk.getPnr()); check("double cancel rejected", false); }
        catch (Exception e) { check("double cancel rejected", true); }

        // invalid seat
        try { svc.book(user, 1, List.of(new PassengerSeat(99, "A", 20, 'M'))); check("seat 99 rejected", false); }
        catch (Exception e) { check("seat 99 rejected", true); }

        // concurrency: 50 threads, same seat, exactly 1 must win
        ExecutorService ex = Executors.newFixedThreadPool(50);
        AtomicInteger wins = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 50; i++) fs.add(ex.submit(() -> {
            try { go.await(); svc.book(user, 2, List.of(new PassengerSeat(7, "T", 30, 'M'))); wins.incrementAndGet(); }
            catch (Exception ignored) { }
        }));
        go.countDown();
        for (Future<?> f : fs) f.get();
        ex.shutdown();
        check("50 concurrent bookings of seat 7 -> exactly 1 succeeds (got " + wins.get() + ")", wins.get() == 1);

        // admin schedule validation
        Bus testBus = admin.newBus("PB-99-ZZ-9999", "Super Test", BusType.AC, 30);
        try {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            admin.addSchedule(testBus, "Delhi", "Delhi", now.plusDays(1), now.plusDays(1).plusHours(5), 500);
            check("same source and destination rejected", false);
        } catch (Exception e) {
            check("same source and destination rejected", true);
        }
        try {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            admin.addSchedule(testBus, "Delhi", "Chandigarh", now.plusDays(1).plusHours(5), now.plusDays(1), 500);
            check("arrival before departure rejected", false);
        } catch (Exception e) {
            check("arrival before departure rejected", true);
        }

        // Json roundtrip check
        Map<String, Object> testObj = new LinkedHashMap<>();
        testObj.put("title", "First Choice \"Express\"");
        testObj.put("seats", List.of(1, 2, 3));
        testObj.put("active", true);
        testObj.put("fare", 450.50);
        String jsonStr = com.firstchoice.busreservation.web.Json.stringify(testObj);
        Map<String, Object> parsed = com.firstchoice.busreservation.web.Json.parseObject(jsonStr);
        check("Json stringify and parse roundtrip", "First Choice \"Express\"".equals(parsed.get("title")) && Boolean.TRUE.equals(parsed.get("active")));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
