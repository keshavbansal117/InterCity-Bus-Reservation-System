package com.firstchoice.busreservation.dao;

import com.firstchoice.busreservation.model.User;
import java.util.Optional;

public interface UserDAO {
    int nextId();
    void save(User user);
    Optional<User> findByEmail(String email);
}
