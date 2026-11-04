package com.firstchoice.busreservation.model;

import java.time.LocalDateTime;
import java.util.List;

public class Booking {
    private final String pnr;
    private final int userId;
    private final Schedule schedule;
    private final List<PassengerSeat> seats;
    private final double totalAmount;
    private final LocalDateTime bookedAt;
    private BookingStatus status = BookingStatus.CONFIRMED;
    private double refundAmount;

    public Booking(String pnr, int userId, Schedule schedule, List<PassengerSeat> seats,
                   double totalAmount, LocalDateTime bookedAt) {
        this.pnr = pnr; this.userId = userId; this.schedule = schedule;
        this.seats = List.copyOf(seats); this.totalAmount = totalAmount; this.bookedAt = bookedAt;
    }

    public String getPnr() { return pnr; }
    public int getUserId() { return userId; }
    public Schedule getSchedule() { return schedule; }
    public List<PassengerSeat> getSeats() { return seats; }
    public double getTotalAmount() { return totalAmount; }
    public LocalDateTime getBookedAt() { return bookedAt; }
    public BookingStatus getStatus() { return status; }
    public double getRefundAmount() { return refundAmount; }

    public void cancel(double refund) { this.status = BookingStatus.CANCELLED; this.refundAmount = refund; }
}
