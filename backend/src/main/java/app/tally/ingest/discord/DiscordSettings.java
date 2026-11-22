package app.tally.ingest.discord;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The {@code tally.ingest.*} settings (discord-ingest spec §7). Defaults are declared in the committed
 * {@code application.properties}; the token, channel and allow-list come from {@code secrets.properties} (E12).
 * Raw strings are kept so a bad value turns the bot off with a clear message instead of failing startup (E3).
 */
@Component
public class DiscordSettings {

    private final String token;
    private final String channelId;
    private final String allowedUserIds;
    private final int lookbackDays;
    private final String startAfter;
    private final Duration rescanInterval;
    private final String timezone;

    public DiscordSettings(
            @Value("${tally.ingest.discord.token:}") String token,
            @Value("${tally.ingest.discord.channel-id:}") String channelId,
            @Value("${tally.ingest.discord.allowed-user-ids:}") String allowedUserIds,
            @Value("${tally.ingest.discord.lookback-days:14}") int lookbackDays,
            @Value("${tally.ingest.discord.start-after:}") String startAfter,
            @Value("${tally.ingest.discord.rescan-interval:15m}") Duration rescanInterval,
            @Value("${tally.ingest.timezone:}") String timezone) {
        this.token = token == null ? "" : token.strip();
        this.channelId = channelId == null ? "" : channelId.strip();
        this.allowedUserIds = allowedUserIds == null ? "" : allowedUserIds;
        this.lookbackDays = lookbackDays;
        this.startAfter = startAfter == null ? "" : startAfter.strip();
        this.rescanInterval = rescanInterval;
        this.timezone = timezone == null ? "" : timezone.strip();
    }

    /** No token: the bot is simply off (E3). */
    public boolean enabled() {
        return !token.isEmpty();
    }

    /** Why the configuration can't be used, or empty when it can. Only meaningful when {@link #enabled()}. */
    public Optional<String> problem() {
        if (!channelId.matches("\\d+")) {
            return Optional.of("tally.ingest.discord.channel-id must be a Discord channel ID (digits)");
        }
        if (allowedUserIdSet().isEmpty()) {
            return Optional.of("tally.ingest.discord.allowed-user-ids must list at least one Discord user ID");
        }
        if (allowedUserIdSet().stream().anyMatch(id -> !id.matches("\\d+"))) {
            return Optional.of("tally.ingest.discord.allowed-user-ids must be comma-separated Discord user IDs");
        }
        if (lookbackDays < 1) {
            return Optional.of("tally.ingest.discord.lookback-days must be at least 1");
        }
        if (rescanInterval == null || rescanInterval.compareTo(Duration.ofMinutes(1)) < 0) {
            return Optional.of("tally.ingest.discord.rescan-interval must be at least 1m");
        }
        try {
            zone();
            startAfterDate();
        } catch (RuntimeException e) {
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

    public int lookbackDays() {
        return lookbackDays;
    }

    public Optional<LocalDate> startAfterDate() {
        if (startAfter.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(startAfter));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("tally.ingest.discord.start-after must be a date (YYYY-MM-DD)");
        }
    }

    public Duration rescanInterval() {
        return rescanInterval;
    }

    /** Blank means the system timezone (§7). */
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
}
