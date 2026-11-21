package app.fitlocal.coach;

import app.fitlocal.common.Checks;

/** API shapes for {@code /api/coach} and {@code /api/health}. */
public final class CoachDto {

    private CoachDto() {
    }

    /**
     * {@code history} is left untyped on purpose: a malformed item is dropped by {@link CoachHistory}, not
     * turned into a 400 for the whole request.
     */
    public record Request(String message, Object history) {

        public Request {
            message = Checks.text(message, "Message", 3000, true);
        }
    }

    public record Response(String answer) {
    }

    public record HealthResponse(String lmStudio, String model) {
    }

    public record ContextResponse(String systemPrompt, String trackerBlock) {
    }
}
