package app.tally.ingest.discord;

import static org.assertj.core.api.Assertions.assertThat;

import app.tally.IntegrationTest;
import app.tally.ingest.IngestService;
import app.tally.ingest.LogReplies;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The message lifecycle (discord-ingest §3.1) and edge cases (§9) against a fake channel and the real DB. */
class IngestWorkerTests extends IntegrationTest {

    private static final String ME = "111";
    private static final String MEAL = "meal: Oats | 420";

    @Autowired
    private IngestService ingestService;

    private FakeDiscordPort discord;
    private IngestWorker worker;
    private Instant now;

    @BeforeEach
    void setUp() {
        discord = new FakeDiscordPort();
        now = clock.instant();
        worker = worker(settings("14", ""), Runnable::run);
    }

    @AfterEach
    void dropFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_ledger");
    }

    private IngestWorker worker(DiscordSettings settings, Executor executor) {
        return new IngestWorker(discord, ingestService, executor, clock, settings, () -> worker.scan());
    }

    private static DiscordSettings settings(String lookbackDays, String startAfter) {
        return new DiscordSettings("token", "999", ME + ", 222", Integer.parseInt(lookbackDays), startAfter,
                Duration.ofMinutes(15), ZoneId.systemDefault().getId());
    }

    private Instant minutesAgo(long minutes) {
        return now.minus(Duration.ofMinutes(minutes));
    }

    @Test
    void scanLogsPendingMessagesOldestFirstWithReactionsAndReplies() {
        discord.post("2", ME, "meal: Rice | 205", minutesAgo(5));
        discord.post("1", ME, MEAL, minutesAgo(10));

        worker.scan();

        assertThat(jdbc.queryForList("SELECT food FROM meals ORDER BY id", String.class)).containsExactly("Oats", "Rice");
        assertThat(discord.reactionsOn("1")).containsExactly("✅");
        assertThat(discord.reactionLog).containsExactly("+👀 1", "+✅ 1", "-👀 1", "+👀 2", "+✅ 2", "-👀 2");
        assertThat(discord.repliesTo("1")).singleElement().asString().startsWith("Logged:\n```\ndate: ");
    }

    @Test
    void processedMessagesAreSkippedAndAScanIsIdempotent() {
        discord.post("1", ME, MEAL, minutesAgo(10));
        worker.scan();
        worker.scan();

        assertThat(count("meals")).isEqualTo(1);
        assertThat(discord.replies).hasSize(1);
    }

    @Test
    void liveMessagesAreProcessedLikeScannedOnes() {
        discord.start(worker);
        discord.listener.onMessage(discord.post("1", ME, MEAL, now));

        assertThat(count("meals")).isEqualTo(1);
        assertThat(discord.reactionsOn("1")).containsExactly("✅");
    }

    @Test
    void connectingTriggersAScan() {
        discord.post("1", ME, MEAL, minutesAgo(1));
        discord.start(worker);

        discord.listener.onConnected();

        assertThat(count("meals")).isEqualTo(1);
    }

    @Test
    void onlyAllowListedHumansInTheChannelAreRead() {
        discord.post("1", "333", MEAL, minutesAgo(4));
        discord.put(new ChannelMessage("2", ME, true, false, MEAL, false, minutesAgo(3), Set.of()));
        discord.put(new ChannelMessage("3", ME, false, true, MEAL, false, minutesAgo(2), Set.of()));
        discord.put(new ChannelMessage("4", null, false, false, MEAL, false, minutesAgo(1), Set.of()));
        discord.post("5", ME, "// meal: Oats | 420", minutesAgo(1));

        worker.scan();

        assertThat(count("meals")).isZero();
        assertThat(discord.reactionLog).isEmpty();
        assertThat(discord.replies).isEmpty();
    }

    @Test
    void invalidMessagesGetACrossAndTheReason() {
        discord.post("1", ME, "meal: Oats", minutesAgo(1));

        worker.scan();

        assertThat(discord.reactionsOn("1")).containsExactly("❌");
        assertThat(discord.repliesTo("1"))
                .containsExactly("Line 1: meal: needs at least food | kcal\nSend `help` for the format.");
        assertThat(count("meals")).isZero();
    }

    @Test
    void helpGetsTheFormatAndACheckWithoutTheEyes() {
        discord.post("1", ME, "Help", minutesAgo(1));

        worker.scan();

        assertThat(discord.reactionLog).containsExactly("+✅ 1");
        assertThat(discord.repliesTo("1")).containsExactly(LogReplies.HELP);
    }

    @Test
    void attachmentsOnlyFailsAndEmptyTextIsLeftAlone() {
        discord.put(new ChannelMessage("1", ME, false, false, "", true, minutesAgo(2), Set.of()));
        discord.put(new ChannelMessage("2", ME, false, false, " ", false, minutesAgo(1), Set.of()));

        worker.scan();

        assertThat(discord.reactionsOn("1")).containsExactly("❌");
        assertThat(discord.repliesTo("1"))
                .containsExactly("Text only. Attachments are not supported.\nSend `help` for the format.");
        assertThat(discord.reactionsOn("2")).isEmpty();
        assertThat(discord.repliesTo("2")).isEmpty();
    }

    @Test
    void textWithAttachmentsIsParsed() {
        discord.put(new ChannelMessage("1", ME, false, false, MEAL, true, minutesAgo(1), Set.of()));

        worker.scan();

        assertThat(count("meals")).isEqualTo(1);
    }

    @Test
    void aMessageLeftWithOnlyTheEyesIsRetried() {
        discord.put(new ChannelMessage("1", ME, false, false, MEAL, false, minutesAgo(1), Set.of("👀")));

        worker.scan();

        assertThat(count("meals")).isEqualTo(1);
        assertThat(discord.reactionsOn("1")).containsExactly("✅");
    }

    @Test
    void aMessageInTheLedgerWithoutACheckIsMarkedNotRelogged() {
        discord.post("1", ME, MEAL, minutesAgo(1));
        discord.failReaction = "✅";
        worker.scan(); // logged, but the ✅ call failed

        assertThat(count("meals")).isEqualTo(1);
        assertThat(discord.reactionsOn("1")).isEmpty();

        discord.failReaction = null;
        worker.scan();

        assertThat(count("meals")).isEqualTo(1);
        assertThat(discord.reactionsOn("1")).containsExactly("✅");
        assertThat(discord.repliesTo("1")).last().isEqualTo("Already logged.");
    }

    @Test
    void aDatabaseErrorLeavesTheMessagePendingForTheNextScan() {
        jdbc.execute("CREATE TRIGGER fail_ledger BEFORE INSERT ON ingested_messages "
                + "BEGIN SELECT RAISE(ABORT, 'simulated failure'); END");
        discord.post("1", ME, MEAL, minutesAgo(1));

        worker.scan();

        assertThat(count("meals")).isZero();
        assertThat(discord.reactionsOn("1")).containsExactly("👀");
        assertThat(discord.replies).isEmpty();

        dropFailureTrigger();
        worker.scan();

        assertThat(count("meals")).isEqualTo(1);
        assertThat(discord.reactionsOn("1")).containsExactly("✅");
    }

    @Test
    void theScanWindowUsesLookbackAndStartAfter() {
        discord.post("old", ME, MEAL, now.minus(Duration.ofDays(15)));
        discord.post("recent", ME, "meal: Rice | 205", now.minus(Duration.ofDays(3)));
        discord.post("new", ME, "meal: Soup | 90", minutesAgo(1));

        worker = worker(settings("14", LocalDate.now(clock).minusDays(1).toString()), Runnable::run);
        worker.scan();

        assertThat(jdbc.queryForList("SELECT food FROM meals", String.class)).containsExactly("Soup");
        assertThat(discord.reactionsOn("old")).isEmpty();
        assertThat(discord.reactionsOn("recent")).isEmpty();

        worker = worker(settings("14", ""), Runnable::run);
        worker.scan();

        assertThat(jdbc.queryForList("SELECT food FROM meals ORDER BY id", String.class)).containsExactly("Soup", "Rice");
        assertThat(discord.reactionsOn("old")).isEmpty();
    }

    @Test
    void aFailedScanIsLoggedNotThrown() {
        discord.failHistory = true;

        worker.scan();

        assertThat(discord.replies).isEmpty();
    }

    @Test
    void aMessageQueuedTwiceIsProcessedOnce() {
        List<Runnable> queue = new ArrayList<>();
        worker = worker(settings("14", ""), queue::add);
        ChannelMessage message = discord.post("1", ME, MEAL, minutesAgo(1));

        worker.onMessage(message);
        worker.scan();
        worker.onMessage(message);
        queue.forEach(Runnable::run);

        assertThat(queue).hasSize(1);
        assertThat(count("meals")).isEqualTo(1);
    }

    @Test
    void theMessageDateIsTheLocalDateItWasSent() {
        discord.post("1", ME, MEAL, now.minus(Duration.ofDays(2)));

        worker.scan();

        assertThat(jdbc.queryForObject("SELECT meal_date FROM meals", String.class)).isEqualTo("2026-11-18");
    }
}
