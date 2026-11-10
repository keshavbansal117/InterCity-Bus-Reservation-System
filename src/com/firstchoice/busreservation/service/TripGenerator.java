package com.firstchoice.busreservation.service;

import com.firstchoice.busreservation.db.Db;
import com.firstchoice.busreservation.db.Row;
import com.firstchoice.busreservation.util.Config;
import com.firstchoice.busreservation.util.Security;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * A "service" is a recurring timetable entry (e.g. Barnala to Chandigarh, 07:00, every day).
 * This class turns services into dated trips for the next DAYS_AHEAD days. Safe to run repeatedly.
 */
public final class TripGenerator {
    private TripGenerator() { }

    public static int generateAll(Db db) {
        int n = 0;
        for (Row s : db.query("SELECT * FROM services WHERE active=?", true)) n += generateFor(db, s);
        return n;
    }

    public static int generateFor(Db db, Row svc) {
        int created = 0;
        LocalDateTime now = LocalDateTime.now();
        LocalTime time = LocalTime.parse(svc.str("depart_time"));
        for (int i = 0; i <= Config.DAYS_AHEAD; i++) {
            LocalDate d = now.toLocalDate().plusDays(i);
            if (svc.str("run_days").charAt(d.getDayOfWeek().getValue() - 1) != '1') continue;
            LocalDateTime dep = d.atTime(time);
            if (!dep.isAfter(now)) continue;
            if (db.one("SELECT id FROM trips WHERE service_id=? AND travel_date=?", svc.str("id"), d) != null) continue;
            db.exec("INSERT INTO trips(id,service_id,bus_id,travel_date,departure,arrival,fare_paise,status,delay_min) VALUES (?,?,?,?,?,?,?,?,?)",
                    Security.id(), svc.str("id"), svc.str("bus_id"), d, dep, dep.plusMinutes(svc.integer("duration_min")), svc.lng("fare_paise"), "SCHEDULED", 0);
            created++;
        }
        return created;
    }
}
