package app.fitlocal.common;

/** A request failed a validation rule. The message is shown to the user as-is (HTTP 400). */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
