-- MySQL schema for upgrading the in-memory DAOs to JDBC
CREATE DATABASE IF NOT EXISTS firstchoice_bus;
USE firstchoice_bus;

CREATE TABLE users (
  user_id INT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  email VARCHAR(120) NOT NULL UNIQUE,
  password_hash CHAR(64) NOT NULL,
  salt VARCHAR(40) NOT NULL,
  role ENUM('PASSENGER','ADMIN') NOT NULL DEFAULT 'PASSENGER'
);

CREATE TABLE buses (
  bus_id INT AUTO_INCREMENT PRIMARY KEY,
  bus_number VARCHAR(20) NOT NULL UNIQUE,
  bus_name VARCHAR(60) NOT NULL,
  bus_type ENUM('AC','NON_AC','SLEEPER') NOT NULL,
  total_seats INT NOT NULL CHECK (total_seats BETWEEN 1 AND 60)
);

CREATE TABLE schedules (
  schedule_id INT AUTO_INCREMENT PRIMARY KEY,
  bus_id INT NOT NULL,
  source VARCHAR(60) NOT NULL,
  destination VARCHAR(60) NOT NULL,
  departure DATETIME NOT NULL,
  arrival DATETIME NOT NULL,
  fare DECIMAL(8,2) NOT NULL CHECK (fare > 0),
  FOREIGN KEY (bus_id) REFERENCES buses(bus_id),
  INDEX idx_search (source, destination, departure)
);

CREATE TABLE bookings (
  pnr CHAR(10) PRIMARY KEY,
  user_id INT NOT NULL,
  schedule_id INT NOT NULL,
  total_amount DECIMAL(9,2) NOT NULL,
  refund_amount DECIMAL(9,2) NOT NULL DEFAULT 0,
  status ENUM('CONFIRMED','CANCELLED') NOT NULL DEFAULT 'CONFIRMED',
  booked_at DATETIME NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(user_id),
  FOREIGN KEY (schedule_id) REFERENCES schedules(schedule_id)
);

-- active = 1 while booking is CONFIRMED, NULL when cancelled.
-- UNIQUE ignores NULLs, so a cancelled seat can be rebooked but a live seat can never be double-booked.
CREATE TABLE booking_seats (
  id INT AUTO_INCREMENT PRIMARY KEY,
  pnr CHAR(10) NOT NULL,
  schedule_id INT NOT NULL,
  seat_no INT NOT NULL,
  passenger_name VARCHAR(100) NOT NULL,
  age INT NOT NULL CHECK (age BETWEEN 1 AND 120),
  gender CHAR(1) NOT NULL CHECK (gender IN ('M', 'F', 'O')),
  active TINYINT NULL DEFAULT 1,
  FOREIGN KEY (pnr) REFERENCES bookings(pnr) ON DELETE CASCADE,
  UNIQUE KEY uq_live_seat (schedule_id, seat_no, active),
  INDEX idx_schedule_active (schedule_id, active)
);
