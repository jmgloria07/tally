package app.tally.coach;

/** One OpenAI-style chat message: role {@code system}, {@code user} or {@code assistant}. */
public record ChatMessage(String role, String content) {
}
