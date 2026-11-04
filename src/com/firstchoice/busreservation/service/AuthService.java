package com.firstchoice.busreservation.service;

import com.firstchoice.busreservation.dao.UserDAO;
import com.firstchoice.busreservation.exception.BookingException;
import com.firstchoice.busreservation.model.Role;
import com.firstchoice.busreservation.model.User;
import com.firstchoice.busreservation.util.PasswordUtil;
import com.firstchoice.busreservation.util.Validator;

public class AuthService {
    private final UserDAO userDAO;

    public AuthService(UserDAO userDAO) { this.userDAO = userDAO; }

    public synchronized User register(String name, String email, String password, Role role) throws BookingException {
        if (name == null || name.isBlank()) throw new BookingException("Name is required.");
        if (!Validator.isEmail(email)) throw new BookingException("Invalid email format.");
        if (!Validator.isStrongEnough(password)) throw new BookingException("Password must be at least 6 characters.");
        if (userDAO.findByEmail(email).isPresent()) throw new BookingException("Email already registered.");

        String salt = PasswordUtil.newSalt();
        User user = new User(userDAO.nextId(), name.trim(), email.trim(), PasswordUtil.hash(password, salt), salt, role);
        userDAO.save(user);
        return user;
    }

    public User login(String email, String password) throws BookingException {
        if (email == null || email.isBlank() || password == null || password.isBlank())
            throw new BookingException("Invalid email or password.");
        User user = userDAO.findByEmail(email.trim())
                .orElseThrow(() -> new BookingException("Invalid email or password."));
        if (!PasswordUtil.verify(password, user.salt(), user.passwordHash()))
            throw new BookingException("Invalid email or password.");
        return user;
    }
}
