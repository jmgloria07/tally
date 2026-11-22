package app.tally.ingest.discord;

import app.tally.ingest.IngestOutcome;
import app.tally.ingest.IngestService;
import app.tally.ingest.LogReplies;
import app.tally.ingest.MessageContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides what happens to each channel message and turns ingestion outcomes into reactions and replies
 * (discord-ingest spec §3.1, E8, E9, E13). Blocking code only; Discord access goes through {@link DiscordPort}.
 *
 * <p>Messages are processed one at a time on {@code processor}, a single-thread executor in production (E4).
 * A message already queued or being processed is not queued again. Message text is never logged (§8).
 */
public class IngestWorker implements DiscordPort.Listener {

    static final String PROCESSING = "👀";
    static final String DONE = "✅";
    static final String FAILED = "❌";
    static final String TEXT_ONLY = "Text only. Attachments are not supported.";

    private static final Logger log = LoggerFactory.getLogger(IngestWorker.class);

    private final DiscordPort discord;
    private final IngestService ingestService;
    private final Executor processor;
    private final Clock clock;
    private final Set<String> allowedUserIds;
    private final ZoneId zone;
    private final int lookbackDays;
    private final Optional<LocalDate> startAfter;
    private final Runnable scanTrigger;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

    /**
     * @param scanTrigger how {@link #onConnected()} asks for a scan; production hands it to a scheduler thread so
     *                    the gateway thread isn't blocked by history calls, tests run it directly
     */
    public IngestWorker(DiscordPort discord, IngestService ingestService, Executor processor, Clock clock,
            DiscordSettings settings, Runnable scanTrigger) {
        this.discord = discord;
        this.ingestService = ingestService;
        this.processor = processor;
        this.clock = clock;
        this.allowedUserIds = settings.allowedUserIdSet();
        this.zone = settings.zone();
        this.lookbackDays = settings.lookbackDays();
        this.startAfter = settings.startAfterDate();
        this.scanTrigger = scanTrigger;
    }

    @Override
    public void onMessage(ChannelMessage message) {
        enqueue(message);
    }

    @Override
    public void onConnected() {
        scanTrigger.run();
    }

    /** One scan pass (E1, E4): every pending message in the window, oldest first. Never throws. */
    public void scan() {
        try {
            int queuedCount = 0;
            for (ChannelMessage message : discord.history(windowStart())) {
                if (isCandidate(message) && enqueue(message)) {
                    queuedCount++;
                }
            }
            log.info("Discord scan: {} pending message(s) queued", queuedCount);
        } catch (RuntimeException e) {
            log.warn("Discord scan failed ({}); will retry on the next pass", e.getClass().getSimpleName());
        }
    }

    /** Newer than {@code lookback-days} ago and, if set, on or after {@code start-after} (E1). */
    Instant windowStart() {
        Instant lookback = clock.instant().minus(Duration.ofDays(lookbackDays));
        if (startAfter.isEmpty()) {
            return lookback;
        }
        Instant start = startAfter.get().atStartOfDay(zone).toInstant();
        return start.isAfter(lookback) ? start : lookback;
    }

    private boolean enqueue(ChannelMessage message) {
        if (!queued.add(message.id())) {
            return false;
        }
        processor.execute(() -> {
            try {
                process(message);
            } finally {
                queued.remove(message.id());
            }
        });
        return true;
    }

    /** Allow-listed human author, and not yet marked ✅ or ❌ by the bot (E1, E13). */
    private boolean isCandidate(ChannelMessage message) {
        return !message.fromBot() && !message.fromWebhook() && message.authorId() != null
                && allowedUserIds.contains(message.authorId())
                && !message.ownReactions().contains(DONE) && !message.ownReactions().contains(FAILED);
    }

    void process(ChannelMessage message) {
        if (!isCandidate(message)) {
            return;
        }
        String id = message.id();
        try {
            if (message.content().isBlank()) {
                if (message.hasAttachments()) {
                    finish(id, FAILED, LogReplies.invalid(TEXT_ONLY)); // every ❌ reply ends with the help hint (E8)
                } else {
                    log.warn("Message {} from an allowed user has no text. Is the Message Content intent enabled "
                            + "for the bot in the Discord developer portal?", id);
                }
                return;
            }
            Optional<IngestOutcome> early = ingestService.precheck(id, message.content());
            IngestOutcome outcome;
            if (early.isPresent()) {
                outcome = early.get();
            } else {
                bestEffort(() -> discord.addReaction(id, PROCESSING), id, "add 👀");
                outcome = ingestService.ingest(id, message.content(), MessageContext.of(message.sentAt(), zone));
            }
            switch (outcome.kind()) {
                case IGNORED -> { }
                case INVALID -> finish(id, FAILED, outcome.reply());
                case LOGGED, ALREADY_LOGGED, HELP -> finish(id, DONE, outcome.reply());
            }
        } catch (RuntimeException e) {
            // Infrastructure failure (E9): no ✅/❌, so the next scan pass retries it.
            log.error("Message {}: not processed ({}); will retry on the next scan", id, e.getClass().getSimpleName());
        }
    }

    /** Final reaction, remove 👀, then the reply. Each step is best-effort: the data is already safe (§3.1). */
    private void finish(String id, String reaction, String reply) {
        bestEffort(() -> discord.addReaction(id, reaction), id, "add " + reaction);
        bestEffort(() -> discord.removeOwnReaction(id, PROCESSING), id, "remove 👀");
        bestEffort(() -> discord.reply(id, reply), id, "reply");
    }

    private static void bestEffort(Runnable action, String id, String what) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Message {}: could not {} ({})", id, what, e.getClass().getSimpleName());
        }
    }
}
