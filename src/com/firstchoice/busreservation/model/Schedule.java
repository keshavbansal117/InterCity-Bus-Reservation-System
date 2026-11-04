package com.firstchoice.busreservation.model;

import java.time.LocalDateTime;

/** One trip: a bus running a route at a particular date/time. Seats are tracked per schedule. */
public record Schedule(int id, Bus bus, String source, String destination,
                       LocalDateTime departure, LocalDateTime arrival, double fare) { }
