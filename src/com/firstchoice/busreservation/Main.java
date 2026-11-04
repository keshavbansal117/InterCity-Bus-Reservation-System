package com.firstchoice.busreservation;

import com.firstchoice.busreservation.dao.*;
import com.firstchoice.busreservation.exception.BookingException;
import com.firstchoice.busreservation.model.*;
import com.firstchoice.busreservation.service.*;
import com.firstchoice.busreservation.util.TicketPrinter;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

public class Main {
    private static final Scanner in = new Scanner(System.in);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM hh:mm a");

    private static AuthService auth;
    private static AdminService admin;
    private static BookingService booking;

    public static void main(String[] args) throws Exception {
        UserDAO userDAO = new InMemoryUserDAO();
        ScheduleDAO scheduleDAO = new InMemoryScheduleDAO();
        BookingDAO bookingDAO = new InMemoryBookingDAO();
        auth = new AuthService(userDAO);
        admin = new AdminService(scheduleDAO);
        booking = new BookingService(scheduleDAO, bookingDAO);
        DataSeeder.seed(auth, admin);

        System.out.println("==========================================");
        System.out.println("   FIRST CHOICE - Intercity Bus Booking");
        System.out.println("==========================================");
        System.out.println("Demo logins:  user@demo.com / user123");
        System.out.println("              admin@firstchoice.com / admin123");

        while (true) {
            System.out.println("\n--- MAIN MENU ---\n1. Login\n2. Register\n0. Exit");
            switch (ask("Choice")) {
                case "1" -> login();
                case "2" -> register();
                case "0" -> { System.out.println("Thank you for using First Choice!"); return; }
                default -> System.out.println("Invalid choice.");
            }
        }
    }

    // ---------------- auth ----------------
    private static void register() {
        try {
            User u = auth.register(ask("Name"), ask("Email"), ask("Password (min 6 chars)"), Role.PASSENGER);
            System.out.println("Registered! You can now log in, " + u.name() + ".");
        } catch (BookingException e) { System.out.println("Error: " + e.getMessage()); }
    }

    private static void login() {
        try {
            User u = auth.login(ask("Email"), ask("Password"));
            System.out.println("Welcome, " + u.name() + "!");
            if (u.role() == Role.ADMIN) adminMenu(u); else passengerMenu(u);
        } catch (BookingException e) { System.out.println("Error: " + e.getMessage()); }
    }

    // ---------------- passenger ----------------
    private static void passengerMenu(User user) {
        while (true) {
            System.out.println("\n--- PASSENGER MENU ---\n1. Search & book bus\n2. My bookings\n3. View ticket by PNR\n4. Cancel ticket\n0. Logout");
            switch (ask("Choice")) {
                case "1" -> searchAndBook(user);
                case "2" -> myBookings(user);
                case "3" -> viewTicket(user);
                case "4" -> cancelTicket(user);
                case "0" -> { return; }
                default -> System.out.println("Invalid choice.");
            }
        }
    }

