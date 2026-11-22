package app.tally.ingest.discord;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DiscordSettingsTest {

    private static DiscordSettings settings(String token, String channel, String users, String startAfter,
            String zone) {
        return new DiscordSettings(token, channel, users, 14, startAfter, Duration.ofMinutes(15), zone);
    }

    @Test
    void noTokenMeansOff() {
        assertThat(settings("", "", "", "", "").enabled()).isFalse();
        assertThat(settings("  ", "1", "2", "", "").enabled()).isFalse();
    }

    @Test
    void completeConfigurationHasNoProblem() {
        DiscordSettings s = settings(" tok ", " 999 ", " 111, 222 ,", "2026-11-01", "Asia/Manila");

        assertThat(s.enabled()).isTrue();
        assertThat(s.problem()).isEmpty();
        assertThat(s.token()).isEqualTo("tok");
        assertThat(s.channelId()).isEqualTo("999");
        assertThat(s.allowedUserIdSet()).containsExactlyInAnyOrder("111", "222");
        assertThat(s.startAfterDate()).contains(LocalDate.of(2026, 11, 1));
        assertThat(s.zone()).isEqualTo(ZoneId.of("Asia/Manila"));
    }

    @Test
    void blankTimezoneIsTheSystemZone() {
        assertThat(settings("t", "1", "2", "", "").zone()).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    void incompleteOrBadValuesAreReportedNotThrown() {
        assertThat(settings("t", "", "111", "", "").problem()).contains(
                "tally.ingest.discord.channel-id must be a Discord channel ID (digits)");
        assertThat(settings("t", "999", " , ", "", "").problem()).contains(
                "tally.ingest.discord.allowed-user-ids must list at least one Discord user ID");
        assertThat(settings("t", "999", "me", "", "").problem()).contains(
                "tally.ingest.discord.allowed-user-ids must be comma-separated Discord user IDs");
        assertThat(settings("t", "999", "111", "last week", "").problem()).contains(
                "tally.ingest.discord.start-after must be a date (YYYY-MM-DD)");
        assertThat(settings("t", "999", "111", "", "Manila").problem()).contains(
                "tally.ingest.timezone must be an IANA zone, e.g. Asia/Manila");
        assertThat(new DiscordSettings("t", "999", "111", 0, "", Duration.ofMinutes(15), "").problem()).contains(
                "tally.ingest.discord.lookback-days must be at least 1");
        assertThat(new DiscordSettings("t", "999", "111", 14, "", Duration.ofSeconds(5), "").problem()).contains(
                "tally.ingest.discord.rescan-interval must be at least 1m");
    }
}
