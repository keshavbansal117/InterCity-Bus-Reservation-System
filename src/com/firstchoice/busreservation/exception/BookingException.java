package com.firstchoice.busreservation.exception;

/** Any business-rule failure (seat taken, invalid login, late cancellation ...). */
public class BookingException extends Exception {
    public BookingException(String message) { super(message); }
}
