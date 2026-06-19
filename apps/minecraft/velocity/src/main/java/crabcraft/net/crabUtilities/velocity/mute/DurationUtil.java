package crabcraft.net.crabUtilities.velocity.mute;

import java.util.concurrent.TimeUnit;

/**
 * Parses and renders short human duration strings used by the mute
 * commands. Supported units: {@code s} (seconds), {@code m} (minutes),
 * {@code h} (hours), {@code d} (days), {@code w} (weeks).
 */
public final class DurationUtil {

    private DurationUtil() {}

    /**
     * Parses a duration like {@code 30m}, {@code 2h}, {@code 7d},
     * {@code 1w} into milliseconds.
     *
     * @return duration in millis, always {@code > 0}
     * @throws IllegalArgumentException if the input is blank, malformed,
     *         or uses an unknown unit
     */
    public static long parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Empty duration");
        }
        String s = input.trim().toLowerCase();
        char unit = s.charAt(s.length() - 1);
        String numberPart = s.substring(0, s.length() - 1);
        long amount;
        try {
            amount = Long.parseLong(numberPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid duration: " + input);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Duration must be positive: " + input);
        }
        return switch (unit) {
            case 's' -> TimeUnit.SECONDS.toMillis(amount);
            case 'm' -> TimeUnit.MINUTES.toMillis(amount);
            case 'h' -> TimeUnit.HOURS.toMillis(amount);
            case 'd' -> TimeUnit.DAYS.toMillis(amount);
            case 'w' -> TimeUnit.DAYS.toMillis(amount * 7L);
            default -> throw new IllegalArgumentException("Unknown duration unit: " + unit);
        };
    }

    /**
     * Renders a remaining-time span in millis to a compact human string
     * like {@code 2d 3h 15m}. Returns {@code "0s"} for non-positive
     * input.
     */
    public static String humanize(long millis) {
        if (millis <= 0) return "0s";

        long totalSeconds = millis / 1000L;
        long weeks = totalSeconds / (7 * 24 * 3600);
        totalSeconds %= 7 * 24 * 3600;
        long days = totalSeconds / (24 * 3600);
        totalSeconds %= 24 * 3600;
        long hours = totalSeconds / 3600;
        totalSeconds %= 3600;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (weeks > 0) sb.append(weeks).append("w ");
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 && weeks == 0 && days == 0) sb.append(seconds).append("s ");

        String result = sb.toString().trim();
        return result.isEmpty() ? "0s" : result;
    }
}
