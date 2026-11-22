package app.tally.ingest.discord;

import java.time.Instant;
import java.util.Set;

/**
 * A message from the log channel, reduced to what ingestion needs. Plain data: no Discord4J or Reactor types
 * leave the adapter (see E4).
 *
 * @param authorId     the author's user ID, or null if Discord reported none
 * @param fromBot      the author is a bot account (including Tally's own bot)
 * @param fromWebhook  the message was posted by a webhook
 * @param ownReactions unicode emoji the bot itself has reacted with
 */
public record ChannelMessage(String id, String authorId, boolean fromBot, boolean fromWebhook, String content,
        boolean hasAttachments, Instant sentAt, Set<String> ownReactions) {

    public ChannelMessage {
        content = content == null ? "" : content;
        ownReactions = Set.copyOf(ownReactions);
    }
}
