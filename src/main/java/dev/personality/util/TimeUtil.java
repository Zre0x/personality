package dev.personality.util;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Utility methods for formatting time values.
 */
public final class TimeUtil {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd.MM.yyyy");

    private TimeUtil() {}

    /**
     * Converts a Minecraft tick count (20 ticks = 1 second) to a human-readable string.
     *
     * <p>Examples: {@code 72000} → {@code "1h 0m"}, {@code 400} → {@code "20s"}</p>
     */
    public static String formatTicks(long ticks) {
        long totalSeconds = ticks / 20L;
        long seconds      = totalSeconds % 60L;
        long totalMinutes = totalSeconds / 60L;
        long minutes      = totalMinutes % 60L;
        long totalHours   = totalMinutes / 60L;
        long hours        = totalHours   % 24L;
        long days         = totalHours   / 24L;

        if (days    > 0) return days    + "d " + hours   + "h " + minutes + "m";
        if (hours   > 0) return hours   + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    /**
     * Formats an epoch-millisecond timestamp as {@code dd.MM.yyyy}.
     */
    public static String formatTimestamp(long epochMillis) {
        return DATE_FORMAT.format(new Date(epochMillis));
    }
}
