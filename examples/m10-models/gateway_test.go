package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// fakeVLLM — httptest-сервер, который отвечает как vLLM; handle решает, что вернуть на n-й вызов.
func fakeVLLM(t *testing.T, handle func(n int, w http.ResponseWriter, body map[string]any)) (*httptest.Server, *atomic.Int32) {
	t.Helper()
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" || r.Header.Get("Authorization") != "Bearer k" {
			http.Error(w, "bad request to upstream", http.StatusBadRequest)
			return
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		handle(int(calls.Add(1)), w, body)
	}))
	t.Cleanup(srv.Close)
	return srv, &calls
}

func newGW(upstream string, stats *[]Stats) *Gateway {
	return New(Config{
		Upstream: upstream, APIKey: "k",
		Models:      map[string]string{"clf": "ticket-clf", "chat": "Qwen/Qwen3-8B"},
		MaxInFlight: 4, QueueWait: 50 * time.Millisecond, FirstByte: 200 * time.Millisecond,
		Timeout: 5 * time.Second, MaxRetries: 2, Backoff: time.Millisecond,
		Observe: func(s Stats) { *stats = append(*stats, s) },
	})
}

func post(t *testing.T, gw http.Handler, body string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	gw.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body)))
	return rec
}

func TestNonStreamMapsModelToAdapter(t *testing.T) {
	up, _ := fakeVLLM(t, func(_ int, w http.ResponseWriter, body map[string]any) {
		if body["model"] != "ticket-clf" {
			t.Errorf("upstream model = %v, want LoRA adapter ticket-clf", body["model"])
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"choices":[{"message":{"role":"assistant","content":"billing"}}],"usage":{"completion_tokens":1}}`)
	})
	var stats []Stats
	rec := post(t, newGW(up.URL, &stats), `{"model":"clf","messages":[{"role":"user","content":"списали дважды"}]}`)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), "billing") {
		t.Fatalf("got %d %s", rec.Code, rec.Body)
	}
	if len(stats) != 1 || stats[0].OutTokens != 1 || stats[0].Attempts != 1 {
		t.Fatalf("stats = %+v", stats)
	}
}

func TestUnknownModel(t *testing.T) {
	var stats []Stats
	rec := post(t, newGW("http://127.0.0.1:1", &stats), `{"model":"gpt-9","messages":[]}`)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("code = %d", rec.Code)
	}
}

func TestStreamPassthroughAndMetrics(t *testing.T) {
	up, _ := fakeVLLM(t, func(_ int, w http.ResponseWriter, body map[string]any) {
		if so, _ := body["stream_options"].(map[string]any); so["include_usage"] != true {
			t.Errorf("gateway must request usage in stream, got %v", body["stream_options"])
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fl := w.(http.Flusher)
		send := func(s string) { fmt.Fprintf(w, "data: %s\n\n", s); fl.Flush() }
		send(`{"choices":[{"delta":{"role":"assistant","content":""}}]}`)
		time.Sleep(30 * time.Millisecond) // prefill
		for _, tok := range []string{"При", "вет", "!"} {
			send(fmt.Sprintf(`{"choices":[{"delta":{"content":%q}}]}`, tok))
			time.Sleep(10 * time.Millisecond) // decode
		}
		send(`{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":3}}`)
		send("[DONE]")
	})
	var stats []Stats
	rec := post(t, newGW(up.URL, &stats), `{"model":"chat","stream":true,"messages":[{"role":"user","content":"привет"}]}`)
	out := rec.Body.String()
	if rec.Code != 200 || rec.Header().Get("Content-Type") != "text/event-stream" {
		t.Fatalf("got %d %q", rec.Code, rec.Header().Get("Content-Type"))
	}
	if strings.Index(out, `"При"`) > strings.Index(out, `"вет"`) || !strings.HasSuffix(out, "data: [DONE]\n\n") {
		t.Fatalf("stream is not passed through in order:\n%s", out)
	}
	s := stats[0]
	if s.OutTokens != 3 || s.TTFT < 30*time.Millisecond || s.TPOT < 5*time.Millisecond || s.TPOT > s.TTFT {
		t.Fatalf("stats = %+v", s)
	}
}

func TestRetryOn503ThenOK(t *testing.T) {
	up, calls := fakeVLLM(t, func(n int, w http.ResponseWriter, _ map[string]any) {
		if n == 1 {
			http.Error(w, `{"error":"overloaded"}`, http.StatusServiceUnavailable)
			return
		}
		fmt.Fprint(w, `{"choices":[],"usage":{"completion_tokens":5}}`)
	})
	var stats []Stats
	rec := post(t, newGW(up.URL, &stats), `{"model":"chat","messages":[]}`)
	if rec.Code != 200 || calls.Load() != 2 || stats[0].Attempts != 2 {
		t.Fatalf("code %d, calls %d, stats %+v", rec.Code, calls.Load(), stats)
	}
}

func TestRetriesExhaustedReturns429(t *testing.T) {
	up, calls := fakeVLLM(t, func(_ int, w http.ResponseWriter, _ map[string]any) {
		http.Error(w, `{"error":"rate limited"}`, http.StatusTooManyRequests)
	})
	var stats []Stats
	rec := post(t, newGW(up.URL, &stats), `{"model":"chat","messages":[]}`)
	if rec.Code != http.StatusTooManyRequests || calls.Load() != 3 { // 1 + MaxRetries
		t.Fatalf("code %d, calls %d", rec.Code, calls.Load())
	}
}

func TestNoFirstByteRetries(t *testing.T) {
	up, calls := fakeVLLM(t, func(n int, w http.ResponseWriter, _ map[string]any) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.(http.Flusher).Flush() // заголовки ушли, а токенов нет: реплика зависла на prefill
		if n == 1 {
			time.Sleep(time.Second)
			return
		}
		fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n")
	})
	var stats []Stats
	rec := post(t, newGW(up.URL, &stats), `{"model":"chat","stream":true,"messages":[]}`)
	if rec.Code != 200 || calls.Load() != 2 || !strings.Contains(rec.Body.String(), `"ok"`) {
		t.Fatalf("code %d, calls %d, body %s", rec.Code, calls.Load(), rec.Body)
	}
}

func TestConcurrencyLimit(t *testing.T) {
	release := make(chan struct{})
	up, _ := fakeVLLM(t, func(_ int, w http.ResponseWriter, _ map[string]any) {
		<-release
		fmt.Fprint(w, `{"choices":[]}`)
	})
	var stats []Stats
	gw := newGW(up.URL, &stats)
	gw.slots = make(chan struct{}, 1) // один слот, как --max-num-seqs 1
	gw.cfg.Observe = nil              // два запроса параллельно: не пишем в общий слайс
	done := make(chan int)
	go func() { done <- post(t, gw, `{"model":"chat","messages":[]}`).Code }()
	for len(gw.slots) == 0 { // ждём, пока первый запрос займёт слот
		time.Sleep(time.Millisecond)
	}
	rec := post(t, gw, `{"model":"chat","messages":[]}`)
	close(release)
	if rec.Code != http.StatusTooManyRequests || rec.Header().Get("Retry-After") == "" {
		t.Fatalf("second request: %d, want 429 with Retry-After", rec.Code)
	}
	if code := <-done; code != 200 {
		t.Fatalf("first request: %d", code)
	}
}
