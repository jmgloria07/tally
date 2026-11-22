package app.tally.ingest.discord;

import app.tally.common.Checks;
import app.tally.common.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

/**
 * The {@code tally.ingest.*} settings (discord-ingest spec §7). Defaults are declared in the committed
 * {@code application.properties}; the token, channel and allow-list come from {@code secrets.properties} (E12).
 *
 * <p>Every value is bound as a raw string and only parsed by {@link #problem()} and the accessors. A typo such as
 * {@code lookback-days=abc} must turn the bot off with a clear message, never stop the app from starting (E3), and
 * a typed binding would fail while the context loads, even with no token set.
 */
@Component
public class DiscordSettings {

    private static final Duration MIN_RESCAN = Duration.ofMinutes(1);

    private final String token;
    private final String channelId;
    private final String allowedUserIds;
    private final String lookbackDays;
    private final String startAfter;
    private final String rescanInterval;
    private final String timezone;

    @Autowired
    public DiscordSettings(
            @Value("${tally.ingest.discord.token:}") String token,
            @Value("${tally.ingest.discord.channel-id:}") String channelId,
            @Value("${tally.ingest.discord.allowed-user-ids:}") String allowedUserIds,
            @Value("${tally.ingest.discord.lookback-days:14}") String lookbackDays,
            @Value("${tally.ingest.discord.start-after:}") String startAfter,
            @Value("${tally.ingest.discord.rescan-interval:15m}") String rescanInterval,
            @Value("${tally.ingest.timezone:}") String timezone) {
        this.token = clean(token);
        this.channelId = clean(channelId);
        this.allowedUserIds = allowedUserIds == null ? "" : allowedUserIds;
        this.lookbackDays = clean(lookbackDays);
        this.startAfter = clean(startAfter);
        this.rescanInterval = clean(rescanInterval);
        this.timezone = clean(timezone);
    }

    /** For tests: typed lookback and rescan values. */
    DiscordSettings(String token, String channelId, String allowedUserIds, int lookbackDays, String startAfter,
            Duration rescanInterval, String timezone) {
        this(token, channelId, allowedUserIds, Integer.toString(lookbackDays), startAfter,
                rescanInterval == null ? "" : rescanInterval.toString(), timezone);
    }

    /** No token: the bot is simply off (E3). */
    public boolean enabled() {
        return !token.isEmpty();
    }

    /** Why the configuration can't be used, or empty when it can. Only meaningful when {@link #enabled()}. */
    public Optional<String> problem() {
        if (!isSnowflake(channelId)) {
            return Optional.of("tally.ingest.discord.channel-id must be a Discord channel ID (digits)");
        }
        Set<String> users = allowedUserIdSet();
        if (users.isEmpty()) {
            return Optional.of("tally.ingest.discord.allowed-user-ids must list at least one Discord user ID");
        }
        if (!users.stream().allMatch(DiscordSettings::isSnowflake)) {
            return Optional.of("tally.ingest.discord.allowed-user-ids must be comma-separated Discord user IDs");
        }
        try {
            lookbackDays();
            rescanInterval();
            zone();
            startAfterDate();
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
        return Optional.empty();
    }

    public String token() {
        return token;
    }

    public String channelId() {
        return channelId;
    }

    public Set<String> allowedUserIdSet() {
        return Arrays.stream(allowedUserIds.split(","))
                .map(String::strip)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** @throws IllegalArgumentException unless a whole number ≥ 1 */
    public int lookbackDays() {
        try {
            int days = Integer.parseInt(lookbackDays);
            if (days >= 1) {
                return days;
            }
        } catch (NumberFormatException e) {
            // falls through to the message below
        }
        throw new IllegalArgumentException("tally.ingest.discord.lookback-days must be a whole number of at least 1");
    }

    /** @throws IllegalArgumentException unless a date */
    public Optional<LocalDate> startAfterDate() {
        if (startAfter.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Checks.date(startAfter, "start-after"));
        } catch (ValidationException e) {
            throw new IllegalArgumentException("tally.ingest.discord.start-after must be a date (YYYY-MM-DD)");
        }
    }

    /**
     * Start of the scan window (E1): {@code lookback-days} before {@code now}, or the start of {@code start-after}
     * in the configured timezone if that is later.
     */
    public Instant windowStart(Instant now) {
        Instant lookback = now.minus(Duration.ofDays(lookbackDays()));
        return startAfterDate()
                .map(date -> date.atStartOfDay(zone()).toInstant())
                .filter(start -> start.isAfter(lookback))
                .orElse(lookback);
    }

    /** Spring duration syntax ({@code 15m}, {@code 1h}, {@code PT15M}). @throws IllegalArgumentException unless ≥ 1m */
    public Duration rescanInterval() {
        Duration interval;
        try {
            interval = DurationStyle.detectAndParse(rescanInterval);
        } catch (IllegalArgumentException e) {
            interval = null;
        }
        if (interval == null || interval.compareTo(MIN_RESCAN) < 0) {
            throw new IllegalArgumentException("tally.ingest.discord.rescan-interval must be a duration of at least 1m");
        }
        return interval;
    }

    /** Blank means the system timezone (§7). @throws IllegalArgumentException unless an IANA zone */
    public ZoneId zone() {
        if (timezone.isEmpty()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("tally.ingest.timezone must be an IANA zone, e.g. Asia/Manila");
        }
    }

    /** Digits that fit Discord's 64-bit unsigned snowflake, so Discord4J can parse them. */
    private static boolean isSnowflake(String id) {
        if (!id.matches("\\d+")) {
            return false;
        }
        try {
            Long.parseUnsignedLong(id);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
