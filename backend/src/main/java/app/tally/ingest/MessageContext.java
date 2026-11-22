package app.tally.ingest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * What a parser may know about a message besides its text (discord-ingest spec §3.2): when it was sent, as a
 * local date-time in the configured timezone.
 */
public record MessageContext(LocalDateTime sentAt, ZoneId zone) {

    public MessageContext {
        Objects.requireNonNull(sentAt, "sentAt");
        Objects.requireNonNull(zone, "zone");
    }

    public static MessageContext of(Instant sentAt, ZoneId zone) {
        return new MessageContext(LocalDateTime.ofInstant(sentAt, zone), zone);
    }

    /** The default log date, and the latest date a {@code date:} line may name (see E10). */
    public LocalDate date() {
        return sentAt.toLocalDate();
    }
}
