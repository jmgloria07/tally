package app.tally.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Fails the build when an example message in the README's "Logging from Discord" section no longer parses
 * (discord-ingest E15: the README shows examples and points to {@code help}). It checks that the examples are
 * valid, not that their wording matches the help reply.
 */
class ReadmeExamplesTest {

    /** Tests run with {@code backend/} as the working directory. */
    private static final Path README = Path.of("..", "README.md");

    /** Plain fenced blocks only: language-tagged ones (e.g. {@code ```properties}) are configuration, not messages. */
    private static final Pattern PLAIN_CODE_BLOCK = Pattern.compile("(?m)^```\\R(.*?)^```", Pattern.DOTALL);

    private static final MessageContext SENT = new MessageContext(LocalDateTime.of(2026, 11, 22, 8, 30),
            ZoneId.of("Asia/Manila"));

    @Test
    void everyExampleMessageInTheDiscordSectionParses() throws Exception {
        List<String> examples = examples(discordSection(Files.readString(README)));

        assertThat(examples).as("example messages under '## Logging from Discord' in README.md").isNotEmpty();
        StrictLogParser parser = new StrictLogParser();
        for (String example : examples) {
            assertThatCode(() -> parser.parse(example, SENT))
                    .as("README example no longer parses; update README.md:%n%s", example)
                    .doesNotThrowAnyException();
        }
    }

    private static String discordSection(String readme) {
        int start = readme.indexOf("## Logging from Discord");
        assertThat(start).as("README.md has a '## Logging from Discord' section").isNotNegative();
        int end = readme.indexOf("\n## ", start + 1);
        return end < 0 ? readme.substring(start) : readme.substring(start, end);
    }

    private static List<String> examples(String section) {
        List<String> blocks = new ArrayList<>();
        Matcher m = PLAIN_CODE_BLOCK.matcher(section);
        while (m.find()) {
            blocks.add(m.group(1));
        }
        return blocks;
    }
}
