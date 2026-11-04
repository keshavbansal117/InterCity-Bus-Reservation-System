# First Choice - Intercity Bus Ticket Reservation (Web version)

Java backend (built-in HTTP server, no libraries) + HTML/CSS/JS website.

## Run on your computer (Windows)
1. Install the JDK 17 or newer (check with `javac -version`).
2. Double-click `run.bat`. The site opens at http://localhost:8080
   (Mac/Linux: `./run.sh`)

Logins:  passenger `user@demo.com / user123`   |   admin `admin@firstchoice.com / admin123`
(or create your own passenger account on the site).

## Structure
- `web/`      index.html, style.css, app.js (the website)
- `src/.../web/WebApp.java   HTTP server + JSON API (/api/...), Json.java (tiny JSON parser/writer)
- `src/.../service|dao|model|util`   business logic (booking, refund, double-booking protection) - same as the console version
- `src/.../Main.java`        the old console version (still works)
- `sql/schema.sql`           MySQL schema for the JDBC upgrade
- `Dockerfile`               for hosting

## API
POST /api/register, /api/login | GET /api/me, /api/cities, /api/search?from=&to=&date=
GET /api/schedules/{id} | GET,POST /api/bookings | GET /api/bookings/{pnr} | POST /api/bookings/{pnr}/cancel
GET,POST /api/admin/schedules | GET /api/admin/bookings, /api/admin/report   (admin only)

## Hosting (Render.com example, free tier)
1. Put this folder on GitHub.
2. Render -> New -> Web Service -> connect the repo -> Runtime: Docker.
3. Add environment variable `ADMIN_PASSWORD` (choose a strong password!).
4. Deploy. Render gives you a public https URL.

Important: data is stored in memory, so bookings and accounts reset whenever the server restarts
(free hosts also sleep when idle). For a real deployment, implement JDBC DAOs on MySQL/PostgreSQL
using `sql/schema.sql` - the services and website do not need to change.

Environment variables: PORT (default 8080), ADMIN_PASSWORD (default admin123), WEB_DIR (default web).
