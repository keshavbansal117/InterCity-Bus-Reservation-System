package com.firstchoice.busreservation.dao;

import com.firstchoice.busreservation.model.Booking;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface BookingDAO {
    void save(Booking booking);
    Optional<Booking> findByPnr(String pnr);
    List<Booking> findByUser(int userId);
    List<Booking> findAll();
    /** Seat numbers currently held by CONFIRMED bookings on this schedule. */
    Set<Integer> bookedSeats(int scheduleId);
}