    private static void searchAndBook(User user) {
        String from = ask("From");
        String to = ask("To");
        LocalDate date = askDate("Travel date (yyyy-MM-dd)");
        if (date == null) return;

        List<Schedule> results = booking.search(from, to, date);
        if (results.isEmpty()) { System.out.println("No buses found for that route/date."); return; }

        System.out.printf("%n%-4s %-16s %-9s %-15s %-15s %-8s %-6s%n", "ID", "Bus", "Type", "Departs", "Arrives", "Fare", "Free");
        for (Schedule s : results) {
            int free = s.bus().totalSeats() - booking.bookedSeats(s.id()).size();
            System.out.printf("%-4d %-16s %-9s %-15s %-15s Rs.%-5.0f %-6d%n", s.id(), s.bus().name(), s.bus().type(),
                    s.departure().format(TIME), s.arrival().format(TIME), s.fare(), free);
        }

        String idStr = ask("Enter schedule ID to book (blank to go back)");
        if (idStr.isBlank()) return;
        try {
            Schedule sch = booking.getSchedule(Integer.parseInt(idStr));
            showSeatMap(sch);

            String[] seatTokens = ask("Seat numbers (comma separated, e.g. 5,6)").split(",");
            List<PassengerSeat> passengers = new ArrayList<>();
            for (String t : seatTokens) {
                int seat = Integer.parseInt(t.trim());
                System.out.println("Passenger for seat " + seat + ":");
                String name = ask("  Name");
                int age = Integer.parseInt(ask("  Age"));
                String g = ask("  Gender (M/F/O)").toUpperCase();
                passengers.add(new PassengerSeat(seat, name, age, g.isEmpty() ? 'O' : g.charAt(0)));
            }

            double total = sch.fare() * passengers.size();
            System.out.printf("Total fare: Rs. %.2f%n", total);
            if (!ask("Proceed to (dummy) payment? (y/n)").equalsIgnoreCase("y")) { System.out.println("Cancelled."); return; }
            System.out.println("Processing payment... SUCCESS (simulated)");

            Booking b = booking.book(user, sch.id(), passengers);
            System.out.println("\nBooking confirmed!\n" + TicketPrinter.format(b));
        } catch (NumberFormatException e) {
            System.out.println("Error: please enter numbers where numbers are expected.");
        } catch (BookingException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private static void showSeatMap(Schedule s) {
        Set<Integer> taken = booking.bookedSeats(s.id());
        System.out.println("\nSeat map  ([##] = booked)   |  FRONT (driver)");
        for (int i = 1; i <= s.bus().totalSeats(); i++) {
            System.out.print(taken.contains(i) ? "[##] " : String.format("[%02d] ", i));
            if (i % 4 == 2) System.out.print("  ");        // aisle
            if (i % 4 == 0) System.out.println();
        }
        System.out.println();
    }

    private static void myBookings(User user) {
        List<Booking> list = booking.historyOf(user);
        if (list.isEmpty()) { System.out.println("No bookings yet."); return; }
        System.out.printf("%n%-12s %-24s %-15s %-6s %-10s %-10s%n", "PNR", "Route", "Departure", "Seats", "Amount", "Status");
        for (Booking b : list) {
            System.out.printf("%-12s %-24s %-15s %-6d Rs.%-7.0f %-10s%n", b.getPnr(),
                    b.getSchedule().source() + "->" + b.getSchedule().destination(),
                    b.getSchedule().departure().format(TIME), b.getSeats().size(), b.getTotalAmount(), b.getStatus());
        }
    }

    private static void viewTicket(User user) {
        String pnr = ask("PNR");
        booking.historyOf(user).stream().filter(b -> b.getPnr().equalsIgnoreCase(pnr.trim())).findFirst()
                .ifPresentOrElse(b -> System.out.println(TicketPrinter.format(b)),
                        () -> System.out.println("No booking with that PNR on your account."));
    }

    private static void cancelTicket(User user) {
        System.out.println("Refund policy: >24h before departure = 90%, 6-24h = 50%, <6h = no refund.");
        try {
            Booking b = booking.cancel(user, ask("PNR to cancel"));
            System.out.printf("Cancelled. Refund of Rs. %.2f will be credited.%n", b.getRefundAmount());
        } catch (BookingException e) { System.out.println("Error: " + e.getMessage()); }
    }

    // ---------------- admin ----------------
    private static void adminMenu(User admin) {
        while (true) {
            System.out.println("\n--- ADMIN MENU ---\n1. Add new bus schedule\n2. View all schedules\n3. View all bookings\n4. Occupancy & revenue report\n0. Logout");
            switch (ask("Choice")) {
                case "1" -> addSchedule();
                case "2" -> booking.allSchedules().forEach(s -> System.out.printf("#%d %s -> %s | %s | %s (%s) | Rs.%.0f%n",
                        s.id(), s.source(), s.destination(), s.departure().format(TIME), s.bus().name(), s.bus().type(), s.fare()));
                case "3" -> booking.allBookings().forEach(b -> System.out.printf("%s | %s->%s | %d seat(s) | Rs.%.0f | %s%n",
                        b.getPnr(), b.getSchedule().source(), b.getSchedule().destination(), b.getSeats().size(), b.getTotalAmount(), b.getStatus()));
                case "4" -> report();
                case "0" -> { return; }
                default -> System.out.println("Invalid choice.");
            }
        }
    }

    private static void addSchedule() {
        try {
            Bus bus = admin.newBus(ask("Bus number"), ask("Bus name"),
                    BusType.valueOf(ask("Type (AC/NON_AC/SLEEPER)").toUpperCase()), Integer.parseInt(ask("Total seats")));
            String from = ask("From"), to = ask("To");
            LocalDateTime dep = LocalDateTime.parse(ask("Departure (yyyy-MM-ddTHH:mm)"));
            LocalDateTime arr = LocalDateTime.parse(ask("Arrival   (yyyy-MM-ddTHH:mm)"));
            Schedule s = admin.addSchedule(bus, from, to, dep, arr, Double.parseDouble(ask("Fare per seat")));
            System.out.println("Schedule #" + s.id() + " added.");
        } catch (IllegalArgumentException | DateTimeParseException e) {
            System.out.println("Error: invalid input (" + e.getMessage() + ")");
        } catch (BookingException e) { System.out.println("Error: " + e.getMessage()); }
    }

    private static void report() {
        System.out.printf("%n%-4s %-24s %-10s %-10s%n", "ID", "Route", "Occupancy", "Revenue");
        double grand = 0;
        for (Schedule s : booking.allSchedules()) {
            int sold = booking.bookedSeats(s.id()).size();
            double rev = sold * s.fare();
            grand += rev;
            System.out.printf("%-4d %-24s %-10s Rs.%-8.0f%n", s.id(), s.source() + "->" + s.destination(),
                    sold + "/" + s.bus().totalSeats() + " (" + (100 * sold / s.bus().totalSeats()) + "%)", rev);
        }
        System.out.printf("TOTAL REVENUE (confirmed): Rs. %.0f%n", grand);
    }

    // ---------------- input helpers ----------------
    private static String ask(String prompt) {
        System.out.print(prompt + ": ");
        return in.hasNextLine() ? in.nextLine().trim() : "0";
    }

    private static LocalDate askDate(String prompt) {
        try { return LocalDate.parse(ask(prompt)); }
        catch (DateTimeParseException e) { System.out.println("Invalid date format."); return null; }
    }
}
