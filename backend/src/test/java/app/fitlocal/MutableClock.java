package app.fitlocal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** A clock tests can pin and move. */
public class MutableClock extends Clock {

    public static final LocalDateTime DEFAULT_NOW = LocalDateTime.of(2026, 11, 20, 12, 0, 0);

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableClock(ZoneId zone) {
        this.zone = zone;
        reset();
    }

    public void reset() {
        instant = DEFAULT_NOW.atZone(zone).toInstant();
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
