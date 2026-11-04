package com.firstchoice.busreservation.util;

import java.security.SecureRandom;
import java.util.function.Predicate;

public final class PNRGenerator {
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RND = new SecureRandom();
    private PNRGenerator() { }

    /** Generates "FC" + 8 chars, retrying until the given predicate says it is unused. */
    public static String generate(Predicate<String> alreadyExists) {
        String pnr;
        do {
            StringBuilder sb = new StringBuilder("FC");
            for (int i = 0; i < 8; i++) sb.append(CHARS.charAt(RND.nextInt(CHARS.length())));
            pnr = sb.toString();
        } while (alreadyExists.test(pnr));
        return pnr;
    }
}
