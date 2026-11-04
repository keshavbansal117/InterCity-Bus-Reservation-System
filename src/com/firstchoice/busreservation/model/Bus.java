package com.firstchoice.busreservation.model;

public record Bus(int id, String number, String name, BusType type, int totalSeats) { }
