package app.tally.coach;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Trims client-sent chat history (spec §8.5). The client is never trusted: anything that is not a non-empty
 * user/assistant string is dropped, including any {@code system} message.
 */
public final class CoachHistory {

    static final int MAX_MESSAGES = 12;
    static final int MAX_MESSAGE_CHARS = 4000;
    static final int MAX_TOTAL_CHARS = 24_000;

    private CoachHistory() {
    }

    public static List<ChatMessage> trim(Object rawHistory) {
        List<ChatMessage> kept = new ArrayList<>();
        if (rawHistory instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> map
                        && map.get("role") instanceof String role
                        && (role.equals("user") || role.equals("assistant"))
                        && map.get("content") instanceof String content
                        && !content.isBlank()) {
                    kept.add(new ChatMessage(role, content));
                }
            }
        }
        if (kept.size() > MAX_MESSAGES) {
            kept = new ArrayList<>(kept.subList(kept.size() - MAX_MESSAGES, kept.size()));
        }
        kept.replaceAll(m -> m.content().length() > MAX_MESSAGE_CHARS
                ? new ChatMessage(m.role(), m.content().substring(0, MAX_MESSAGE_CHARS))
                : m);
        int total = kept.stream().mapToInt(m -> m.content().length()).sum();
        while (total > MAX_TOTAL_CHARS && !kept.isEmpty()) {
            total -= kept.removeFirst().content().length();
        }
        while (!kept.isEmpty() && kept.getFirst().role().equals("assistant")) {
            kept.removeFirst();
        }
        return List.copyOf(kept);
    }
}
