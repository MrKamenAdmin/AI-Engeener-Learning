package llmmw

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/anthropics/anthropic-sdk-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
)

func ask(text string) Request {
	return Request{Tenant: "acme", MaxTokens: 500,
		Messages: []anthropic.MessageParam{anthropic.NewUserMessage(anthropic.NewTextBlock(text))}}
}

// fake отвечает заданным текстом и считает вызовы.
func fake(text string, calls *int) LLM {
	return Func(func(ctx context.Context, req Request) (*Response, error) {
		*calls++
		return &Response{Text: text, StopReason: "end_turn"}, nil
	})
}

func TestRouter(t *testing.T) {
	validJSON := func(r *Response) bool { return json.Valid([]byte(r.Text)) }
	cases := []struct {
		name, q, cheapOut      string
		wantCheap, wantStrong  int
		wantText, wantCounterK string
	}{
		{"простое → дешёвая", "Какие часы работы поддержки?", `{"a":1}`, 1, 0, `{"a":1}`, "cheap"},
		{"сложное → сильная", "Сравни тарифы Pro и Team и объясни, почему счёт вырос?", `{}`, 0, 1, "strong", "strong"},
		{"невалидный ответ → эскалация", "Какой у меня тариф?", "не JSON", 1, 1, "strong", "escalated"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			var nc, ns int
			r := &Router{Cheap: fake(c.cheapOut, &nc), Strong: fake("strong", &ns),
				Classify: Heuristic, Threshold: 0.3, Accept: validJSON}
			before := counter(c.wantCounterK)
			resp, err := r.Complete(context.Background(), ask(c.q))
			if err != nil {
				t.Fatal(err)
			}
			if nc != c.wantCheap || ns != c.wantStrong || resp.Text != c.wantText {
				t.Fatalf("cheap=%d strong=%d text=%q", nc, ns, resp.Text)
			}
			if counter(c.wantCounterK) != before+1 {
				t.Fatalf("метрика %s не выросла", c.wantCounterK)
			}
		})
	}
}

func TestRouterClassifierErrorGoesStrong(t *testing.T) {
	var nc, ns int
	r := &Router{Cheap: fake("c", &nc), Strong: fake("s", &ns), Threshold: 0.5,
		Classify: func(context.Context, Request) (float64, error) { return 0, errors.New("haiku down") }}
	if resp, _ := r.Complete(context.Background(), ask("привет")); resp.Text != "s" || nc != 0 {
		t.Fatalf("ошибка классификатора должна вести на сильную модель: %q cheap=%d", resp.Text, nc)
	}
}

func counter(k string) int64 {
	if v := routed.Get(k); v != nil {
		var n int64
		fmt.Sscan(v.String(), &n)
		return n
	}
	return 0
}

func TestFlagRollout(t *testing.T) {
	f := Flag{Key: "answer", Control: Variant{Name: "v12", Model: "claude-opus-5"},
		Treatment: Variant{Name: "v13", Model: "claude-sonnet-5"}, Percent: 10}
	in10 := map[string]bool{}
	for i := range 20000 {
		u := fmt.Sprintf("u%d", i)
		if f.Evaluate("acme", u).Name == "v13" {
			in10[u] = true
		}
		if f.Evaluate("acme", u) != f.Evaluate("acme", u) {
			t.Fatal("вариант должен быть детерминирован")
		}
	}
	if share := float64(len(in10)) / 20000; share < 0.09 || share > 0.11 {
		t.Fatalf("доля Treatment %.3f, ждали ≈ 0.10", share)
	}
	f.Percent = 30
	for u := range in10 {
		if f.Evaluate("acme", u).Name != "v13" {
			t.Fatalf("%s выпал из Treatment при росте процента", u)
		}
	}
	f.Tenants = map[string]bool{"bank": false}
	if f.Evaluate("bank", "u1").Name != "v12" {
		t.Fatal("таргетинг: тенант bank исключён")
	}
	f.Killed = true
	for u := range in10 {
		if f.Evaluate("acme", u).Name != "v12" {
			t.Fatal("kill switch должен вернуть всех на Control")
		}
	}
}

func TestRegistryKeepsOldConfigOnError(t *testing.T) {
	var r Registry
	def := Variant{Name: "default", Model: "claude-opus-5"}
	good := `[{"key":"answer","percent":100,"control":{"name":"v12","model":"claude-opus-5"},"treatment":{"name":"v13","model":"claude-sonnet-5"}}]`
	if err := r.Load([]byte(good)); err != nil {
		t.Fatal(err)
	}
	if err := r.Load([]byte(`[{"key":"answer","percent":250,"control":{"model":"x"},"treatment":{"model":"y"}}]`)); err == nil {
		t.Fatal("percent 250 должен отклоняться")
	}
	if v := r.Variant("answer", "acme", "u1", def); v.Name != "v13" {
		t.Fatalf("после битого конфига должен работать старый, получили %q", v.Name)
	}
	if v := r.Variant("unknown", "acme", "u1", def); v != def {
		t.Fatal("неизвестный флаг → default")
	}
}

