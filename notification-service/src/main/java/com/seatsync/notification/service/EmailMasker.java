package com.seatsync.notification.service;

import java.util.regex.Pattern;

/**
 * Keeps recipient addresses out of the logs: {@code attendee@seatsync.local}
 * becomes {@code a***@seatsync.local}, which is enough to correlate a support
 * ticket without writing personal data to every log line.
 */
public final class EmailMasker {

    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@");

    private EmailMasker() {
    }

    /** Masks one address. */
    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "<none>";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** Masks every address-looking token inside free text (payload excerpts). */
    public static String maskAll(String text) {
        return text == null ? null : EMAIL.matcher(text).replaceAll("$1***@");
    }
}
