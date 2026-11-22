package app.tally.ingest.discord;

import static org.assertj.core.api.Assertions.assertThat;

import app.tally.IntegrationTest;
import app.tally.TallyApplication;
import app.tally.ingest.IngestService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Startup gating (E3) and secrets loading (E12). */
class DiscordIngestWiringTests extends IntegrationTest {

    @Autowired
    private DiscordIngestRunner runner;

    @Autowired
    private IngestService ingestService;

    @Test
    void withoutATokenTheAppStartsWithIngestionOff() {
        assertThat(runner.isRunning()).isFalse();
    }

    @Test
    void anIncompleteConfigurationLeavesIngestionOff() {
        List<DiscordSettings> created = new ArrayList<>();
        DiscordSettings incomplete = new DiscordSettings("token", "", "111", 14, "", Duration.ofMinutes(15), "");
        DiscordIngestRunner gated = new DiscordIngestRunner(incomplete, ingestService, clock, s -> {
            created.add(s);
            return new FakeDiscordPort();
        });

        gated.start();

        assertThat(gated.isRunning()).isFalse();
        assertThat(created).isEmpty();
    }

    @Test
    void aCompleteConfigurationStartsThePortAndStopsItOnShutdown() throws Exception {
        FakeDiscordPort port = new FakeDiscordPort();
        DiscordSettings complete = new DiscordSettings("token", "999", "111", 14, "", Duration.ofMinutes(15), "");
        DiscordIngestRunner started = new DiscordIngestRunner(complete, ingestService, clock, s -> port);

        started.start();

        assertThat(started.isRunning()).isTrue();
        assertThat(port.listener).isInstanceOf(IngestWorker.class);

        started.destroy();

        assertThat(port.stopped).isTrue();
        assertThat(started.isRunning()).isFalse();
    }

    @Test
    void secretsPropertiesOverrideTheCommittedDefaults() throws Exception {
        Path secrets = newDataDir().resolve("secrets.properties");
        Files.writeString(secrets, "tally.ingest.discord.channel-id=999\ntally.ingest.discord.lookback-days=3\n");
        try (ConfigurableApplicationContext cli = cliContext(
                "--TALLY_SECRETS_FILE=" + secrets.toString().replace('\\', '/'))) {
            DiscordSettings settings = cli.getBean(DiscordSettings.class);
            assertThat(settings.channelId()).isEqualTo("999");
            assertThat(settings.lookbackDays()).isEqualTo(3);
            assertThat(settings.rescanInterval()).isEqualTo(Duration.ofMinutes(15)); // committed default
        }
    }

    @Test
    void cliModeHasNoDiscordRunner() {
        try (ConfigurableApplicationContext cli = cliContext()) {
            assertThat(cli.getBeanNamesForType(DiscordIngestRunner.class)).isEmpty();
        }
    }

    /**
     * A non-web context like the CLI subcommands get. Settings go in as command-line arguments: Spring's
     * "default properties" rank below application.properties, which would point the data dir at ./data.
     */
    private static ConfigurableApplicationContext cliContext(String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--tally.data-dir=" + newDataDir().toString().replace('\\', '/'),
                "--tally.ingest.discord.token="));
        args.addAll(List.of(extraArgs));
        return new SpringApplicationBuilder(TallyApplication.class)
                .web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }
}
