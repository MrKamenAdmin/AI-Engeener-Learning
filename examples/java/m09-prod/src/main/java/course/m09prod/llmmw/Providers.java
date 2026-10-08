package course.m09prod.llmmw;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;

public final class Providers {
    private Providers() {}

    /** Нижний слой. Ретраи SDK выключены: они живут в нашем Retry. */
    public static LLM anthropic() {
        AnthropicClient c = AnthropicOkHttpClient.builder().fromEnv().maxRetries(0).build();
        return req -> {
            var p = MessageCreateParams.builder()
                    .model(req.model())
                    .maxTokens(req.maxTokens())
                    .system(req.system());
            for (LLM.Msg m : req.messages()) {
                if (m.role().equals("assistant")) {
                    p.addAssistantMessage(m.text());
                } else {
                    p.addUserMessage(m.text());
                }
            }
            var opts = RequestOptions.builder();
            req.timeLeft().ifPresent(opts::timeout); // дедлайн запроса — таймаут HTTP-вызова
            Message msg = c.messages().create(p.build(), opts.build());
            var text = new StringBuilder();
            msg.content().forEach(b -> b.text().ifPresent(t -> text.append(t.text())));
            var u = msg.usage();
            return new LLM.Response(text.toString(), msg.model().asString(),
                    msg.stopReason().map(StopReason::asString).orElse(""), "",
                    u.inputTokens(), u.outputTokens(),
                    u.cacheReadInputTokens().orElse(0L), u.cacheCreationInputTokens().orElse(0L));
        };
    }

    /**
     * Сборка: Traced → SemCache → Metered → Fallback[Breaker → Retry → Limiter → провайдер].
     * cache и meter получают следующее звено: next -> new SemCache(next, ds, embed, 0.92, ttl).
     */
    public static LLM chain(LLM selfHosted, UnaryOperator<LLM> cache, UnaryOperator<LLM> meter, GenAi otel) {
        LLM api = anthropic();
        var budget = new Retry.Budget(0.2, 50);
        var fb = new Fallback(List.of(
                route("primary", "claude-opus-5", api, 4000, 2_000_000, 400_000, budget),
                route("smaller", "claude-sonnet-5", api, 4000, 2_000_000, 400_000, budget),
                route("self-hosted", "qwen3-8b", selfHosted, 600, 1_000_000, 200_000, budget)));
        return new Traced(cache.apply(meter.apply(fb)), "anthropic", otel);
    }

    private static Fallback.Route route(String name, String model, LLM base,
                                        double rpm, double itpm, double otpm, Retry.Budget budget) {
        return new Fallback.Route(name, model,
                new Retry(new Limiter(base, rpm, itpm, otpm), 2, Duration.ofMillis(250), Duration.ofSeconds(4), budget),
                new Fallback.Breaker(5, Duration.ofSeconds(30)));
    }
}
