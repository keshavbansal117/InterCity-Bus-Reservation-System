package com.firstchoice.busreservation.service;

import com.firstchoice.busreservation.dao.ScheduleDAO;
import com.firstchoice.busreservation.exception.BookingException;
import com.firstchoice.busreservation.model.*;

import java.time.LocalDateTime;

public class AdminService {
    private final ScheduleDAO scheduleDAO;
    private int busCounter = 0;

    public AdminService(ScheduleDAO scheduleDAO) { this.scheduleDAO = scheduleDAO; }

    public synchronized Bus newBus(String number, String name, BusType type, int seats) throws BookingException {
        if (number == null || number.isBlank()) throw new BookingException("Bus number is required.");
        if (name == null || name.isBlank()) throw new BookingException("Bus name is required.");
        if (type == null) throw new BookingException("Bus type is required.");
        if (seats < 1 || seats > 60) throw new BookingException("Seats must be between 1 and 60.");
        return new Bus(++busCounter, number.trim(), name.trim(), type, seats);
    }

    public synchronized Schedule addSchedule(Bus bus, String from, String to, LocalDateTime dep, LocalDateTime arr, double fare)
            throws BookingException {
        if (bus == null) throw new BookingException("Bus details are required.");
        if (from == null || from.isBlank() || to == null || to.isBlank())
            throw new BookingException("Source and destination are required.");
        if (from.trim().equalsIgnoreCase(to.trim()))
            throw new BookingException("Source and destination cannot be the same.");
        if (dep == null || arr == null)
            throw new BookingException("Departure and arrival times are required.");
        if (!arr.isAfter(dep))
            throw new BookingException("Arrival must be after departure.");
        if (fare <= 0)
            throw new BookingException("Fare must be positive.");
        if (bus.totalSeats() < 1 || bus.totalSeats() > 60)
            throw new BookingException("Seats must be between 1 and 60.");
        Schedule s = new Schedule(scheduleDAO.nextId(), bus, from.trim(), to.trim(), dep, arr, fare);
        scheduleDAO.save(s);
        return s;
    }
}
