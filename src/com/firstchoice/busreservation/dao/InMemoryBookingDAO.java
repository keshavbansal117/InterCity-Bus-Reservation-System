package com.firstchoice.busreservation.dao;

import com.firstchoice.busreservation.model.*;
import java.util.*;

public class InMemoryBookingDAO implements BookingDAO {
    private final Map<String, Booking> store = new LinkedHashMap<>();
    private final Map<Integer, Set<Integer>> activeSeatsBySchedule = new HashMap<>();

    @Override
    public synchronized void save(Booking b) {
        String key = b.getPnr().toUpperCase();
        store.put(key, b);

        Set<Integer> seats = activeSeatsBySchedule.computeIfAbsent(b.getSchedule().id(), k -> new TreeSet<>());
        if (b.getStatus() == BookingStatus.CONFIRMED) {
            b.getSeats().forEach(s -> seats.add(s.seatNo()));
        } else {
            b.getSeats().forEach(s -> seats.remove(s.seatNo()));
        }
    }

    @Override
    public synchronized Optional<Booking> findByPnr(String pnr) {
        if (pnr == null) return Optional.empty();
        return Optional.ofNullable(store.get(pnr.trim().toUpperCase()));
    }

    @Override
    public synchronized List<Booking> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public synchronized List<Booking> findByUser(int userId) {
        return store.values().stream().filter(b -> b.getUserId() == userId).toList();
    }

    @Override
    public synchronized Set<Integer> bookedSeats(int scheduleId) {
        Set<Integer> set = activeSeatsBySchedule.get(scheduleId);
        return set == null ? Collections.emptySet() : Collections.unmodifiableSet(new TreeSet<>(set));
    }
}
