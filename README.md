# First Choice - Intercity Bus Ticket Reservation (v2)

Java backend (built-in HTTP server + JDBC) with a real SQL database, plus an HTML/CSS/JS website.

## Run on your computer (Windows)
1. Install the **JDK 17 or newer** (check with `javac -version`).
2. Double-click **run.bat**. The site opens at http://localhost:8080 (Mac/Linux: `./run.sh`).
3. Admin login (local only): `admin@firstchoice.local` / `Admin@12345`.  Passengers create their own accounts on the site.

## Where is my data saved?
In the **`data/` folder** next to run.bat, in the file `data/firstchoice.mv.db` (an H2 SQL database).
Users, bookings, payments, everything lives there and survives restarts. To back up, copy that file while the server is stopped.
To start completely fresh, stop the server and delete the `data` folder.
Passwords are never stored: only a salted PBKDF2 hash. Login sessions and reset/verify links are stored only as SHA-256 hashes.
To use a bigger server database instead (recommended when hosting), set `DATABASE_URL` to a PostgreSQL address (see below).

## What is included
Accounts: sign up with mobile + email, login/logout, email confirmation, **forgot/reset password** (30-minute single-use link),
change password, log out of all devices, account lockout after 5 wrong passwords, rate limiting.
Booking: search with filters/sort, live seat map (seater + sleeper decks, women-only seats), boarding/dropping points,
10-minute seat hold with countdown, coupons, payment (demo or Razorpay), ticket + print, partial or full cancellation with refund,
operator ratings and reviews, track a booking without login, email notifications.
Admin: dashboard/revenue report, trips (delay, cancel trip with automatic 100% refunds), recurring timetable, buses, operators,
coupons, bookings search, users, email outbox, failed-refund list.

## Settings (environment variables, all optional)
| Variable | Meaning |
|---|---|
| PORT | web port (default 8080) |
| APP_BASE_URL | public address of your site, used in email links, e.g. https://buses.example.com |
| ADMIN_EMAIL / ADMIN_PASSWORD | first admin account (created only if no admin exists). If ADMIN_PASSWORD is empty a random one is printed once |
| DATABASE_URL | PostgreSQL address `postgres://user:pass@host/db` (the Dockerfile includes the driver). Default: local H2 file |
| SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD, SMTP_FROM, SMTP_SECURITY | real email (STARTTLS, SSL or NONE). Without SMTP_HOST emails are only printed in the console and Admin > Outbox |
| RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET | real payments. Without them the built-in demo gateway is used (no money moves) |
| SEED_SAMPLE_DATA | `false` to start with an empty timetable instead of the sample routes |
| TRUST_PROXY | `true` behind a proxy/host so client IPs are read from X-Forwarded-For (set in the Dockerfile) |
| HOLD_MINUTES, DAYS_AHEAD, BOOKING_CLOSE_MIN | seat hold time (10), days of trips generated (30), booking closes N minutes before departure (15) |

## Going live checklist
1. **Real data:** open Admin > Operators, Buses, Timetable and enter your real operators, buses, routes, fares and stops.
   The sample operators are marked "(sample)". Or start with SEED_SAMPLE_DATA=false.
2. **Email:** create an SMTP account (Gmail app password, Brevo, Zoho, ...) and set the SMTP_* variables. Without it, password-reset emails are NOT delivered to users.
3. **Payments:** create a Razorpay account, complete its KYC, set RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET (use test keys first).
4. **Database:** use PostgreSQL (Neon, Supabase, Render Postgres) via DATABASE_URL, because free hosts erase local files.
5. **Host:** push to GitHub, create a Docker web service (Render/Railway/Fly), add the variables above, set APP_BASE_URL to your https address.
6. Set a strong ADMIN_PASSWORD before the first start.

## Not included (needs outside services or business agreements)
SMS/WhatsApp OTP and tickets, live GPS tracking, QR codes, GST invoices, and real-time feeds from other operators' systems.
Check consumer-protection, GST and refund rules for your business before going live.
