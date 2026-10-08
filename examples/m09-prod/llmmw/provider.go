package llmmw

import (
	"context"
	"strings"
	"time"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/anthropics/anthropic-sdk-go/option"
)

type Func func(ctx context.Context, req Request) (*Response, error)

func (f Func) Complete(ctx context.Context, req Request) (*Response, error) { return f(ctx, req) }

// Anthropic — нижний слой. Ретраи SDK выключены: они живут в нашем Retry.
func Anthropic() LLM {
	c := anthropic.NewClient(option.WithMaxRetries(0))
	return Func(func(ctx context.Context, req Request) (*Response, error) {
		msg, err := c.Messages.New(ctx, anthropic.MessageNewParams{
			Model:     anthropic.Model(req.Model),
			MaxTokens: req.MaxTokens,
			System:    []anthropic.TextBlockParam{{Text: req.System}},
			Messages:  req.Messages,
		})
		if err != nil {
			return nil, err
		}
		out := &Response{
			Model: string(msg.Model), StopReason: string(msg.StopReason),
			InputTokens: msg.Usage.InputTokens, OutputTokens: msg.Usage.OutputTokens,
			CacheReadTokens:  msg.Usage.CacheReadInputTokens,
			CacheWriteTokens: msg.Usage.CacheCreationInputTokens,
		}
		for _, b := range msg.Content {
			if t, ok := b.AsAny().(anthropic.TextBlock); ok {
				out.Text += t.Text
			}
		}
		return out, nil
	})
}

func isRateLimit(err error) bool {
	s, _, ok := httpStatus(err)
	return ok && s == 429
}

// lastUserText — текст последнего user-сообщения.
func lastUserText(r Request) string {
	for i := len(r.Messages) - 1; i >= 0; i-- {
		if r.Messages[i].Role == anthropic.MessageParamRoleUser {
			return textOf(r.Messages[i])
		}
	}
	return ""
}

// textOf склеивает текстовые блоки сообщения.
func textOf(m anthropic.MessageParam) string {
	var sb strings.Builder
	for _, b := range m.Content {
		if b.OfText != nil {
			sb.WriteString(b.OfText.Text)
		}
	}
	return sb.String()
}

func New(selfHosted LLM, cache *SemCache, meter *Metered) LLM {
	api := Anthropic()
	budget := &Budget{Ratio: 0.2, Max: 50}
	route := func(name, model string, base LLM, rpm, itpm, otpm float64) Route {
		return Route{
			Name: name, Model: model,
			Breaker: &Breaker{Threshold: 5, Cooldown: 30 * time.Second},
			LLM: &Retry{MaxRetries: 2, Base: 250 * time.Millisecond, Cap: 4 * time.Second, Budget: budget,
				Next: NewLimiter(base, rpm, itpm, otpm)},
		}
	}
	fb := &Fallback{Routes: []Route{
		route("primary", "claude-opus-5", api, 4000, 2_000_000, 400_000),
		route("smaller", "claude-sonnet-5", api, 4000, 2_000_000, 400_000),
		route("self-hosted", "qwen3-8b", selfHosted, 600, 1_000_000, 200_000),
	}}
	meter.Next = fb
	cache.Next = meter
	return &Traced{Provider: "anthropic", Next: cache}
}
