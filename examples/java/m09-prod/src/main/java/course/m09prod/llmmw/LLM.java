package course.m09prod.llmmw;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Звено цепочки. Каждый слой (retry, breaker, limiter, trace) — декоратор над LLM. */
@FunctionalInterface
public interface LLM {
    Response complete(Request req) throws Exception;

    /** Текстовое сообщение диалога: role — "user" или "assistant". */
    record Msg(String role, String text) {
        public static Msg user(String text) {
            return new Msg("user", text);
        }
    }

    /**
     * deadline — бюджет всего пользовательского запроса: ретраи, лимитер и fallback
     * укладываются внутрь него. null — без дедлайна.
     */
    record Request(String tenant, String model, String system, List<Msg> messages,
                   long maxTokens, int estInputTokens, Instant deadline) {

        public Request withModel(String m) {
            return new Request(tenant, m, system, messages, maxTokens, estInputTokens, deadline);
        }

        public Request withSystem(String s) {
            return new Request(tenant, model, s, messages, maxTokens, estInputTokens, deadline);
        }

        /** Сколько осталось до дедлайна; пусто — дедлайна нет. */
        public Optional<Duration> timeLeft() {
            return Optional.ofNullable(deadline).map(d -> Duration.between(Instant.now(), d));
        }

        public boolean expired() {
            return timeLeft().map(d -> !d.isPositive()).orElse(false);
        }

        /** Текст последнего user-сообщения. */
        public String lastUserText() {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).role().equals("user")) {
                    return messages.get(i).text();
                }
            }
            return "";
        }
    }

    record Response(String text, String model, String stopReason, String route,
                    long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {

        public static Response of(String text, String model, String stopReason) {
            return new Response(text, model, stopReason, "", 0, 0, 0, 0);
        }

        public Response withRoute(String r) {
            return new Response(text, model, stopReason, r, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens);
        }
    }
}
