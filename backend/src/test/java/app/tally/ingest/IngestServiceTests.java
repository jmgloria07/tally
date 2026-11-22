package app.tally.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.tally.IntegrationTest;
import app.tally.ingest.IngestOutcome.Kind;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

class IngestServiceTests extends IntegrationTest {

    private static final MessageContext SENT = new MessageContext(LocalDateTime.of(2026, 11, 20, 8, 30),
            ZoneId.systemDefault());

    private static final String MIXED = """
            meal: Oats | 420 | 32/60/8 | breakfast
            meal: Rice | 205
            lift: Squat | 3x5@100
            lift: Pull-up | 8,8,6
            weight: 72.4
            note: legs
            """;

    @Autowired
    private IngestService ingestService;

    @AfterEach
    void dropFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_ledger");
    }

    @Test
    void logsEveryEntryAndTheLedgerRowTogether() {
        IngestOutcome outcome = ingestService.ingest("1001", MIXED, SENT);

        assertThat(outcome.kind()).isEqualTo(Kind.LOGGED);
        assertThat(outcome.reply()).startsWith("Logged:\n```\ndate: 2026-11-20\nmeal: Oats");
        assertThat(count("meals")).isEqualTo(2);
        assertThat(count("workouts")).isEqualTo(1);
        assertThat(count("workout_exercises")).isEqualTo(2);
        assertThat(count("workout_sets")).isEqualTo(6);
        assertThat(count("weigh_ins")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT notes FROM workouts", String.class)).isEqualTo("legs");
        assertThat(jdbc.queryForObject("SELECT meal_date FROM meals LIMIT 1", String.class)).isEqualTo("2026-11-20");
        assertThat(jdbc.queryForMap("SELECT * FROM ingested_messages"))
                .containsEntry("message_id", "1001")
                .containsEntry("ingested_at", "2026-11-20T12:00:00");
    }

    @Test
    void aMessageAlreadyInTheLedgerIsNotLoggedAgain() {
        ingestService.ingest("1001", MIXED, SENT);

        IngestOutcome again = ingestService.ingest("1001", MIXED, SENT);

        assertThat(again).isEqualTo(new IngestOutcome(Kind.ALREADY_LOGGED, "Already logged."));
        assertThat(count("meals")).isEqualTo(2);
        assertThat(count("workouts")).isEqualTo(1);
        assertThat(count("ingested_messages")).isEqualTo(1);
    }

    @Test
    void anInvalidMessageInsertsNothing() {
        IngestOutcome outcome = ingestService.ingest("1002", "meal: Oats | 420\nmeal: Rice", SENT);

        assertThat(outcome.kind()).isEqualTo(Kind.INVALID);
        assertThat(outcome.reply())
                .isEqualTo("Line 2: meal: needs at least food | kcal\nSend `help` for the format.");
        assertThat(count("meals")).isZero();
        assertThat(count("ingested_messages")).isZero();
    }

    @Test
    void aDatabaseErrorRollsBackEverythingAndLeavesTheMessagePending() {
        jdbc.execute("CREATE TRIGGER fail_ledger BEFORE INSERT ON ingested_messages "
                + "BEGIN SELECT RAISE(ABORT, 'simulated failure'); END");

        assertThatThrownBy(() -> ingestService.ingest("1003", MIXED, SENT)).isInstanceOf(DataAccessException.class);

        assertThat(count("meals")).isZero();
        assertThat(count("workouts")).isZero();
        assertThat(count("weigh_ins")).isZero();
        assertThat(count("ingested_messages")).isZero();

        dropFailureTrigger();
        assertThat(ingestService.ingest("1003", MIXED, SENT).kind()).isEqualTo(Kind.LOGGED);
        assertThat(count("meals")).isEqualTo(2);
    }

    @Test
    void helpRepliesWithTheFormatAndInsertsNothing() {
        for (String text : new String[] {"help", "HELP", "  Help \n"}) {
            assertThat(ingestService.ingest("1004", text, SENT))
                    .isEqualTo(new IngestOutcome(Kind.HELP, LogReplies.HELP));
        }
        assertThat(count("ingested_messages")).isZero();
        assertThat(count("meals")).isZero();
    }

    @Test
    void helpWithOtherTextIsParsedAsALog() {
        IngestOutcome outcome = ingestService.ingest("1005", "help me log this", SENT);

        assertThat(outcome.kind()).isEqualTo(Kind.INVALID);
        assertThat(outcome.reply()).endsWith("Send `help` for the format.");
    }

    @Test
    void commentsAndBlankMessagesAreIgnored() {
        assertThat(ingestService.ingest("1006", "// meal: Oats | 420", SENT).kind()).isEqualTo(Kind.IGNORED);
        assertThat(ingestService.ingest("1007", "  //oops", SENT).reply()).isNull();
        assertThat(ingestService.ingest("1008", " \n ", SENT).kind()).isEqualTo(Kind.IGNORED);
        assertThat(count("meals")).isZero();
    }

    @Test
    void lineNumbersCountLeadingBlankLines() {
        IngestOutcome outcome = ingestService.ingest("1009", "\n\nmeal: Oats", SENT);

        assertThat(outcome.reply()).startsWith("Line 3:");
    }
}
