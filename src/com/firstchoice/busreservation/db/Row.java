package com.firstchoice.busreservation.db;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;

/** One database row with typed getters. Column names are lower case. */
public final class Row extends LinkedHashMap<String, Object> {
    public String str(String k) { Object v = get(k); return v == null ? null : v.toString(); }
    public long lng(String k) { Object v = get(k); return v == null ? 0 : ((Number) v).longValue(); }
    public int integer(String k) { return (int) lng(k); }
    public double dbl(String k) { Object v = get(k); return v == null ? 0 : ((Number) v).doubleValue(); }
    public boolean bool(String k) { Object v = get(k); return v instanceof Boolean b && b; }
    public LocalDateTime dt(String k) { return (LocalDateTime) get(k); }
    public LocalDate date(String k) { return (LocalDate) get(k); }

    static Object convert(Object v) {
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof BigDecimal b) return b.scale() <= 0 ? (Object) b.longValue() : (Object) b.doubleValue();
        return v;
    }
}
