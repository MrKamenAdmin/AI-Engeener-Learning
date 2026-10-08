// Шлюз перед OpenAI-совместимым сервером vLLM (подойдёт и Ollama на :11434).
// Делает пять вещей: публичное имя модели → база или LoRA-адаптер в vLLM, лимит
// одновременных запросов под --max-num-seqs, ретраи на 429/5xx, пока клиенту
// ещё ничего не отправлено, таймаут на первый байт стрима и метрики TTFT/TPOT.
package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math/rand/v2"
	"net/http"
	"strconv"
	"time"
)

type Config struct {
	Upstream    string            // http://vllm:8000
	APIKey      string            // --api-key vLLM; пусто — без авторизации
	Models      map[string]string // публичное имя → имя в vLLM: база или адаптер из --lora-modules
	MaxInFlight int               // ≈ max-num-seqs × число реплик
	QueueWait   time.Duration     // сколько запрос ждёт свободный слот, потом 429
	FirstByte   time.Duration     // стрим: нет первого байта за это время — отменяем попытку и повторяем
	Timeout     time.Duration     // общий потолок на запрос
	MaxRetries  int
	Backoff     time.Duration // база экспоненциального backoff с full jitter
	Observe     func(Stats)   // куда отдать метрики: лог, Prometheus, OTel (модуль 9)
}

type Stats struct {
	Model     string
	Status    int
	Attempts  int
	TTFT      time.Duration // от прихода запроса до первого непустого токена
	TPOT      time.Duration // (последний токен − первый) / (токенов − 1)
	OutTokens int
}

type Gateway struct {
	cfg    Config
	client *http.Client
	slots  chan struct{}
}

func New(cfg Config) *Gateway {
	t := http.DefaultTransport.(*http.Transport).Clone()
	t.MaxIdleConnsPerHost = cfg.MaxInFlight // по умолчанию 2: при 128 параллельных стримах соединения пересоздавались бы
	return &Gateway{cfg: cfg, client: &http.Client{Transport: t}, slots: make(chan struct{}, cfg.MaxInFlight)}
}

func (g *Gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	start := time.Now()
	if r.Method != http.MethodPost || r.URL.Path != "/v1/chat/completions" {
		apiError(w, http.StatusNotFound, "only POST /v1/chat/completions")
		return
	}
	var req map[string]json.RawMessage // остальные поля запроса пробрасываем как есть
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<20)).Decode(&req); err != nil {
		apiError(w, http.StatusBadRequest, "bad JSON: "+err.Error())
		return
	}
	var public string
	var stream bool
	_ = json.Unmarshal(req["model"], &public)
	_ = json.Unmarshal(req["stream"], &stream)
	target, ok := g.cfg.Models[public]
	if !ok {
		apiError(w, http.StatusNotFound, fmt.Sprintf("model %q not found", public))
		return
	}
	req["model"], _ = json.Marshal(target)
	if _, set := req["stream_options"]; stream && !set {
		req["stream_options"] = json.RawMessage(`{"include_usage":true}`) // точное число токенов в последнем чанке
	}
	body, _ := json.Marshal(req)

	st := Stats{Model: public}
	defer func() {
		if g.cfg.Observe != nil {
			g.cfg.Observe(st)
		}
	}()
	ctx, cancel := context.WithTimeout(r.Context(), g.cfg.Timeout)
	defer cancel()
	wait := time.NewTimer(g.cfg.QueueWait)
	defer wait.Stop()
	select { // слот: в vLLM не больше MaxInFlight запросов, остальные ждут здесь, а не в его очереди
	case g.slots <- struct{}{}:
		defer func() { <-g.slots }()
	case <-wait.C:
		st.Status = http.StatusTooManyRequests
		w.Header().Set("Retry-After", "1")
		apiError(w, st.Status, "all slots busy")
		return
	case <-ctx.Done():
		return
	}

	up, attempts, err := g.send(ctx, body, stream)
	st.Attempts = attempts
	if err != nil {
		st.Status = http.StatusBadGateway
		if ctx.Err() == context.DeadlineExceeded {
			st.Status = http.StatusGatewayTimeout
		}
		apiError(w, st.Status, "upstream: "+err.Error())
		return
	}
	defer up.Close()
	st.Status = up.resp.StatusCode
	if stream && st.Status == http.StatusOK {
		pipeSSE(w, up.body, start, &st)
		return
	}
	raw, err := io.ReadAll(up.body)
	if err != nil {
		st.Status = http.StatusBadGateway
		apiError(w, st.Status, "upstream: "+err.Error())
		return
	}
	var resp struct {
		Usage struct {
			CompletionTokens int `json:"completion_tokens"`
		} `json:"usage"`
	}
	if json.Unmarshal(raw, &resp) == nil {
		st.OutTokens = resp.Usage.CompletionTokens
	}
	st.TTFT = time.Since(start) // без стрима пользователь видит первый токен вместе с последним
	w.Header().Set("Content-Type", up.resp.Header.Get("Content-Type"))
	w.WriteHeader(st.Status)
	_, _ = w.Write(raw)
}

