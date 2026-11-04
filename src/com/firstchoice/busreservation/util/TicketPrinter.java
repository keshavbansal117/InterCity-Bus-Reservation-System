package com.firstchoice.busreservation.util;

import com.firstchoice.busreservation.model.*;
import java.time.format.DateTimeFormatter;

public final class TicketPrinter {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");
    private TicketPrinter() { }

    public static String format(Booking b) {
        Schedule s = b.getSchedule();
        StringBuilder sb = new StringBuilder();
        String line = "=".repeat(50);
        sb.append(line).append('\n');
        sb.append("        FIRST CHOICE - INTERCITY BUS TICKET\n");
        sb.append(line).append('\n');
        sb.append(String.format("PNR        : %s   [%s]%n", b.getPnr(), b.getStatus()));
        sb.append(String.format("Route      : %s -> %s%n", s.source(), s.destination()));
        sb.append(String.format("Bus        : %s (%s) %s%n", s.bus().name(), s.bus().number(), s.bus().type()));
        sb.append(String.format("Departure  : %s%n", s.departure().format(FMT)));
        sb.append(String.format("Arrival    : %s%n", s.arrival().format(FMT)));
        sb.append("-".repeat(50)).append('\n');
        sb.append(String.format("%-6s %-20s %-5s %-6s%n", "Seat", "Passenger", "Age", "Sex"));
        for (PassengerSeat p : b.getSeats()) {
            sb.append(String.format("%-6d %-20s %-5d %-6s%n", p.seatNo(), p.name(), p.age(), p.gender()));
        }
        sb.append("-".repeat(50)).append('\n');
        sb.append(String.format("Total paid : Rs. %.2f%n", b.getTotalAmount()));
        if (b.getStatus() == BookingStatus.CANCELLED)
            sb.append(String.format("Refund     : Rs. %.2f%n", b.getRefundAmount()));
        sb.append(line);
        return sb.toString();
    }
}
