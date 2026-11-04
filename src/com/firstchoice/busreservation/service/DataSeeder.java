package com.firstchoice.busreservation.service;

import com.firstchoice.busreservation.model.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Demo data so the app works the moment you start it. Creates trips for the next 6 days. */
public final class DataSeeder {
    private DataSeeder() { }

    public static void seed(AuthService auth, AdminService admin) throws Exception {
        seed(auth, admin, "admin123");
    }

    public static void seed(AuthService auth, AdminService admin, String adminPassword) throws Exception {
        auth.register("Administrator", "admin@firstchoice.com", adminPassword, Role.ADMIN);
        auth.register("Demo Passenger", "user@demo.com", "user123", Role.PASSENGER);

        Bus ac = admin.newBus("PB-13-AC-1234", "Punjab Express", BusType.AC, 40);
        Bus nonAc = admin.newBus("PB-03-NA-5678", "Malwa Travels", BusType.NON_AC, 40);
        Bus sleeper = admin.newBus("HR-26-SL-9012", "Night Rider", BusType.SLEEPER, 30);

        for (int day = 1; day <= 6; day++) {
            LocalDate d = LocalDate.now().plusDays(day);
            //  bus, from, to, date, hour, minute, duration(min), fare
            add(admin, ac,      "Barnala",    "Chandigarh", d,  7,  0, 210, 450);
            add(admin, nonAc,   "Barnala",    "Chandigarh", d, 11, 30, 270, 320);
            add(admin, ac,      "Chandigarh", "Barnala",    d, 16,  0, 210, 450);
            add(admin, nonAc,   "Chandigarh", "Barnala",    d,  6, 30, 270, 320);
            add(admin, ac,      "Bathinda",   "Delhi",      d,  6,  0, 330, 700);
            add(admin, sleeper, "Bathinda",   "Delhi",      d, 21, 30, 390, 900);
            add(admin, sleeper, "Delhi",      "Bathinda",   d, 22,  0, 390, 900);
            add(admin, nonAc,   "Ludhiana",   "Amritsar",   d,  9,  0, 210, 280);
            add(admin, nonAc,   "Amritsar",   "Ludhiana",   d, 15,  0, 210, 280);
            add(admin, nonAc,   "Patiala",    "Chandigarh", d,  8,  0,  90, 150);
            add(admin, ac,      "Patiala",    "Delhi",      d, 13,  0, 270, 520);
        }
    }

    private static void add(AdminService admin, Bus bus, String from, String to, LocalDate date,
                            int hour, int minute, int durationMin, double fare) throws Exception {
        LocalDateTime dep = LocalDateTime.of(date, LocalTime.of(hour, minute));
        admin.addSchedule(bus, from, to, dep, dep.plusMinutes(durationMin), fare);
    }
}
