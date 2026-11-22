package app.tally.ingest.discord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory channel: messages, the bot's own reactions and its replies. Can be told to fail calls. */
class FakeDiscordPort implements DiscordPort {

    record Reply(String messageId, String text) {
    }

    private final Map<String, ChannelMessage> messages = new HashMap<>();
    private final Map<String, Set<String>> reactions = new HashMap<>();
    final List<Reply> replies = new ArrayList<>();
    final List<String> reactionLog = new ArrayList<>();
    Listener listener;
    boolean stopped;
    boolean failHistory;
    String failReaction;

    ChannelMessage post(String id, String authorId, String content, Instant sentAt) {
        ChannelMessage message = new ChannelMessage(id, authorId, false, false, content, false, sentAt, Set.of());
        put(message);
        return message;
    }

    void put(ChannelMessage message) {
        messages.put(message.id(), message);
        reactions.computeIfAbsent(message.id(), k -> new HashSet<>()).addAll(message.ownReactions());
    }

    Set<String> reactionsOn(String messageId) {
        return Set.copyOf(reactions.getOrDefault(messageId, Set.of()));
    }

    List<String> repliesTo(String messageId) {
        return replies.stream().filter(r -> r.messageId().equals(messageId)).map(Reply::text).toList();
    }

    /** The message as Discord would return it now, with the bot's current reactions. */
    ChannelMessage current(String id) {
        ChannelMessage m = messages.get(id);
        return new ChannelMessage(m.id(), m.authorId(), m.fromBot(), m.fromWebhook(), m.content(),
                m.hasAttachments(), m.sentAt(), reactionsOn(id));
    }

    @Override
    public void start(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void stop() {
        stopped = true;
    }

    @Override
    public List<ChannelMessage> history(Instant after) {
        if (failHistory) {
            throw new IllegalStateException("history failed");
        }
        return messages.values().stream()
                .filter(m -> m.sentAt().isAfter(after))
                .sorted(Comparator.comparing(ChannelMessage::sentAt))
                .map(m -> current(m.id()))
                .toList();
    }

    @Override
    public void addReaction(String messageId, String emoji) {
        if (emoji.equals(failReaction)) {
            throw new IllegalStateException("reaction failed");
        }
        reactionLog.add("+" + emoji + " " + messageId);
        reactions.computeIfAbsent(messageId, k -> new HashSet<>()).add(emoji);
    }

    @Override
    public void removeOwnReaction(String messageId, String emoji) {
        Set<String> own = reactions.get(messageId);
        if (own != null && own.remove(emoji)) {
            reactionLog.add("-" + emoji + " " + messageId);
        }
    }

    @Override
    public void reply(String messageId, String text) {
        replies.add(new Reply(messageId, text));
    }
}
