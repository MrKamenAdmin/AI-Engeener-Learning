package llmmw

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"
)

// HTTPError — ошибка адаптера в той же классификации, что и ошибки Anthropic SDK:
// по ней работают Retryable, breaker и метрика error.type.
type HTTPError struct {
	StatusCode int
	RetryAfter time.Duration
	Body       string
}

func (e *HTTPError) Error() string { return fmt.Sprintf("http %d: %s", e.StatusCode, e.Body) }

// OpenAICompat — звено под второго провайдера или self-hosted (vLLM, Ollama)
// через OpenAI-совместимый Chat Completions. Только текст, без tools: для fallback этого хватает.
func OpenAICompat(hc *http.Client, baseURL, apiKey string) LLM {
	type msg struct {
		Role    string `json:"role"`
		Content string `json:"content"`
	}
	return Func(func(ctx context.Context, req Request) (*Response, error) {
		msgs := []msg{{Role: "system", Content: req.System}}
		for _, m := range req.Messages {
			msgs = append(msgs, msg{Role: string(m.Role), Content: textOf(m)})
		}
		body, err := json.Marshal(map[string]any{"model": req.Model, "messages": msgs, "max_tokens": req.MaxTokens})
		if err != nil {
			return nil, err
		}
		hreq, err := http.NewRequestWithContext(ctx, http.MethodPost, baseURL+"/chat/completions", bytes.NewReader(body))
		if err != nil {
			return nil, err
		}
		hreq.Header.Set("Content-Type", "application/json")
		hreq.Header.Set("Authorization", "Bearer "+apiKey)
		resp, err := hc.Do(hreq)
		if err != nil {
			return nil, err // net.Error: Retryable разберётся
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			b, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
			sec, _ := strconv.Atoi(resp.Header.Get("retry-after"))
			return nil, &HTTPError{StatusCode: resp.StatusCode, RetryAfter: time.Duration(sec) * time.Second, Body: string(b)}
		}
		var out struct {
			Model   string `json:"model"`
			Choices []struct {
				Message      msg    `json:"message"`
				FinishReason string `json:"finish_reason"`
			} `json:"choices"`
			Usage struct {
				PromptTokens     int64 `json:"prompt_tokens"`
				CompletionTokens int64 `json:"completion_tokens"`
			} `json:"usage"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
			return nil, err
		}
		if len(out.Choices) == 0 {
			return nil, &HTTPError{StatusCode: http.StatusBadGateway, Body: "no choices"}
		}
		c := out.Choices[0]
		// stop_reason приводим к словарю Anthropic: остальная цепочка (кэш, метрики) знает только его.
		stop := map[string]string{"stop": "end_turn", "length": "max_tokens", "content_filter": "refusal"}[c.FinishReason]
		return &Response{Text: c.Message.Content, Model: out.Model, StopReason: stop,
			InputTokens: out.Usage.PromptTokens, OutputTokens: out.Usage.CompletionTokens}, nil
	})
}
