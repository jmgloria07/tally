package app.tally.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class LogRepliesTest {

    private static final MessageContext SENT = new MessageContext(LocalDateTime.of(2026, 11, 22, 8, 30),
            ZoneId.of("Asia/Manila"));

    private final StrictLogParser parser = new StrictLogParser();

    @Test
    void loggedReplyEchoesTheEntriesInTheStrictFormat() {
        ParsedLog log = parser.parse("""
                date: 2026-11-21
                meal: Oats | 420 | 32/60/8 | breakfast
                meal: Rice | 205
                meal: Soup | 90 | 1/2/3 looks like macros? no, notes
                lift: Squat | 3x5@100, 3x110 | felt strong
                lift: Pull-up | 8,8,6
                weight: 72.4 | BF: 17.5%
                note: legs
                """, SENT);

        assertThat(LogReplies.logged(log)).isEqualTo("""
                Logged:
                ```
                date: 2026-11-21
                meal: Oats | 420 | 32/60/8 | breakfast
                meal: Rice | 205
                meal: Soup | 90 | 0/0/0 | 1/2/3 looks like macros? no, notes
                lift: Squat | 3x5@100, 3x110 | felt strong
                lift: Pull-up | 8, 8, 6
                weight: 72.4 | BF: 17.5%
                note: legs
                ```""");
    }

    @Test
    void loggedReplyParsesBackToTheSameEntries() {
        ParsedLog original = parser.parse(LogReplies.HELP_EXAMPLE + "\nmeal: Tea | 5 | 0/0/0 | 1/2/3", SENT);
        String reply = LogReplies.logged(original);
        String body = reply.substring(reply.indexOf("```") + 3, reply.lastIndexOf("```"));

        ParsedLog reparsed = parser.parse(body, SENT);

        assertThat(reparsed).isEqualTo(original);
    }

    @Test
    void longRepliesAreTruncatedButCountWhatIsLeftOut() {
        String text = ("meal: " + "a".repeat(150) + " | 100\n").repeat(30);
        ParsedLog log = parser.parse(text, SENT);

        String reply = LogReplies.logged(log);

        assertThat(reply.length()).isLessThanOrEqualTo(LogReplies.MAX_LENGTH);
        assertThat(reply).contains("```\n…and ").endsWith(" more lines not shown. Everything was logged.");
        int shown = reply.split("\nmeal: ", -1).length - 1;
        assertThat(reply).contains("…and " + (30 - shown) + " more lines");
    }

    @Test
    void invalidReplyEndsWithTheHelpHint() {
        assertThat(LogReplies.invalid("Line 2: Calories is required"))
                .isEqualTo("Line 2: Calories is required\nSend `help` for the format.");
    }

    @Test
    void helpFitsInOneMessageAndContainsTheExample() {
        assertThat(LogReplies.HELP.length()).isLessThanOrEqualTo(LogReplies.MAX_LENGTH);
        assertThat(LogReplies.HELP).contains(LogReplies.HELP_EXAMPLE);
    }
}
