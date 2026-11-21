package app.fitlocal.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "Today" is the server's local calendar date (spec I3). Everything time-dependent takes this injectable
 * clock so tests can pin or move time.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock);
    }

    /** Current local time, truncated to seconds like every stored timestamp. */
    public static LocalDateTime now(Clock clock) {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
