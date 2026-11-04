package com.firstchoice.busreservation.dao;

import com.firstchoice.busreservation.model.User;
import java.util.*;

public class InMemoryUserDAO implements UserDAO {
    private final Map<String, User> byEmail = new HashMap<>();
    private int counter = 0;

    @Override public synchronized int nextId() { return ++counter; }
    @Override public synchronized void save(User u) { byEmail.put(u.email().toLowerCase(), u); }
    @Override public synchronized Optional<User> findByEmail(String email) {
        return Optional.ofNullable(byEmail.get(email.toLowerCase()));
    }
}
