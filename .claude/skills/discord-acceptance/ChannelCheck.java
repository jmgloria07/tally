import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lists the newest messages in the configured log channel, oldest first, as the bot sees them: author, first
 * line of text, the bot's own reactions, and which message a reply answers. Reads the token and channel ID from
 * secrets.properties and never prints the token.
 * Usage: java -cp "$CP" ChannelCheck.java secrets.properties [limit, default 20]
 */
public class ChannelCheck {
    public static void main(String[] args) throws Exception {
        Properties secrets = new Properties();
        try (var in = Files.newBufferedReader(Path.of(args.length > 0 ? args[0] : "secrets.properties"))) {
            secrets.load(in);
        }
        String token = secrets.getProperty("tally.ingest.discord.token", "").strip();
        String channel = secrets.getProperty("tally.ingest.discord.channel-id", "").strip();
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        if (token.isEmpty() || channel.isEmpty()) {
            throw new IllegalStateException("token or channel-id missing in secrets.properties");
        }
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("https://discord.com/api/v10/channels/" + channel + "/messages?limit=" + limit))
                .header("Authorization", "Bot " + token)
                .header("User-Agent", "DiscordBot (tally-acceptance, 1.0)")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.out.println("Discord returned HTTP " + response.statusCode());
            return;
        }
        JsonNode messages = JsonMapper.builder().build().readTree(response.body());
        List<String> lines = new ArrayList<>();
        for (JsonNode m : messages) { // newest first from Discord
            JsonNode author = m.path("author");
            String who = author.path("bot").asBoolean(false) ? "BOT " : "USER";
            String text = m.path("content").asString("").replace("\r", "");
            int newline = text.indexOf('\n');
            String first = newline < 0 ? text : text.substring(0, newline) + " ...";
            if (first.length() > 70) {
                first = first.substring(0, 70) + "...";
            }
            StringBuilder own = new StringBuilder();
            for (JsonNode r : m.path("reactions")) {
                if (r.path("me").asBoolean(false)) {
                    // Names, not emoji: a Windows console prints emoji as "?".
                    String emoji = r.path("emoji").path("name").asString("");
                    own.append(own.isEmpty() ? "" : ",").append(switch (emoji) {
                        case "✅" -> "done";
                        case "❌" -> "failed";
                        case "👀" -> "processing";
                        default -> "U+" + Integer.toHexString(emoji.codePointAt(0)).toUpperCase();
                    });
                }
            }
            String replyTo = m.path("message_reference").path("message_id").asString("");
            String attachments = m.path("attachments").isEmpty() ? "" : " [attachment]";
            lines.add(0, m.path("id").asString() + "  " + who + "  react=" + (own.isEmpty() ? "-" : own)
                    + (replyTo.isEmpty() ? "" : "  reply-to=" + replyTo) + attachments + "  | " + first);
        }
        System.out.println(lines.size() + " message(s), oldest first:");
        lines.forEach(System.out::println);
    }
}
