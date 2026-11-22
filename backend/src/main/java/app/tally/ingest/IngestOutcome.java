package app.tally.ingest;

/**
 * What happened to one message, and what to tell the user. Which reaction each kind gets is decided by the
 * Discord worker (E8). {@code reply} is null only for IGNORED.
 */
public record IngestOutcome(Kind kind, String reply) {

    public enum Kind {
        /** Entries and ledger row inserted. */
        LOGGED,
        /** Already in the ledger: nothing inserted (E2). */
        ALREADY_LOGGED,
        /** Can't be logged as written: nothing inserted (E9). */
        INVALID,
        /** The {@code help} keyword (E15): nothing inserted. */
        HELP,
        /** Blank or a {@code //} comment (E13): no reaction, no reply. */
        IGNORED
    }

    static IngestOutcome ignored() {
        return new IngestOutcome(Kind.IGNORED, null);
    }
}
