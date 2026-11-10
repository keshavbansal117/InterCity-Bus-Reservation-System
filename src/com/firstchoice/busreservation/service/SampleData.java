package com.firstchoice.busreservation.service;

import com.firstchoice.busreservation.db.Db;
import com.firstchoice.busreservation.util.Security;

import java.util.*;

/**
 * Loads a SAMPLE timetable the first time the database is empty, so the site is not blank on day one.
 * Operators and fares are illustrative. Replace them with your real operators in the Admin panel,
 * or start with SEED_SAMPLE_DATA=false to begin empty.
 */
public final class SampleData {
    private SampleData() { }

    private record Pt(String name, int offset) { }

    public static void loadIfEmpty(Db db) {
        if (db.count("SELECT COUNT(*) n FROM operators") > 0) return;

        String malwa = op(db, "Malwa Express Lines (sample)", "9876500001", "Sample operator for demonstration.");
        String doaba = op(db, "Doaba Travels (sample)", "9876500002", "Sample operator for demonstration.");
        String night = op(db, "Night Liners (sample)", "9876500003", "Sample operator for demonstration.");

        String ac = bus(db, malwa, "PB-13-AA-1101", "Malwa Express AC", "AC", "SEATER", 40, "WiFi,Charging point,Water bottle,Reading light", "1,2,5,6");
        String ord = bus(db, malwa, "PB-13-AB-2202", "Malwa Ordinary", "NON_AC", "SEATER", 40, "Charging point,Water bottle", "1,2,5,6");
        String volvo = bus(db, doaba, "PB-10-CD-3303", "Doaba Volvo", "AC", "SEATER", 44, "WiFi,Charging point,Blanket,Water bottle,Live tracking", "1,2,3,4");
        String sl1 = bus(db, night, "PB-03-EF-4404", "Night Liner Sleeper", "AC", "SLEEPER", 30, "Charging point,Blanket,Water bottle,Reading light", "L1,L2,U1,U2");
        String sl2 = bus(db, night, "HR-26-GH-5505", "Night Liner Plus", "AC", "SLEEPER", 30, "WiFi,Charging point,Blanket,Water bottle", "L1,L2");

        List<Pt> barnala = List.of(new Pt("Barnala Bus Stand", 0), new Pt("Barnala Bypass Chowk", 15));
        List<Pt> chd = List.of(new Pt("Sector 43 ISBT, Chandigarh", 0), new Pt("Sector 17 Bus Stand, Chandigarh", -20));
        List<Pt> delhi = List.of(new Pt("Kashmere Gate ISBT, Delhi", 0), new Pt("Anand Vihar ISBT, Delhi", -35));
        List<Pt> bathinda = List.of(new Pt("Bathinda Bus Stand", 0), new Pt("Bathinda Railway Station", 10));
        List<Pt> ludhiana = List.of(new Pt("Ludhiana Bus Stand", 0), new Pt("Jagraon Bridge, Ludhiana", 15));
        List<Pt> amritsar = List.of(new Pt("Amritsar Bus Stand", 0));
        List<Pt> patiala = List.of(new Pt("Patiala Bus Stand", 0));

        svc(db, malwa, ac, "Barnala", "Chandigarh", "07:00", 240, 380, barnala, chd);
        svc(db, malwa, ord, "Barnala", "Chandigarh", "11:30", 270, 250, barnala, chd);
        svc(db, doaba, volvo, "Barnala", "Chandigarh", "15:00", 225, 450, barnala, chd);
        svc(db, malwa, ac, "Chandigarh", "Barnala", "16:00", 240, 380, chd, barnala);
        svc(db, malwa, ord, "Chandigarh", "Barnala", "06:30", 270, 250, chd, barnala);
        svc(db, doaba, volvo, "Bathinda", "Delhi", "06:00", 360, 650, bathinda, delhi);
        svc(db, night, sl1, "Bathinda", "Delhi", "21:30", 420, 850, bathinda, delhi);
        svc(db, night, sl1, "Delhi", "Bathinda", "22:00", 420, 850, delhi, bathinda);
        svc(db, malwa, ord, "Ludhiana", "Amritsar", "09:00", 210, 220, ludhiana, amritsar);
        svc(db, malwa, ord, "Amritsar", "Ludhiana", "15:00", 210, 220, amritsar, ludhiana);
        svc(db, malwa, ord, "Patiala", "Chandigarh", "08:00", 90, 110, patiala, chd);
        svc(db, doaba, volvo, "Patiala", "Delhi", "13:00", 270, 480, patiala, delhi);
        svc(db, night, sl2, "Amritsar", "Delhi", "20:00", 540, 950, amritsar, delhi);

        coupon(db, "SAVE10", "PERCENT", 10, 30000, 10000, "10% off (up to Rs. 100) on bookings of Rs. 300 or more");
        coupon(db, "FLAT50", "FLAT", 5000, 50000, 5000, "Flat Rs. 50 off on bookings of Rs. 500 or more");
        TripGenerator.generateAll(db);
        System.out.println("Sample timetable loaded (replace it from the Admin panel with your real operators).");
    }

    private static String op(Db db, String name, String phone, String about) {
        String id = Security.id();
        db.exec("INSERT INTO operators(id,name,phone,about,active) VALUES (?,?,?,?,?)", id, name, phone, about, true);
        return id;
    }

    private static String bus(Db db, String op, String reg, String name, String type, String layout, int cap, String amenities, String ladies) {
        String id = Security.id();
        db.exec("INSERT INTO buses(id,operator_id,reg_number,name,bus_type,layout,capacity,amenities,ladies_seats,active) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, op, reg, name, type, layout, cap, amenities, ladies, true);
        return id;
    }

    private static void svc(Db db, String op, String bus, String from, String to, String time, int dur, long rupees,
                            List<Pt> boarding, List<Pt> dropping) {
        String id = Security.id();
        db.exec("INSERT INTO services(id,operator_id,bus_id,origin,destination,depart_time,duration_min,fare_paise,run_days,active) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, op, bus, from, to, time, dur, rupees * 100, "1111111", true);
        for (Pt p : boarding) db.exec("INSERT INTO service_points(id,service_id,point_type,point_name,offset_min) VALUES (?,?,?,?,?)", Security.id(), id, "BOARDING", p.name(), p.offset());
        for (Pt p : dropping) db.exec("INSERT INTO service_points(id,service_id,point_type,point_name,offset_min) VALUES (?,?,?,?,?)", Security.id(), id, "DROPPING", p.name(), p.offset());
    }

    private static void coupon(Db db, String code, String kind, long amount, long min, long max, String desc) {
        db.exec("INSERT INTO coupons(code,description,kind,amount,min_paise,max_discount_paise,valid_from,valid_to,usage_limit,used_count,active) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                code, desc, kind, amount, min, max, null, null, null, 0, true);
    }
}
