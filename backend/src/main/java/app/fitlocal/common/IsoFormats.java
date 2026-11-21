package app.fitlocal.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * The single place that decides how dates and timestamps look as text: in the database, in JSON and in
 * the coach context. ISO text sorts correctly, so SQL range queries and ORDER BY work on it.
 */
public final class IsoFormats {

    /** Local timestamp with seconds precision, e.g. {@code 2026-11-04T17:58:22}. */
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private IsoFormats() {
    }

    public static String date(LocalDate date) {
        return date == null ? null : date.toString();
    }

    public static String dateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.format(DATE_TIME);
    }

    public static LocalDateTime parseDateTime(String text) {
        return text == null ? null : LocalDateTime.parse(text).truncatedTo(ChronoUnit.SECONDS);
    }
}
