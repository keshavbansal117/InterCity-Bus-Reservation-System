package com.firstchoice.busreservation.model;

public record User(int id, String name, String email, String passwordHash, String salt, Role role) { }
