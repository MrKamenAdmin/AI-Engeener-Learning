package llmmw

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"
)

// Breaker: closed → (Threshold ошибок подряд) → open → (Cooldown) → half-open (1 проба).
// В проде можно взять sony/gobreaker; логика та же.
type Breaker struct {
	mu        sync.Mutex
	fails     int
	openUntil time.Time
	probing   bool
	Threshold int
	Cooldown  time.Duration
}

func (b *Breaker) Allow() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	switch {
	case b.fails < b.Threshold:
		return true // closed
	case time.Now().Before(b.openUntil), b.probing:
		return false // open, или проба уже в полёте
	default:
		b.probing = true // half-open: пропускаем ровно один запрос
		return true
	}
}

func (b *Breaker) Report(failed bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.probing = false
	if !failed {
		b.fails = 0
		return
	}
	if b.fails++; b.fails >= b.Threshold {
		b.openUntil = time.Now().Add(b.Cooldown)
	}
}

type Route struct {
	Name, Model string
	LLM         LLM // уже обёрнут в Retry и Limiter
	Breaker     *Breaker
	Fits        func(Request) bool    // влезет ли контекст, есть ли нужные фичи
	Adapt       func(Request) Request // свой промпт и параметры под модель
}

type Fallback struct{ Routes []Route }

func (f *Fallback) Complete(ctx context.Context, req Request) (*Response, error) {
	var errs []error
	for _, rt := range f.Routes {
		if rt.Fits != nil && !rt.Fits(req) {
			continue
		}
		if !rt.Breaker.Allow() {
			errs = append(errs, fmt.Errorf("%s: circuit open", rt.Name))
			continue
		}
		r := req
		r.Model = rt.Model
		if rt.Adapt != nil {
			r = rt.Adapt(r)
		}
		resp, err := rt.LLM.Complete(ctx, r)
		retryable, _ := Retryable(err)
		rt.Breaker.Report(err != nil && retryable && !isRateLimit(err)) // 429 — наша квота, не авария
		if err == nil {
			resp.Route = rt.Name
			return resp, nil
		}
		if !retryable {
			return nil, err // 400 на A будет 400 и на B: не маскируем баг перебором
		}
		errs = append(errs, fmt.Errorf("%s: %w", rt.Name, err))
		if ctx.Err() != nil {
			break
		}
	}
	return nil, errors.Join(errs...)
}
