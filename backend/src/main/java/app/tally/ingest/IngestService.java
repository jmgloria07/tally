package app.tally.ingest;

import app.tally.common.ClockConfig;
import app.tally.meal.MealDto;
import app.tally.meal.MealService;
import app.tally.weighin.WeighInDto;
import app.tally.weighin.WeighInService;
import app.tally.workout.WorkoutService;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logs one message (discord-ingest spec §3.1, steps 2 onward). Knows nothing about Discord: the caller passes
 * the message ID, text and context, and maps the outcome to reactions and a reply.
 *
 * <p>Everything a message produces, plus its ledger row, is written in one transaction (E2, E14). A database
 * error propagates and rolls everything back; the caller leaves the message pending so the next scan retries
 * it (E9, "infrastructure"). Message text is never logged (§8).
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final LogParser parser;
    private final MealService mealService;
    private final WorkoutService workoutService;
    private final WeighInService weighInService;
    private final IngestedMessageRepository ledger;
    private final Clock clock;

    public IngestService(LogParser parser, MealService mealService, WorkoutService workoutService,
            WeighInService weighInService, IngestedMessageRepository ledger, Clock clock) {
        this.parser = parser;
        this.mealService = mealService;
        this.workoutService = workoutService;
        this.weighInService = weighInService;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * The checks that need no parsing (§3.1 steps 2–3, plus E13 comments): ignored, help or already logged. Empty
     * means the message should be parsed. The Discord worker calls this first so it adds 👀 only to messages it
     * is about to parse.
     */
    @Transactional(readOnly = true)
    public Optional<IngestOutcome> precheck(String messageId, String text) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId is required");
        }
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("//")) { // see E13
            return Optional.of(IngestOutcome.ignored());
        }
        if (trimmed.toLowerCase(Locale.ROOT).equals("help")) { // see E15
            log.info("Message {}: help", messageId);
            return Optional.of(new IngestOutcome(IngestOutcome.Kind.HELP, LogReplies.HELP));
        }
        if (ledger.existsById(messageId)) { // see E2
            log.info("Message {}: already logged", messageId);
            return Optional.of(new IngestOutcome(IngestOutcome.Kind.ALREADY_LOGGED, LogReplies.ALREADY_LOGGED));
        }
        return Optional.empty();
    }

    @Transactional
    public IngestOutcome ingest(String messageId, String text, MessageContext context) {
        Optional<IngestOutcome> early = precheck(messageId, text);
        if (early.isPresent()) {
            return early.get();
        }

        ParsedLog parsed;
        try {
            parsed = parser.parse(text, context); // untrimmed, so line numbers match what the user sees
        } catch (InvalidLogException e) {
            log.info("Message {}: invalid", messageId);
            return new IngestOutcome(IngestOutcome.Kind.INVALID, LogReplies.invalid(e.getMessage()));
        }

        for (MealDto.Request meal : parsed.meals()) {
            mealService.create(meal);
        }
        if (parsed.workout() != null) {
            workoutService.create(parsed.workout());
        }
        for (WeighInDto.Request weighIn : parsed.weighIns()) {
            weighInService.create(weighIn);
        }
        ledger.saveAndFlush(new IngestedMessage(messageId, ClockConfig.now(clock)));
        log.info("Message {}: logged {} entries", messageId, parsed.entryCount());
        return new IngestOutcome(IngestOutcome.Kind.LOGGED, LogReplies.logged(parsed));
    }
}
