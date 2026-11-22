package app.tally.ingest;

/**
 * The message can't be logged as written (E9, "invalid"). The message is shown to the user as-is in the ❌
 * reply, so it names the line and the reason, e.g. {@code Line 3: Calories is required}.
 */
public class InvalidLogException extends RuntimeException {

    public InvalidLogException(String message) {
        super(message);
    }
}
