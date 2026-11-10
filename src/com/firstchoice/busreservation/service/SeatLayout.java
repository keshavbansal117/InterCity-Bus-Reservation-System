package com.firstchoice.busreservation.service;

import java.util.*;

/** Builds the seat plan of a bus: SEATER (2+2 seats) or SLEEPER (lower + upper deck, 1+2 berths). */
public final class SeatLayout {
    public record Seat(String label, String deck, int row, int col, boolean ladies) { }

    public final String type;
    public final int cols;
    public final List<Seat> seats;

    private SeatLayout(String type, int cols, List<Seat> seats) { this.type = type; this.cols = cols; this.seats = seats; }

    public Set<String> labels() { Set<String> s = new LinkedHashSet<>(); for (Seat x : seats) s.add(x.label()); return s; }

    public static SeatLayout build(String type, int capacity, String ladiesCsv) {
        Set<String> ladies = new HashSet<>();
        if (ladiesCsv != null) for (String l : ladiesCsv.split(",")) if (!l.isBlank()) ladies.add(l.trim().toUpperCase());
        List<Seat> list = new ArrayList<>();
        if ("SLEEPER".equals(type)) {
            int lower = (capacity + 1) / 2, upper = capacity - lower;
            for (int i = 0; i < lower; i++) list.add(new Seat("L" + (i + 1), "L", i / 3, i % 3 == 0 ? 0 : (i % 3) + 1, ladies.contains("L" + (i + 1))));
            for (int i = 0; i < upper; i++) list.add(new Seat("U" + (i + 1), "U", i / 3, i % 3 == 0 ? 0 : (i % 3) + 1, ladies.contains("U" + (i + 1))));
            return new SeatLayout(type, 4, list);
        }
        for (int i = 0; i < capacity; i++) {
            int pos = i % 4;
            list.add(new Seat(String.valueOf(i + 1), "L", i / 4, pos < 2 ? pos : pos + 1, ladies.contains(String.valueOf(i + 1))));
        }
        return new SeatLayout("SEATER", 5, list);
    }
}
