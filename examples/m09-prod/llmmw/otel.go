package llmmw

import (
	"context"
	"strconv"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/trace"
)

var (
	tracer = otel.Tracer("llmmw")
	meter  = otel.Meter("llmmw")
	// Границы бакетов — рекомендованные конвенцией: токены растут степенями 4, секунды — удвоением.
	tokenUsage, _ = meter.Int64Histogram("gen_ai.client.token.usage", metric.WithUnit("{token}"),
		metric.WithExplicitBucketBoundaries(1, 4, 16, 64, 256, 1024, 4096, 16384, 65536, 262144, 1048576, 4194304, 16777216, 67108864))
	opDuration, _ = meter.Float64Histogram("gen_ai.client.operation.duration", metric.WithUnit("s"),
		metric.WithExplicitBucketBoundaries(0.01, 0.02, 0.04, 0.08, 0.16, 0.32, 0.64, 1.28, 2.56, 5.12, 10.24, 20.48, 40.96, 81.92))
)

type Traced struct {
	Next     LLM
	Provider string // "anthropic"
}

func (t *Traced) Complete(ctx context.Context, req Request) (*Response, error) {
	start := time.Now()
	ctx, span := tracer.Start(ctx, "chat "+req.Model,
		trace.WithSpanKind(trace.SpanKindClient),
		trace.WithAttributes(
			attribute.String("gen_ai.operation.name", "chat"),
			attribute.String("gen_ai.provider.name", t.Provider),
			attribute.String("gen_ai.request.model", req.Model),
			attribute.Int64("gen_ai.request.max_tokens", req.MaxTokens),
			attribute.String("app.tenant", req.Tenant), // свой namespace для не-конвенционных полей
		))
	defer span.End()

	common := []attribute.KeyValue{
		attribute.String("gen_ai.operation.name", "chat"),
		attribute.String("gen_ai.provider.name", t.Provider),
		attribute.String("gen_ai.request.model", req.Model),
	}
	resp, err := t.Next.Complete(ctx, req)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "llm call failed")
		span.SetAttributes(attribute.String("error.type", errorType(err)))
		opDuration.Record(ctx, time.Since(start).Seconds(),
			metric.WithAttributes(append(common, attribute.String("error.type", errorType(err)))...))
		return nil, err
	}
	span.SetAttributes(
		attribute.String("gen_ai.response.model", resp.Model),
		attribute.StringSlice("gen_ai.response.finish_reasons", []string{resp.StopReason}),
		attribute.Int64("gen_ai.usage.input_tokens", resp.InputTokens),
		attribute.Int64("gen_ai.usage.output_tokens", resp.OutputTokens),
		attribute.Int64("gen_ai.usage.cache_read.input_tokens", resp.CacheReadTokens),
		attribute.Int64("gen_ai.usage.cache_creation.input_tokens", resp.CacheWriteTokens),
		attribute.String("app.llm.route", resp.Route),
		attribute.Float64("app.llm.cost_usd", CostUSD(resp)),
	)
	common = append(common, attribute.String("gen_ai.response.model", resp.Model))
	opDuration.Record(ctx, time.Since(start).Seconds(), metric.WithAttributes(common...))
	tokenUsage.Record(ctx, resp.InputTokens, metric.WithAttributes(append(common, attribute.String("gen_ai.token.type", "input"))...))
	tokenUsage.Record(ctx, resp.OutputTokens, metric.WithAttributes(append(common, attribute.String("gen_ai.token.type", "output"))...))
	// Текст промпта и ответа в атрибуты НЕ пишем: только opt-in, с маскированием и сэмплированием.
	return resp, nil
}

func errorType(err error) string {
	if s, _, ok := httpStatus(err); ok {
		return strconv.Itoa(s)
	}
	return "_OTHER" // значение по конвенции OTel для неклассифицированных ошибок
}
