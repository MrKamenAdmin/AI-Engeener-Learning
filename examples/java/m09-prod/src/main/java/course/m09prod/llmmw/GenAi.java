package course.m09prod.llmmw;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** Инструменты OpenTelemetry по конвенции GenAI: трейсер, гистограммы, спаны агента и инструментов. */
public final class GenAi {
    final Tracer tracer;
    final LongHistogram tokenUsage;
    final DoubleHistogram opDuration, ttft;

    // Границы бакетов — рекомендованные конвенцией: токены растут степенями 4, секунды — удвоением.
    private static final List<Long> TOKENS = List.of(1L, 4L, 16L, 64L, 256L, 1024L, 4096L, 16384L, 65536L,
            262144L, 1048576L, 4194304L, 16777216L, 67108864L);
    private static final List<Double> SECONDS = List.of(0.01, 0.02, 0.04, 0.08, 0.16, 0.32, 0.64, 1.28,
            2.56, 5.12, 10.24, 20.48, 40.96, 81.92);

    public GenAi(OpenTelemetry otel) {
        tracer = otel.getTracer("llmmw");
        Meter meter = otel.getMeter("llmmw");
        tokenUsage = meter.histogramBuilder("gen_ai.client.token.usage").ofLongs().setUnit("{token}")
                .setExplicitBucketBoundariesAdvice(TOKENS).build();
        opDuration = meter.histogramBuilder("gen_ai.client.operation.duration").setUnit("s")
                .setExplicitBucketBoundariesAdvice(SECONDS).build();
        // TTFT на клиенте — метрика конвенции (semconv v1.41, статус Development).
        ttft = meter.histogramBuilder("gen_ai.client.operation.time_to_first_chunk").setUnit("s")
                .setExplicitBucketBoundariesAdvice(SECONDS).build();
    }

    /**
     * Колбэк для стрима: первый вызов пишет TTFT, остальные — no-op.
     * Вызывайте его из onText в streamWithDeadlines.
     */
    public Runnable firstChunk(long startNanos, Attributes attrs) {
        var once = new AtomicBoolean();
        return () -> {
            if (once.compareAndSet(false, true)) {
                ttft.record((System.nanoTime() - startNanos) / 1e9, attrs);
            }
        };
    }

    /**
     * Спан всей задачи агента. Спаны chat и execute_tool внутри run становятся его детьми
     * через Context.current(): в трейсе видно, на какой итерации агент «заблудился».
     */
    public <T> T invokeAgent(String agent, Callable<T> run) throws Exception {
        Span span = tracer.spanBuilder("invoke_agent " + agent)
                .setSpanKind(SpanKind.INTERNAL) // агент в нашем процессе; удалённый агент — CLIENT
                .setAttribute("gen_ai.operation.name", "invoke_agent")
                .setAttribute("gen_ai.agent.name", agent)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return run.call();
        } catch (Exception e) {
            fail(span, e, "agent failed");
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Спан одного вызова инструмента. Аргументы и результат в атрибуты не пишем: по конвенции
     * это opt-in (gen_ai.tool.call.arguments/result), там бывают PII.
     */
    public String executeTool(String tool, String callId, Callable<String> fn) throws Exception {
        Span span = tracer.spanBuilder("execute_tool " + tool)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("gen_ai.operation.name", "execute_tool")
                .setAttribute("gen_ai.tool.name", tool)
                .setAttribute("gen_ai.tool.call.id", callId)
                .setAttribute("gen_ai.tool.type", "function")
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return fn.call();
        } catch (Exception e) {
            fail(span, e, "tool failed");
            throw e;
        } finally {
            span.end();
        }
    }

    static void fail(Span span, Exception e, String msg) {
        span.recordException(e);
        span.setStatus(StatusCode.ERROR, msg);
        span.setAttribute("error.type", errorType(e));
    }

    static String errorType(Throwable e) {
        Retry.Status s = Retry.httpStatus(e);
        return s != null ? String.valueOf(s.code()) : "_OTHER"; // значение по конвенции OTel для неклассифицированных ошибок
    }
}
