package course.m09prod.llmmw;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import java.util.List;
import java.util.Objects;

/** Спан chat {model} и метрики gen_ai.client.* вокруг всей цепочки. provider — "anthropic". */
public record Traced(LLM next, String provider, GenAi otel) implements LLM {

    @Override
    public Response complete(Request req) throws Exception {
        long start = System.nanoTime();
        Span span = otel.tracer.spanBuilder("chat " + req.model())
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("gen_ai.operation.name", "chat")
                .setAttribute("gen_ai.provider.name", provider)
                .setAttribute("gen_ai.request.model", req.model())
                .setAttribute("gen_ai.request.max_tokens", req.maxTokens())
                .setAttribute("app.tenant", req.tenant()) // свой namespace для не-конвенционных полей
                .startSpan();
        var common = Attributes.builder()
                .put("gen_ai.operation.name", "chat")
                .put("gen_ai.provider.name", provider)
                .put("gen_ai.request.model", req.model());
        try (Scope ignored = span.makeCurrent()) {
            Response resp = next.complete(req);
            span.setAllAttributes(Attributes.builder()
                    .put("gen_ai.response.model", resp.model())
                    .put(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"),
                            List.of(Objects.toString(resp.stopReason(), "")))
                    .put("gen_ai.usage.input_tokens", resp.inputTokens())
                    .put("gen_ai.usage.output_tokens", resp.outputTokens())
                    .put("gen_ai.usage.cache_read.input_tokens", resp.cacheReadTokens())
                    .put("gen_ai.usage.cache_creation.input_tokens", resp.cacheWriteTokens())
                    .put("app.llm.route", resp.route())
                    .put("app.llm.cost_usd", Metered.costUsd(resp))
                    .build());
            Attributes attrs = common.put("gen_ai.response.model", resp.model()).build();
            otel.opDuration.record(seconds(start), attrs);
            otel.tokenUsage.record(resp.inputTokens(), attrs.toBuilder().put("gen_ai.token.type", "input").build());
            otel.tokenUsage.record(resp.outputTokens(), attrs.toBuilder().put("gen_ai.token.type", "output").build());
            // Текст промпта и ответа в атрибуты НЕ пишем: только opt-in, с маскированием и сэмплированием.
            return resp;
        } catch (Exception e) {
            GenAi.fail(span, e, "llm call failed");
            otel.opDuration.record(seconds(start), common.put("error.type", GenAi.errorType(e)).build());
            throw e;
        } finally {
            span.end();
        }
    }

    private static double seconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e9;
    }
}
