-- First Choice schema. Portable SQL: runs on H2 (default) and PostgreSQL.
-- Money is stored in paise (integer) to avoid rounding errors. Statements are separated by semicolons.

CREATE TABLE IF NOT EXISTS users (
  id VARCHAR(24) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  email VARCHAR(160) NOT NULL,
  phone VARCHAR(20),
  password_hash VARCHAR(300) NOT NULL,
  role VARCHAR(12) NOT NULL,
  email_verified BOOLEAN NOT NULL,
  failed_logins INTEGER NOT NULL,
  locked_until TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS sessions (
  token_hash VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(24) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_tokens (
  token_hash VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(24) NOT NULL,
  purpose VARCHAR(12) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  used_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS operators (
  id VARCHAR(24) PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  phone VARCHAR(20),
  about VARCHAR(400),
  active BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS buses (
  id VARCHAR(24) PRIMARY KEY,
  operator_id VARCHAR(24) NOT NULL,
  reg_number VARCHAR(30) NOT NULL,
  name VARCHAR(80) NOT NULL,
  bus_type VARCHAR(10) NOT NULL,
  layout VARCHAR(10) NOT NULL,
  capacity INTEGER NOT NULL,
  amenities VARCHAR(300),
  ladies_seats VARCHAR(200),
  active BOOLEAN NOT NULL,
  CONSTRAINT uq_bus_reg UNIQUE (reg_number)
);

CREATE TABLE IF NOT EXISTS services (
  id VARCHAR(24) PRIMARY KEY,
  operator_id VARCHAR(24) NOT NULL,
  bus_id VARCHAR(24) NOT NULL,
  origin VARCHAR(80) NOT NULL,
  destination VARCHAR(80) NOT NULL,
  depart_time VARCHAR(5) NOT NULL,
  duration_min INTEGER NOT NULL,
  fare_paise BIGINT NOT NULL,
  run_days VARCHAR(7) NOT NULL,
  active BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS service_points (
  id VARCHAR(24) PRIMARY KEY,
  service_id VARCHAR(24) NOT NULL,
  point_type VARCHAR(10) NOT NULL,
  point_name VARCHAR(120) NOT NULL,
  offset_min INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS trips (
  id VARCHAR(24) PRIMARY KEY,
  service_id VARCHAR(24) NOT NULL,
  bus_id VARCHAR(24) NOT NULL,
  travel_date DATE NOT NULL,
  departure TIMESTAMP NOT NULL,
  arrival TIMESTAMP NOT NULL,
  fare_paise BIGINT NOT NULL,
  status VARCHAR(12) NOT NULL,
  delay_min INTEGER NOT NULL,
  CONSTRAINT uq_trip_service_date UNIQUE (service_id, travel_date)
);

CREATE INDEX IF NOT EXISTS idx_trips_date ON trips (travel_date);

-- One row per seat that is held (HELD, expires) or sold (BOOKED). The primary key is what makes
-- double booking impossible: two people can never insert the same (trip, seat).
CREATE TABLE IF NOT EXISTS seat_locks (
  trip_id VARCHAR(24) NOT NULL,
  seat_label VARCHAR(8) NOT NULL,
  booking_pnr VARCHAR(12) NOT NULL,
  lock_state VARCHAR(8) NOT NULL,
  expires_at TIMESTAMP,
  PRIMARY KEY (trip_id, seat_label)
);

CREATE TABLE IF NOT EXISTS bookings (
  pnr VARCHAR(12) PRIMARY KEY,
  user_id VARCHAR(24) NOT NULL,
  trip_id VARCHAR(24) NOT NULL,
  status VARCHAR(16) NOT NULL,
  boarding VARCHAR(160),
  dropping VARCHAR(160),
  contact_email VARCHAR(160) NOT NULL,
  contact_phone VARCHAR(20) NOT NULL,
  base_paise BIGINT NOT NULL,
  discount_paise BIGINT NOT NULL,
  total_paise BIGINT NOT NULL,
  refund_paise BIGINT NOT NULL,
  coupon_code VARCHAR(30),
  created_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP,
  confirmed_at TIMESTAMP,
  cancelled_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bookings_user ON bookings (user_id);
CREATE INDEX IF NOT EXISTS idx_bookings_trip ON bookings (trip_id);

CREATE TABLE IF NOT EXISTS booking_seats (
  booking_pnr VARCHAR(12) NOT NULL,
  seat_label VARCHAR(8) NOT NULL,
  passenger_name VARCHAR(100) NOT NULL,
  age INTEGER NOT NULL,
  gender VARCHAR(1) NOT NULL,
  fare_paise BIGINT NOT NULL,
  seat_status VARCHAR(10) NOT NULL,
  refund_paise BIGINT NOT NULL,
  PRIMARY KEY (booking_pnr, seat_label)
);

CREATE TABLE IF NOT EXISTS payments (
  id VARCHAR(24) PRIMARY KEY,
  booking_pnr VARCHAR(12) NOT NULL,
  provider VARCHAR(12) NOT NULL,
  provider_order_id VARCHAR(60),
  provider_payment_id VARCHAR(60),
  amount_paise BIGINT NOT NULL,
  pay_status VARCHAR(10) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS refunds (
  id VARCHAR(24) PRIMARY KEY,
  booking_pnr VARCHAR(12) NOT NULL,
  amount_paise BIGINT NOT NULL,
  refund_status VARCHAR(10) NOT NULL,
  provider_ref VARCHAR(60),
  reason VARCHAR(120),
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS coupons (
  code VARCHAR(30) PRIMARY KEY,
  description VARCHAR(160) NOT NULL,
  kind VARCHAR(8) NOT NULL,
  amount BIGINT NOT NULL,
  min_paise BIGINT NOT NULL,
  max_discount_paise BIGINT NOT NULL,
  valid_from DATE,
  valid_to DATE,
  usage_limit INTEGER,
  used_count INTEGER NOT NULL,
  active BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS reviews (
  id VARCHAR(24) PRIMARY KEY,
  booking_pnr VARCHAR(12) NOT NULL,
  user_id VARCHAR(24) NOT NULL,
  operator_id VARCHAR(24) NOT NULL,
  rating INTEGER NOT NULL,
  review_text VARCHAR(600),
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uq_review_booking UNIQUE (booking_pnr)
);

CREATE TABLE IF NOT EXISTS outbox (
  id VARCHAR(24) PRIMARY KEY,
  to_email VARCHAR(160) NOT NULL,
  subject VARCHAR(200) NOT NULL,
  body VARCHAR(4000),
  mail_status VARCHAR(10) NOT NULL,
  created_at TIMESTAMP NOT NULL
);
