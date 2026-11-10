package com.firstchoice.busreservation.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** Simple in-memory sliding-window limiter (per key, e.g. "login:1.2.3.4"). */
public final class RateLimiter {
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /** Records an attempt; returns false when the key has exceeded max attempts within the window. */
    public boolean allow(String key, int max, long windowSeconds) {
        long now = System.currentTimeMillis(), cutoff = now - windowSeconds * 1000;
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < cutoff) q.pollFirst();
            if (q.size() >= max) return false;
            q.addLast(now);
        }
        if (hits.size() > 20_000) hits.entrySet().removeIf(e -> { synchronized (e.getValue()) { return e.getValue().isEmpty() || e.getValue().peekLast() < cutoff; } });
        return true;
    }
}
