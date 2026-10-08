package course.m09prod.llmmw;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Звено под второго провайдера или self-hosted (vLLM, Ollama) через OpenAI-совместимый
 * Chat Completions. Только текст, без tools: для fallback этого хватает.
 */
public record OpenAiCompat(HttpClient http, String baseUrl, String apiKey) implements LLM {

    /**
     * Ошибка адаптера в той же классификации, что и ошибки Anthropic SDK:
     * по ней работают retryable, breaker и метрика error.type.
     */
    public static final class HttpError extends IOException {
        private final int statusCode;
        private final Duration retryAfter;

        public HttpError(int statusCode, Duration retryAfter, String body) {
            super("http " + statusCode + ": " + body);
            this.statusCode = statusCode;
            this.retryAfter = retryAfter;
        }

        public int statusCode() {
            return statusCode;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }

    record ChatMsg(String role, String content) {}

    record ChatResp(String model, List<Choice> choices, Usage usage) {
        record Choice(ChatMsg message, @JsonProperty("finish_reason") String finishReason) {}

        record Usage(@JsonProperty("prompt_tokens") long promptTokens,
                     @JsonProperty("completion_tokens") long completionTokens) {}
    }

    @Override
    public Response complete(Request req) throws Exception {
        List<ChatMsg> msgs = new ArrayList<>();
        msgs.add(new ChatMsg("system", req.system()));
        req.messages().forEach(m -> msgs.add(new ChatMsg(m.role(), m.text())));
        byte[] body = Flags.JSON.writeValueAsBytes(
                Map.of("model", req.model(), "messages", msgs, "max_tokens", req.maxTokens()));
        var hreq = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (req.expired()) {
            throw new TimeoutException("deadline exceeded");
        }
        req.timeLeft().ifPresent(hreq::timeout);
        // IOException (сеть, HttpTimeoutException): retryable разберётся.
        HttpResponse<byte[]> resp = http.send(hreq.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            byte[] b = resp.body();
            throw new HttpError(resp.statusCode(),
                    Retry.retryAfter(resp.headers().firstValue("retry-after").orElse(null)),
                    new String(b, 0, Math.min(b.length, 1024), StandardCharsets.UTF_8));
        }
        ChatResp out = Flags.JSON.readValue(resp.body(), ChatResp.class);
        if (out.choices() == null || out.choices().isEmpty()) {
            throw new HttpError(502, Duration.ZERO, "no choices");
        }
        var c = out.choices().getFirst();
        // stop_reason приводим к словарю Anthropic: остальная цепочка (кэш, метрики) знает только его.
        String stop = switch (Objects.requireNonNullElse(c.finishReason(), "")) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "content_filter" -> "refusal";
            default -> "";
        };
        var usage = out.usage() != null ? out.usage() : new ChatResp.Usage(0, 0);
        return new Response(c.message().content(), out.model(), stop, "",
                usage.promptTokens(), usage.completionTokens(), 0, 0);
    }
}