type upstream struct {
	resp   *http.Response
	body   *bufio.Reader
	cancel context.CancelFunc
}

func (u *upstream) Close() { u.resp.Body.Close(); u.cancel() }

// send повторяет запрос, пока vLLM отвечает 429/502/503/504 или стрим молчит дольше FirstByte.
// Повторять можно только до первого байта клиенту: после него ответ уже частично отдан.
func (g *Gateway) send(ctx context.Context, body []byte, stream bool) (*upstream, int, error) {
	for attempt := 1; ; attempt++ {
		up, err := g.attempt(ctx, body, stream)
		if err == nil && !retryable(up.resp.StatusCode) {
			return up, attempt, nil
		}
		if attempt > g.cfg.MaxRetries || ctx.Err() != nil {
			return up, attempt, err // последний 429/503 отдаём клиенту как есть
		}
		delay := time.Duration(0)
		if d := g.cfg.Backoff << (attempt - 1); d > 0 {
			delay = rand.N(d)
		}
		if err == nil {
			if s, perr := strconv.Atoi(up.resp.Header.Get("Retry-After")); perr == nil {
				delay = max(delay, time.Duration(s)*time.Second)
			}
			up.Close()
		}
		select {
		case <-time.After(delay):
		case <-ctx.Done():
			return nil, attempt, ctx.Err()
		}
	}
}

func (g *Gateway) attempt(ctx context.Context, body []byte, stream bool) (*upstream, error) {
	actx, cancel := context.WithCancel(ctx) // отмена закрывает соединение, и vLLM освобождает слот и KV-cache
	req, err := http.NewRequestWithContext(actx, http.MethodPost, g.cfg.Upstream+"/v1/chat/completions", bytes.NewReader(body))
	if err != nil {
		cancel()
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if g.cfg.APIKey != "" {
		req.Header.Set("Authorization", "Bearer "+g.cfg.APIKey)
	}
	if stream && g.cfg.FirstByte > 0 {
		t := time.AfterFunc(g.cfg.FirstByte, cancel)
		defer t.Stop() // остановится, как только Peek дождётся первого байта
	}
	resp, err := g.client.Do(req)
	if err != nil {
		cancel()
		return nil, err
	}
	up := &upstream{resp: resp, body: bufio.NewReader(resp.Body), cancel: cancel}
	if _, err := up.body.Peek(1); err != nil && err != io.EOF {
		up.Close()
		return nil, err
	}
	return up, nil
}

func retryable(code int) bool {
	return code == http.StatusTooManyRequests || code == http.StatusBadGateway ||
		code == http.StatusServiceUnavailable || code == http.StatusGatewayTimeout
}

// chunk — то, что нужно шлюзу из SSE-чанка chat.completion.chunk.
type chunk struct {
	Choices []struct {
		Delta struct {
			Content   string `json:"content"`
			Reasoning string `json:"reasoning"` // reasoning-модели; в старых vLLM поле звалось reasoning_content
		} `json:"delta"`
	} `json:"choices"`
	Usage *struct {
		CompletionTokens int `json:"completion_tokens"`
	} `json:"usage"`
}

// pipeSSE пробрасывает стрим построчно и сбрасывает буфер после каждой строки.
func pipeSSE(w http.ResponseWriter, src *bufio.Reader, start time.Time, st *Stats) {
	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	h.Set("Cache-Control", "no-cache")
	h.Set("X-Accel-Buffering", "no") // nginx перед шлюзом не должен копить стрим
	w.WriteHeader(http.StatusOK)
	rc := http.NewResponseController(w)
	var first, last time.Time
	chunks := 0
	for {
		line, err := src.ReadBytes('\n')
		if len(line) > 0 {
			if _, werr := w.Write(line); werr != nil {
				return // клиент ушёл: defer отменит запрос к vLLM, генерация остановится
			}
			_ = rc.Flush()
			if data, ok := bytes.CutPrefix(line, []byte("data:")); ok {
				var c chunk
				if json.Unmarshal(data, &c) == nil { // "[DONE]" не JSON — пропускаем
					if len(c.Choices) > 0 && (c.Choices[0].Delta.Content != "" || c.Choices[0].Delta.Reasoning != "") {
						if first.IsZero() {
							first = time.Now()
						}
						last = time.Now()
						chunks++
					}
					if c.Usage != nil {
						st.OutTokens = c.Usage.CompletionTokens
					}
				}
			}
		}
		if err != nil {
			break
		}
	}
	if st.OutTokens == 0 {
		st.OutTokens = chunks // без usage: чанк ≈ токен, если stream_interval = 1
	}
	if !first.IsZero() {
		st.TTFT = first.Sub(start)
		if st.OutTokens > 1 {
			st.TPOT = last.Sub(first) / time.Duration(st.OutTokens-1)
		}
	}
}

func apiError(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]any{"error": map[string]any{"message": msg, "code": code}})
}
