package llmmw

import (
	"context"
	"sync"
	"time"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/trace"
)

// TTFT на клиенте — метрика конвенции (semconv v1.41, статус Development).
var ttftHist, _ = meter.Float64Histogram("gen_ai.client.operation.time_to_first_chunk", metric.WithUnit("s"),
	metric.WithExplicitBucketBoundaries(0.01, 0.02, 0.04, 0.08, 0.16, 0.32, 0.64, 1.28, 2.56, 5.12, 10.24, 20.48, 40.96, 81.92))

// FirstChunk возвращает колбэк для стрима: первый вызов пишет TTFT, остальные — no-op.
// Вызывайте его из onText в StreamWithDeadlines.
func FirstChunk(ctx context.Context, start time.Time, attrs ...attribute.KeyValue) func() {
	var once sync.Once
	return func() {
		once.Do(func() { ttftHist.Record(ctx, time.Since(start).Seconds(), metric.WithAttributes(attrs...)) })
	}
}

// InvokeAgent — спан всей задачи агента. Спаны chat и execute_tool внутри run
// становятся его детьми через ctx: в трейсе видно, на какой итерации агент «заблудился».
func InvokeAgent(ctx context.Context, agent string, run func(ctx context.Context) error) error {
	ctx, span := tracer.Start(ctx, "invoke_agent "+agent,
		trace.WithSpanKind(trace.SpanKindInternal), // агент в нашем процессе; удалённый агент — CLIENT
		trace.WithAttributes(
			attribute.String("gen_ai.operation.name", "invoke_agent"),
			attribute.String("gen_ai.agent.name", agent),
		))
	defer span.End()
	err := run(ctx)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "agent failed")
		span.SetAttributes(attribute.String("error.type", errorType(err)))
	}
	return err
}

// ExecuteTool — спан одного вызова инструмента. Аргументы и результат в атрибуты
// не пишем: по конвенции это opt-in (gen_ai.tool.call.arguments/result), там бывают PII.
func ExecuteTool(ctx context.Context, tool, callID string, fn func(ctx context.Context) (string, error)) (string, error) {
	ctx, span := tracer.Start(ctx, "execute_tool "+tool,
		trace.WithSpanKind(trace.SpanKindInternal),
		trace.WithAttributes(
			attribute.String("gen_ai.operation.name", "execute_tool"),
			attribute.String("gen_ai.tool.name", tool),
			attribute.String("gen_ai.tool.call.id", callID),
			attribute.String("gen_ai.tool.type", "function"),
		))
	defer span.End()
	out, err := fn(ctx)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "tool failed")
		span.SetAttributes(attribute.String("error.type", errorType(err)))
	}
	return out, err
}
