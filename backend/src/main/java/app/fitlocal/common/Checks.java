package app.fitlocal.common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Hand-written request validation (spec I6). Every method trims or normalizes its input and throws
 * {@link ValidationException} with a message that names the field in plain words.
 */
public final class Checks {

    public static final int MAX_LIST_LIMIT = 500;

    private Checks() {
    }

    /** Trimmed text; {@code null} becomes "". */
    public static String text(String value, String label, int maxLength, boolean required) {
        String trimmed = value == null ? "" : value.strip();
        if (required && trimmed.isEmpty()) {
            throw new ValidationException(label + " is required");
        }
        if (trimmed.length() > maxLength) {
            throw new ValidationException(label + " must be " + maxLength + " characters or fewer");
        }
        return trimmed;
    }

    public static LocalDate date(String value, String label) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            throw new ValidationException(label + " is required");
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new ValidationException(label + " must be a valid date (YYYY-MM-DD)");
        }
    }

    /**
     * A number in [min, max]. When {@code value} is null, returns {@code defaultValue}, or fails if there is
     * no default (the field is required).
     */
    public static double number(Double value, String label, double min, double max, Double defaultValue) {
        if (value == null) {
            if (defaultValue == null) {
                throw new ValidationException(label + " is required");
            }
            return defaultValue;
        }
        if (value.isNaN() || value.isInfinite() || value < min || value > max) {
            throw new ValidationException(label + " must be between " + plain(min) + " and " + plain(max));
        }
        return value;
    }

    /** A whole number in [min, max]. {@code where} is appended to the message, e.g. " (exercise 2, set 3)". */
    public static int wholeNumber(Double value, String label, int min, int max, String where) {
        if (value == null || value.isNaN() || value.isInfinite() || value != Math.rint(value)
                || value < min || value > max) {
            throw new ValidationException(
                    label + " must be a whole number between " + min + " and " + max + where);
        }
        return value.intValue();
    }

    /** Optional UUID; blank becomes {@code null}. */
    public static String optionalUuid(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        try {
            UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(label + " must be a UUID");
        }
        return trimmed.toLowerCase();
    }

    public static int clampLimit(Integer limit, int defaultLimit) {
        int value = limit == null ? defaultLimit : limit;
        return Math.max(1, Math.min(MAX_LIST_LIMIT, value));
    }

    private static String plain(double number) {
        return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
    }
}