func TestOpenAICompat(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Model    string `json:"model"`
			Messages []struct{ Role, Content string }
		}
		json.NewDecoder(r.Body).Decode(&body)
		switch {
		case body.Model == "down":
			w.Header().Set("retry-after", "2")
			w.WriteHeader(http.StatusServiceUnavailable)
		case body.Model == "bad":
			w.WriteHeader(http.StatusBadRequest)
		default:
			if len(body.Messages) != 2 || body.Messages[0].Role != "system" || body.Messages[1].Content != "ping" {
				t.Errorf("неожиданные messages: %+v", body.Messages)
			}
			fmt.Fprint(w, `{"model":"qwen3-8b","choices":[{"message":{"role":"assistant","content":"pong"},"finish_reason":"length"}],"usage":{"prompt_tokens":12,"completion_tokens":3}}`)
		}
	}))
	defer srv.Close()
	llm := OpenAICompat(srv.Client(), srv.URL, "k")

	req := ask("ping")
	req.System, req.Model = "Ты бот.", "qwen3-8b"
	resp, err := llm.Complete(context.Background(), req)
	if err != nil || resp.Text != "pong" || resp.StopReason != "max_tokens" || resp.InputTokens != 12 {
		t.Fatalf("resp=%+v err=%v", resp, err)
	}
	req.Model = "down"
	_, err = llm.Complete(context.Background(), req)
	if ok, ra := Retryable(err); !ok || ra != 2*time.Second || errorType(err) != "503" {
		t.Fatalf("503 второго провайдера должен ретраиться с retry-after: ok=%v ra=%v", ok, ra)
	}
	req.Model = "bad"
	_, err = llm.Complete(context.Background(), req)
	if ok, _ := Retryable(err); ok {
		t.Fatal("400 не ретраится")
	}
}

func TestAgentSpansAndMetrics(t *testing.T) {
	rec := tracetest.NewSpanRecorder()
	otel.SetTracerProvider(sdktrace.NewTracerProvider(sdktrace.WithSpanProcessor(rec)))
	reader := metric.NewManualReader()
	otel.SetMeterProvider(metric.NewMeterProvider(metric.WithReader(reader)))

	llm := &Traced{Provider: "anthropic", Next: Func(func(ctx context.Context, req Request) (*Response, error) {
		return &Response{Model: req.Model, StopReason: "end_turn", InputTokens: 10, OutputTokens: 5}, nil
	})}
	err := InvokeAgent(context.Background(), "support", func(ctx context.Context) error {
		req := ask("где заказ?")
		req.Model = "claude-opus-5"
		if _, err := llm.Complete(ctx, req); err != nil {
			return err
		}
		_, err := ExecuteTool(ctx, "lookup_order", "toolu_1", func(context.Context) (string, error) { return "в пути", nil })
		return err
	})
	if err != nil {
		t.Fatal(err)
	}

	spans := rec.Ended()
	names := map[string]sdktrace.ReadOnlySpan{}
	for _, s := range spans {
		names[s.Name()] = s
	}
	agent, chat, tool := names["invoke_agent support"], names["chat claude-opus-5"], names["execute_tool lookup_order"]
	if agent == nil || chat == nil || tool == nil {
		t.Fatalf("спаны: %v", keys(names))
	}
	for _, child := range []sdktrace.ReadOnlySpan{chat, tool} {
		if child.Parent().SpanID() != agent.SpanContext().SpanID() {
			t.Fatalf("%s должен быть ребёнком invoke_agent", child.Name())
		}
	}

	var rm metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &rm); err != nil {
		t.Fatal(err)
	}
	found := map[string]bool{}
	for _, sm := range rm.ScopeMetrics {
		for _, m := range sm.Metrics {
			found[m.Name] = true
		}
	}
	for _, want := range []string{"gen_ai.client.operation.duration", "gen_ai.client.token.usage"} {
		if !found[want] {
			t.Fatalf("нет метрики %s, есть %v", want, found)
		}
	}
}

func keys[M ~map[string]V, V any](m M) string {
	var ks []string
	for k := range m {
		ks = append(ks, k)
	}
	return strings.Join(ks, ", ")
}
