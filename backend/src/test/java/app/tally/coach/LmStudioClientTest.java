package app.tally.coach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/** Runs the real client against a fake LM Studio (the JDK's built-in HTTP server; no extra dependencies). */
class LmStudioClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<ChatMessage> QUESTION = List.of(new ChatMessage("user", "Hi"));

    private HttpServer server;
    private final List<String> chatModels = new CopyOnWriteArrayList<>();
    private volatile int nativeStatus = 200;
    private volatile String nativeBody = "{\"data\": []}";
    private volatile String openAiModelsBody = "{\"data\": []}";

    @BeforeEach
    void startFakeLmStudio() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v0/models", exchange -> respond(exchange, nativeStatus, nativeBody));
        server.createContext("/v1/models", exchange -> respond(exchange, 200, openAiModelsBody));
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            chatModels.add(JSON.readTree(request).get("model").asString());
            respond(exchange, 200, "{\"choices\": [{\"message\": {\"content\": \"<think>plan</think>## Answer\"}}]}");
        });
        server.start();
    }

    @AfterEach
    void stopFakeLmStudio() {
        server.stop(0);
    }

    @Test
    void usesTheLoadedModelNotTheFirstDownloadedOne() {
        nativeBody = """
                {"data": [
                  {"id": "qwen/qwen3.8-27b", "type": "vlm", "state": "not-loaded"},
                  {"id": "qwen3-14b", "type": "llm", "state": "loaded"}
                ]}""";

        assertThat(client("").chat(QUESTION)).isEqualTo("## Answer");
        assertThat(chatModels).containsExactly("qwen3-14b");
    }

    @Test
    void prefersTheConfiguredModelWhenItIsLoaded() {
        nativeBody = """
                {"data": [
                  {"id": "qwen3-14b", "type": "llm", "state": "loaded"},
                  {"id": "tally-coach", "type": "llm", "state": "loaded"}
                ]}""";

        client("tally-coach").chat(QUESTION);

        assertThat(chatModels).containsExactly("tally-coach");
    }

    /** The reported case: start.bat's model is not loaded, but one was loaded in the LM Studio app. */
    @Test
    void usesTheLoadedModelWhenTheConfiguredOneIsNotLoaded() {
        nativeBody = """
                {"data": [
                  {"id": "openai/gpt-oss-20b", "type": "llm", "state": "not-loaded"},
                  {"id": "qwen3-14b", "type": "llm", "state": "loaded"}
                ]}""";

        client("tally-coach").chat(QUESTION);

        assertThat(chatModels).containsExactly("qwen3-14b");
    }

    @Test
    void noModelLoadedFailsClearlyWithoutAskingLmStudioToLoadOne() {
        nativeBody = """
                {"data": [
                  {"id": "qwen/qwen3.8-27b", "type": "vlm", "state": "not-loaded"},
                  {"id": "text-embedding-nomic", "type": "embeddings", "state": "loaded"}
                ]}""";

        assertThatThrownBy(() -> client("").chat(QUESTION))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage()).isEqualTo(LmStudioClient.NO_MODEL_LOADED);
                });
        assertThat(chatModels).isEmpty();
    }

    @Test
    void olderLmStudioWithoutTheRestApiFallsBackToTheModelList() {
        nativeStatus = 404;
        nativeBody = "{\"error\": \"Unexpected endpoint or method.\"}";
        openAiModelsBody = "{\"data\": [{\"id\": \"some-model\"}]}";

        client("").chat(QUESTION);

        assertThat(chatModels).containsExactly("some-model");
    }

    @Test
    void anErrorObjectWithStatus200AlsoFallsBack() {
        nativeBody = "{\"error\": \"Unexpected endpoint or method.\"}";
        openAiModelsBody = "{\"data\": [{\"id\": \"some-model\"}]}";

        client("").chat(QUESTION);

        assertThat(chatModels).containsExactly("some-model");
    }

    @Test
    void healthReportsTheModelInUseOrNone() {
        nativeBody = "{\"data\": [{\"id\": \"qwen3-14b\", \"type\": \"llm\", \"state\": \"loaded\"}]}";
        assertThat(client("").health()).isEqualTo(new LmStudioClient.Health(true, "qwen3-14b"));

        nativeBody = "{\"data\": [{\"id\": \"qwen3-14b\", \"type\": \"llm\", \"state\": \"not-loaded\"}]}";
        assertThat(client("").health()).isEqualTo(new LmStudioClient.Health(true, null));
    }

    @Test
    void unreachableLmStudioIsOfflineAndChatFailsWith503() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        LmStudioClient offline = new LmStudioClient("http://127.0.0.1:" + closedPort + "/v1", "", Duration.ofSeconds(5));

        assertThat(offline.health()).isEqualTo(new LmStudioClient.Health(false, null));
        assertThatThrownBy(() -> offline.chat(QUESTION))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage()).startsWith("Can't reach LM Studio");
                });
    }

    /** RestClient spec R5: a read timeout on the chat call is the model being slow (504), not LM Studio being down. */
    @Test
    void aSlowModelFailsWith504() {
        nativeBody = "{\"data\": [{\"id\": \"qwen3-14b\", \"type\": \"llm\", \"state\": \"loaded\"}]}";
        server.removeContext("/v1/chat/completions");
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"choices\": [{\"message\": {\"content\": \"too late\"}}]}");
        });
        LmStudioClient slow = new LmStudioClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "",
                Duration.ofSeconds(1));

        assertThatThrownBy(() -> slow.chat(QUESTION))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                    assertThat(e.getMessage()).isEqualTo("The model took longer than 1 seconds. Try a shorter question.");
                });
    }

    private LmStudioClient client(String configuredModel) {
        return new LmStudioClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", configuredModel,
                Duration.ofSeconds(5));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
