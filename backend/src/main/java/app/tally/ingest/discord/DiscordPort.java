package app.tally.ingest.discord;

import java.time.Instant;
import java.util.List;

/**
 * Everything ingestion needs from Discord, as plain blocking calls on the one configured channel (see E4). The
 * Discord4J adapter implements it; tests use a fake. Methods throw a RuntimeException when Discord fails.
 */
public interface DiscordPort {

    /** Receives gateway events. Called on Discord4J's threads, so implementations must return quickly. */
    interface Listener {

        /** A new message in the configured channel. Messages in threads never arrive here (E13). */
        void onMessage(ChannelMessage message);

        /** The gateway connected for the first time or reconnected: time for a scan pass (E4). */
        void onConnected();
    }

    /** Connects in the background and keeps retrying; never blocks or throws for connection problems (E3). */
    void start(Listener listener);

    void stop();

    /** Channel messages sent after {@code after}, oldest first. */
    List<ChannelMessage> history(Instant after);

    void addReaction(String messageId, String emoji);

    void removeOwnReaction(String messageId, String emoji);

    /** Replies to the message without pinging anyone. */
    void reply(String messageId, String text);
}
