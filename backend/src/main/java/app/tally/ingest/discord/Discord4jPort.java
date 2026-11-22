package app.tally.ingest.discord;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.event.domain.lifecycle.ReconnectEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.emoji.UnicodeEmoji;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.reaction.Reaction;
import discord4j.discordjson.json.AllowedMentionsData;
import discord4j.discordjson.json.MessageCreateRequest;
import discord4j.discordjson.json.MessageReferenceData;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
import discord4j.rest.entity.RestChannel;
import discord4j.rest.http.client.ClientException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.util.retry.Retry;

/**
 * {@link DiscordPort} on Discord4J (see E4). The only class that sees Reactor: every public method either
 * blocks with a timeout (called from Tally's own worker and scheduler threads, never from Reactor's) or
 * subscribes in the background. Message text is never logged (§8).
 */
public class Discord4jPort implements DiscordPort {

    private static final Logger log = LoggerFactory.getLogger(Discord4jPort.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HISTORY_TIMEOUT = Duration.ofMinutes(2);

    private final DiscordClient client;
    private final Snowflake channelId;
    private final RestChannel restChannel;
    private final Disposable.Composite subscriptions = Disposables.composite();
    private volatile GatewayDiscordClient gateway;

    public Discord4jPort(String token, String channelId) {
        this.client = DiscordClient.create(token);
        this.channelId = Snowflake.of(channelId);
        this.restChannel = client.getChannelById(this.channelId);
    }

    @Override
    public void start(Listener listener) {
        subscriptions.add(client.gateway()
                // GUILD_MESSAGES for MessageCreateEvent; MESSAGE_CONTENT (privileged) so the text isn't empty.
                .setEnabledIntents(IntentSet.of(Intent.GUILDS, Intent.GUILD_MESSAGES, Intent.MESSAGE_CONTENT))
                .login()
                .doOnError(e -> log.warn("Discord login failed ({}); retrying", describe(e)))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5)).maxBackoff(Duration.ofMinutes(5)))
                .subscribe(connected -> {
                    gateway = connected;
                    subscribe(connected, listener);
                }, e -> log.error("Discord connection stopped ({})", describe(e))));
    }

    private void subscribe(GatewayDiscordClient connected, Listener listener) {
        subscriptions.add(connected.on(ReadyEvent.class).subscribe(ready -> {
            log.info("Discord connected");
            safely(listener::onConnected);
        }));
        subscriptions.add(connected.on(ReconnectEvent.class).subscribe(reconnect -> {
            log.info("Discord reconnected");
            safely(listener::onConnected);
        }));
        subscriptions.add(connected.on(MessageCreateEvent.class)
                .filter(event -> event.getMessage().getChannelId().equals(channelId)) // threads have their own ID
                .subscribe(event -> safely(() -> listener.onMessage(toChannelMessage(event.getMessage())))));
    }

    @Override
    public void stop() {
        subscriptions.dispose();
        GatewayDiscordClient connected = gateway;
        if (connected != null) {
            connected.logout().block(CALL_TIMEOUT);
        }
    }

    @Override
    public List<ChannelMessage> history(Instant after) {
        GatewayDiscordClient connected = gateway;
        if (connected == null) {
            throw new IllegalStateException("not connected to Discord yet");
        }
        return connected.getChannelById(channelId)
                .ofType(MessageChannel.class)
                .flatMapMany(channel -> channel.getMessagesAfter(Snowflake.of(after))) // oldest first
                .map(Discord4jPort::toChannelMessage)
                .collectList()
                .block(HISTORY_TIMEOUT);
    }

    @Override
    public void addReaction(String messageId, String emoji) {
        restChannel.getRestMessage(Snowflake.of(messageId)).createReaction(emoji).block(CALL_TIMEOUT);
    }

    @Override
    public void removeOwnReaction(String messageId, String emoji) {
        restChannel.getRestMessage(Snowflake.of(messageId)).deleteOwnReaction(emoji).block(CALL_TIMEOUT);
    }

    @Override
    public void reply(String messageId, String text) {
        restChannel.createMessage(MessageCreateRequest.builder()
                        .content(text)
                        .messageReference(MessageReferenceData.builder().messageId(messageId).failIfNotExists(false).build())
                        // Echoed text must never ping anyone, including the author.
                        .allowedMentions(AllowedMentionsData.builder().parse(List.of()).repliedUser(false).build())
                        .build())
                .block(CALL_TIMEOUT);
    }

    static ChannelMessage toChannelMessage(Message message) {
        Optional<User> author = message.getAuthor();
        Set<String> ownReactions = message.getReactions().stream()
                .filter(Reaction::selfReacted)
                .map(reaction -> reaction.getEmoji().asUnicodeEmoji().map(UnicodeEmoji::getRaw).orElse(""))
                .filter(raw -> !raw.isEmpty())
                .collect(Collectors.toSet());
        return new ChannelMessage(
                message.getId().asString(),
                author.map(user -> user.getId().asString()).orElse(null),
                author.map(User::isBot).orElse(false),
                message.getWebhookId().isPresent(),
                message.getContent(),
                !message.getAttachments().isEmpty(),
                message.getTimestamp(),
                ownReactions);
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("Discord event handling failed ({})", describe(e));
        }
    }

    /** Discord's HTTP status, or the exception class; never the message, which can echo request content (§8). */
    private static String describe(Throwable e) {
        if (e instanceof ClientException clientError) {
            return "Discord " + clientError.getStatus().code();
        }
        return e.getClass().getSimpleName();
    }
}
