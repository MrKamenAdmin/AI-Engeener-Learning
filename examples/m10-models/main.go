// go run . -upstream http://localhost:8000 -models "clf=ticket-clf,chat=Qwen/Qwen3-8B" -max-inflight 128
// Локально без GPU: -upstream http://localhost:11434 -models "chat=qwen3:8b" (Ollama).
package main

import (
	"flag"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"time"
)

func main() {
	listen := flag.String("listen", ":8080", "адрес шлюза")
	upstream := flag.String("upstream", "http://localhost:8000", "vLLM или Ollama")
	models := flag.String("models", "chat=Qwen/Qwen3-8B", "публичное=имя_в_vLLM через запятую")
	inflight := flag.Int("max-inflight", 128, "≈ --max-num-seqs × реплики")
	flag.Parse()

	m := map[string]string{}
	for _, kv := range strings.Split(*models, ",") {
		if pub, target, ok := strings.Cut(kv, "="); ok {
			m[pub] = target
		}
	}
	gw := New(Config{
		Upstream: *upstream, APIKey: os.Getenv("VLLM_API_KEY"), Models: m,
		MaxInFlight: *inflight, QueueWait: 2 * time.Second,
		FirstByte: 10 * time.Second, Timeout: 5 * time.Minute,
		MaxRetries: 2, Backoff: 200 * time.Millisecond,
		Observe: func(s Stats) {
			slog.Info("llm", "model", s.Model, "status", s.Status, "attempts", s.Attempts,
				"ttft_ms", s.TTFT.Milliseconds(), "tpot_ms", s.TPOT.Milliseconds(), "out_tokens", s.OutTokens)
		},
	})
	srv := &http.Server{Addr: *listen, Handler: gw, ReadHeaderTimeout: 5 * time.Second}
	slog.Info("gateway", "listen", *listen, "upstream", *upstream, "models", m)
	if err := srv.ListenAndServe(); err != nil {
		slog.Error("serve", "err", err)
		os.Exit(1)
	}
}
