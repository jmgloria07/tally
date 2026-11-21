package app.fitlocal.coach;

import org.springframework.http.HttpStatus;

/** A coach request failed because of LM Studio. Carries the HTTP status and user-facing message (spec §8.7). */
public class LlmException extends RuntimeException {

    private final HttpStatus status;

    public LlmException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
