package app.fitlocal.coach;

import app.fitlocal.coach.ModelPicker.LmModel;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls LM Studio's OpenAI-compatible API with the JDK HttpClient (spec §8.1-8.3, §8.7). One blocking request
 * per chat turn, no streaming, no {@code max_tokens} (so reasoning models are not cut off before answering).
 *
 * <p>The model is chosen per request from what LM Studio has <em>loaded</em>, via its REST API
 * ({@code GET /api/v0/models}, which reports each model's load state). See {@link ModelPicker} and decision D6.
 * Older LM Studio versions without that API fall back to the previous behavior.
 */
@Component
public class LmStudioClient {

    /** {@code online}: LM Studio answered. {@code model}: what a chat request would use now, or null. */
    public record Health(boolean online, String model) {
    }

    static final double TEMPERATURE = 0.55;
    static final String NO_MODEL_LOADED =
            "LM Studio is running but no model is loaded. Load one in LM Studio (or run start.bat) and try again.";
    private static final String UNEXPECTED = "LM Studio returned an unexpected response.";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MODELS_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);

    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    /** OpenAI-compatible base, e.g. {@code http://127.0.0.1:1234/v1}. */
    private final String baseUrl;
    /** LM Studio's own REST API list, e.g. {@code http://127.0.0.1:1234/api/v0/models}. */
    private final String nativeModelsUrl;
    private final String configuredModel;
    private final Duration timeout;

    public LmStudioClient(
            @Value("${fitlocal.lm-studio.base-url}") String baseUrl,
            @Value("${fitlocal.lm-studio.model:}") String configuredModel,
            @Value("${fitlocal.lm-studio.timeout}") Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String root = this.baseUrl.endsWith("/v1") ? this.baseUrl.substring(0, this.baseUrl.length() - 3) : this.baseUrl;
        this.nativeModelsUrl = root + "/api/v0/models";
        this.configuredModel = configuredModel == null ? "" : configuredModel.strip();
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** Sends the messages to the loaded model and returns the final answer with reasoning removed. */
    public String chat(List<ChatMessage> messages) {
        String model = resolveModel(MODELS_TIMEOUT)
                .orElseThrow(() -> new LlmException(HttpStatus.SERVICE_UNAVAILABLE, NO_MODEL_LOADED));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", TEMPERATURE);
        body.put("stream", false);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = send(request, true);
        if (response.statusCode() >= 400) {
            throw httpError(response);
        }
        String content;
        try {
            JsonNode contentNode = json.readTree(response.body()).path("choices").path(0).path("message").get("content");
            if (contentNode == null || !contentNode.isString()) {
                throw new LlmException(HttpStatus.BAD_GATEWAY, UNEXPECTED);
            }
            content = contentNode.asString();
        } catch (JacksonException e) {
            throw new LlmException(HttpStatus.BAD_GATEWAY, UNEXPECTED);
        }
        String answer = ReasoningStripper.strip(content);
        if (answer.isEmpty()) {
            throw new LlmException(HttpStatus.BAD_GATEWAY,
                    "The model returned no answer (only reasoning). Try again or shorten the conversation.");
        }
        return answer;
    }

    /** Never throws: if LM Studio can't be reached or answers badly, reports offline. */
    public Health health() {
        try {
            return new Health(true, resolveModel(HEALTH_TIMEOUT).orElse(null));
        } catch (RuntimeException e) {
            return new Health(false, null);
        }
    }

    /**
     * The model to use now: the configured one if it is loaded, else the first loaded chat model, else empty.
     * Asked on every request, so loading a different model in LM Studio takes effect immediately.
     */
    Optional<String> resolveModel(Duration requestTimeout) {
        HttpResponse<String> response = send(get(nativeModelsUrl, requestTimeout), false);
        Optional<List<LmModel>> models = response.statusCode() == 200 ? parseNativeModels(response.body()) : Optional.empty();
        if (models.isPresent()) {
            return ModelPicker.pick(configuredModel, models.get());
        }
        if (response.statusCode() >= 500) {
            throw httpError(response);
        }
        return legacyResolve(requestTimeout);
    }

    /**
     * {@code {"data": [{"id": "...", "type": "llm", "state": "loaded"}, ...]}}. Empty when the body is not that
     * shape (an LM Studio version without the REST API may answer unknown paths with a 200 error object).
     */
    private Optional<List<LmModel>> parseNativeModels(String body) {
        try {
            JsonNode data = json.readTree(body).path("data");
            if (!data.isArray()) {
                return Optional.empty();
            }
            List<LmModel> models = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                JsonNode m = data.get(i);
                models.add(new LmModel(text(m.get("id")), text(m.get("type")), "loaded".equals(text(m.get("state")))));
            }
            return Optional.of(models);
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    /**
     * Before LM Studio's REST API: {@code LM_STUDIO_MODEL} when set, else the first id from {@code /v1/models}.
     * With just-in-time loading that list includes every downloaded model, so this is only a fallback.
     */
    private Optional<String> legacyResolve(Duration requestTimeout) {
        if (!configuredModel.isEmpty()) {
            return Optional.of(configuredModel);
        }
        HttpResponse<String> response = send(get(baseUrl + "/models", requestTimeout), false);
        if (response.statusCode() >= 400) {
            throw httpError(response);
        }
        try {
            String id = text(json.readTree(response.body()).path("data").path(0).get("id"));
            return id.isBlank() ? Optional.empty() : Optional.of(id);
        } catch (JacksonException e) {
            throw new LlmException(HttpStatus.BAD_GATEWAY, UNEXPECTED);
        }
    }

    private static HttpRequest get(String url, Duration requestTimeout) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.asString() : "";
    }

    /** {@code chat}: a timeout means the model was too slow (504); for a quick model-list call it means unreachable. */
    private HttpResponse<String> send(HttpRequest request, boolean chat) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw unreachable();
        } catch (HttpTimeoutException e) {
            if (!chat) {
                throw unreachable();
            }
            throw new LlmException(HttpStatus.GATEWAY_TIMEOUT,
                    "The model took longer than " + describe(timeout) + ". Try a shorter question.");
        } catch (IOException e) {
            throw unreachable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unreachable();
        }
    }

    private LlmException unreachable() {
        return new LlmException(HttpStatus.SERVICE_UNAVAILABLE,
                "Can't reach LM Studio at " + baseUrl + ". Start it (or run start.bat) and try again.");
    }

    private static LlmException httpError(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        String excerpt = body.length() > 300 ? body.substring(0, 300) : body;
        return new LlmException(HttpStatus.BAD_GATEWAY,
                "LM Studio returned HTTP " + response.statusCode() + ": " + excerpt);
    }

    private static String describe(Duration duration) {
        long seconds = duration.toSeconds();
        return seconds % 60 == 0 ? (seconds / 60) + " minutes" : seconds + " seconds";
    }
}
