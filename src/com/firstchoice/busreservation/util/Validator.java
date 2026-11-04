package com.firstchoice.busreservation.util;

import java.util.regex.Pattern;

public final class Validator {
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    private Validator() { }

    public static boolean isEmail(String s) { return s != null && EMAIL.matcher(s).matches(); }
    public static boolean isStrongEnough(String pw) { return pw != null && pw.length() >= 6; }
}
