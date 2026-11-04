package com.firstchoice.busreservation.service;

import com.firstchoice.busreservation.dao.BookingDAO;
import com.firstchoice.busreservation.dao.ScheduleDAO;
import com.firstchoice.busreservation.exception.BookingException;
import com.firstchoice.busreservation.model.*;
import com.firstchoice.busreservation.util.PNRGenerator;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

public class BookingService {
    private final ScheduleDAO scheduleDAO;
    private final BookingDAO bookingDAO;

    /**
     * Guards the "check seat free -> save booking" step so two users can never get the same seat.
     * With MySQL you would replace this with a DB transaction + UNIQUE(schedule_id, seat_no).
     */
    private final Object bookingLock = new Object();

    public BookingService(ScheduleDAO scheduleDAO, BookingDAO bookingDAO) {
        this.scheduleDAO = scheduleDAO;
        this.bookingDAO = bookingDAO;
    }

    public List<Schedule> search(String from, String to, LocalDate date) {
        if (from == null || to == null || date == null) return Collections.emptyList();
        LocalDateTime now = LocalDateTime.now();
        return scheduleDAO.search(from, to, date).stream()
                .filter(s -> s.departure().isAfter(now))
                .toList();
    }

    public Set<Integer> bookedSeats(int scheduleId) { return bookingDAO.bookedSeats(scheduleId); }

    public Schedule getSchedule(int id) throws BookingException {
        return scheduleDAO.findById(id).orElseThrow(() -> new BookingException("Schedule not found."));
    }

    public Booking book(User user, int scheduleId, List<PassengerSeat> passengers) throws BookingException {
        if (passengers == null || passengers.isEmpty()) throw new BookingException("Select at least one seat.");
        if (passengers.size() > 6) throw new BookingException("You can book at most 6 seats at a time.");

        synchronized (bookingLock) {
            Schedule schedule = getSchedule(scheduleId);
            if (!schedule.departure().isAfter(LocalDateTime.now()))
                throw new BookingException("This bus has already departed.");

            Set<Integer> chosen = new HashSet<>();
            Set<Integer> taken = bookingDAO.bookedSeats(scheduleId);
            for (PassengerSeat p : passengers) {
                if (p.seatNo() < 1 || p.seatNo() > schedule.bus().totalSeats())
                    throw new BookingException("Seat " + p.seatNo() + " does not exist.");
                if (!chosen.add(p.seatNo()))
                    throw new BookingException("Seat " + p.seatNo() + " selected twice.");
                if (taken.contains(p.seatNo()))
                    throw new BookingException("Seat " + p.seatNo() + " is already booked.");
                if (p.name() == null || p.name().isBlank()) throw new BookingException("Passenger name is required.");
                if (p.age() < 1 || p.age() > 120) throw new BookingException("Invalid age for " + p.name() + ".");
                char g = Character.toUpperCase(p.gender());
                if (g != 'M' && g != 'F' && g != 'O') throw new BookingException("Invalid gender for " + p.name() + ".");
            }

            double total = Math.round(schedule.fare() * passengers.size() * 100.0) / 100.0;
            String pnr = PNRGenerator.generate(p -> bookingDAO.findByPnr(p).isPresent());
            Booking booking = new Booking(pnr, user.id(), schedule, passengers, total, LocalDateTime.now());
            bookingDAO.save(booking);
            return booking;
        }
    }

    /** Refund slabs: >24h = 90%, >6h = 50%, otherwise 0%. */
    public static double refundPercent(Duration timeToDeparture) {
        if (timeToDeparture.compareTo(Duration.ofHours(24)) > 0) return 0.90;
        if (timeToDeparture.compareTo(Duration.ofHours(6)) > 0) return 0.50;
        return 0.0;
    }

    public Booking cancel(User user, String pnr) throws BookingException {
        if (pnr == null || pnr.isBlank()) throw new BookingException("PNR is required.");
        synchronized (bookingLock) {
            Booking b = bookingDAO.findByPnr(pnr.trim())
                    .orElseThrow(() -> new BookingException("No booking found for PNR " + pnr.trim()));
            if (b.getUserId() != user.id() && user.role() != Role.ADMIN)
                throw new BookingException("This booking belongs to another user.");
            if (b.getStatus() == BookingStatus.CANCELLED)
                throw new BookingException("Booking is already cancelled.");

            Duration left = Duration.between(LocalDateTime.now(), b.getSchedule().departure());
            if (left.isNegative() || left.isZero())
                throw new BookingException("Bus has already departed; cancellation not allowed.");

            b.cancel(Math.round(b.getTotalAmount() * refundPercent(left) * 100.0) / 100.0);
            bookingDAO.save(b);   // seats become free automatically (only CONFIRMED bookings hold seats)
            return b;
        }
    }

    public List<Booking> historyOf(User user) { return bookingDAO.findByUser(user.id()); }
    public List<Booking> allBookings() { return bookingDAO.findAll(); }
    public List<Schedule> allSchedules() { return scheduleDAO.findAll(); }
}
