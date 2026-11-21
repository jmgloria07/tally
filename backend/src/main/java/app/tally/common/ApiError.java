package app.tally.common;

/** The one error body shape the API returns: {@code {"error": "<message>"}}. */
public record ApiError(String error) {
}
