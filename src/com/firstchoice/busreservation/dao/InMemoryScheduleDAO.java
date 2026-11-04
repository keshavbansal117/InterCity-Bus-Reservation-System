package com.firstchoice.busreservation.dao;

import com.firstchoice.busreservation.model.Schedule;
import java.time.LocalDate;
import java.util.*;

public class InMemoryScheduleDAO implements ScheduleDAO {
    private final Map<Integer, Schedule> store = new LinkedHashMap<>();
    private int counter = 0;

    @Override public synchronized int nextId() { return ++counter; }
    @Override public synchronized void save(Schedule s) { store.put(s.id(), s); }
    @Override public synchronized Optional<Schedule> findById(int id) { return Optional.ofNullable(store.get(id)); }
    @Override public synchronized List<Schedule> findAll() { return new ArrayList<>(store.values()); }

    @Override
    public synchronized List<Schedule> search(String source, String destination, LocalDate date) {
        return store.values().stream()
                .filter(s -> s.source().equalsIgnoreCase(source.trim()))
                .filter(s -> s.destination().equalsIgnoreCase(destination.trim()))
                .filter(s -> s.departure().toLocalDate().equals(date))
                .sorted(Comparator.comparing(Schedule::departure))
                .toList();
    }
}
