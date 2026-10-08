package llmmw

import (
	"context"
	"errors"

	"golang.org/x/time/rate"
)

// Limiter: три token bucket'а под RPM/ITPM/OTPM провайдера, выставленные на ~90% лимита.
type Limiter struct {
	Next            LLM
	RPM, ITPM, OTPM *rate.Limiter
}

func NewLimiter(next LLM, rpm, itpm, otpm float64) *Limiter {
	per := func(v float64) *rate.Limiter { return rate.NewLimiter(rate.Limit(v*0.9/60), int(v*0.9/6)) }
	return &Limiter{Next: next, RPM: per(rpm), ITPM: per(itpm), OTPM: per(otpm)}
}

func (l *Limiter) Complete(ctx context.Context, req Request) (*Response, error) {
	// WaitN сразу вернёт ошибку, если дедлайн ctx не дождётся токенов: бесплатный load shedding.
	if err := l.RPM.Wait(ctx); err != nil {
		return nil, err
	}
	if err := l.ITPM.WaitN(ctx, min(req.EstInputTokens, l.ITPM.Burst())); err != nil {
		return nil, err
	}
	if err := l.OTPM.WaitN(ctx, min(int(req.MaxTokens), l.OTPM.Burst())); err != nil {
		return nil, err
	}
	return l.Next.Complete(ctx, req)
}

// Цены $ за 1M токенов на момент написания (сентябрь 2026).
var prices = map[string]struct{ In, Out float64 }{
	"claude-opus-5":    {5, 25},
	"claude-sonnet-5":  {2, 10},
	"claude-haiku-4-5": {1, 5},
}

func CostUSD(r *Response) float64 {
	p, ok := prices[r.Model]
	if !ok {
		return 0 // неизвестная модель: алертим отдельно, а не падаем
	}
	in := float64(r.InputTokens) + 0.1*float64(r.CacheReadTokens) + 1.25*float64(r.CacheWriteTokens)
	return (in*p.In + float64(r.OutputTokens)*p.Out) / 1e6
}

var ErrQuota = errors.New("llm: tenant budget exceeded")

// Metered: проверка квоты тенанта до вызова и учёт фактической стоимости после.
type Metered struct {
	Next  LLM
	Spent func(ctx context.Context, tenant string) (usd float64, err error) // напр. Redis GET
	Add   func(ctx context.Context, tenant string, usd float64) error       // INCRBYFLOAT + EXPIRE
	Limit func(tenant string) float64
}

func (m *Metered) Complete(ctx context.Context, req Request) (*Response, error) {
	spent, err := m.Spent(ctx, req.Tenant)
	if err == nil && spent >= m.Limit(req.Tenant) {
		return nil, ErrQuota // при ошибке Redis — fail-open: квота мягкая, логируем
	}
	resp, err := m.Next.Complete(ctx, req)
	if err != nil {
		return nil, err
	}
	_ = m.Add(ctx, req.Tenant, CostUSD(resp))
	return resp, nil
}
