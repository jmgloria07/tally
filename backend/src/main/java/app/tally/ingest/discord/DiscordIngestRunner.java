package app.tally.ingest.discord;

import app.tally.ingest.IngestService;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Starts Discord ingestion once the web app is up, if configured (see E3). Web mode only: CLI subcommands run
 * without a web application, so this bean doesn't exist there. Nothing here can fail startup; a missing token
 * keeps the feature off and a bad configuration is logged once.
 */
@Component
@ConditionalOnWebApplication
public class DiscordIngestRunner implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DiscordIngestRunner.class);

    private final DiscordSettings settings;
    private final IngestService ingestService;
    private final Clock clock;
    private final Function<DiscordSettings, DiscordPort> portFactory;

    private DiscordPort port;
    private IngestWorker worker;
    private ExecutorService processor;
    private ScheduledExecutorService scheduler;

    @Autowired
    public DiscordIngestRunner(DiscordSettings settings, IngestService ingestService, Clock clock) {
        this(settings, ingestService, clock, s -> new Discord4jPort(s.token(), s.channelId()));
    }

    /** For tests: a fake port instead of Discord4J. */
    DiscordIngestRunner(DiscordSettings settings, IngestService ingestService, Clock clock,
            Function<DiscordSettings, DiscordPort> portFactory) {
        this.settings = settings;
        this.ingestService = ingestService;
        this.clock = clock;
        this.portFactory = portFactory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (port != null) {
            return;
        }
        if (!settings.enabled()) {
            log.info("Discord ingestion is off (no tally.ingest.discord.token)");
            return;
        }
        Optional<String> problem = settings.problem();
        if (problem.isPresent()) {
            log.error("Discord ingestion is off: {}", problem.get());
            return;
        }
        processor = Executors.newSingleThreadExecutor(daemon("discord-ingest"));
        scheduler = Executors.newSingleThreadScheduledExecutor(daemon("discord-scan"));
        port = portFactory.apply(settings);
        worker = new IngestWorker(port, ingestService, processor, clock, settings,
                () -> scheduler.execute(worker::scan));
        long every = settings.rescanInterval().toSeconds();
        // Before the first connection a periodic scan just fails its history call and logs a warning.
        scheduler.scheduleWithFixedDelay(worker::scan, every, every, TimeUnit.SECONDS);
        port.start(worker); // connects in the background; ReadyEvent triggers the first scan
        log.info("Discord ingestion starting (rescan every {})", settings.rescanInterval());
    }

    public synchronized boolean isRunning() {
        return port != null;
    }

    @Override
    public synchronized void destroy() {
        if (port == null) {
            return;
        }
        scheduler.shutdownNow();
        processor.shutdown();
        try {
            port.stop();
        } catch (RuntimeException e) {
            log.warn("Discord logout failed ({})", e.getClass().getSimpleName());
        }
        try {
            processor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        port = null;
        worker = null;
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
