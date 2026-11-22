package app.tally.ingest;

/**
 * The parsing seam (see E5). This version has one implementation, {@link StrictLogParser}; a later LLM parser
 * (spec §5) plugs in here without touching the Discord code or {@link IngestService}.
 */
public interface LogParser {

    /**
     * Turns a message into validated entries.
     *
     * @throws InvalidLogException when the text can't be logged as written
     */
    ParsedLog parse(String text, MessageContext context);
}
