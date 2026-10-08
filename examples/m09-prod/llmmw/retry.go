package llmmw

import (
	"context"
	"errors"
	"math/rand/v2"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/anthropics/anthropic-sdk-go"
)

type Request struct {
	Tenant, Model, System string
	Messages              []anthropic.MessageParam
	MaxTokens             int64
	EstInputTokens        int // для лимитера ITPM
}

type Response struct {
	Text, Model, StopReason, Route    string
	InputTokens, OutputTokens         int64
	CacheReadTokens, CacheWriteTokens int64
}

type LLM interface {
	Complete(ctx context.Context, req Request) (*Response, error)
}

// Retryable: повтор может закончиться иначе. Второе значение — retry-after от сервера.
func Retryable(err error) (bool, time.Duration) {
	if s, ra, ok := httpStatus(err); ok {
		switch {
		case s == 408, s == 409, s == 429, s >= 500: // 529 overloaded входит сюда
			return true, ra
		default: // 400/401/403/404/413: повтор даст то же самое
			return false, 0
		}
	}
	var ne net.Error
	return errors.As(err, &ne) || errors.Is(err, context.DeadlineExceeded), 0 // таймаут попытки
}

// httpStatus достаёт HTTP-статус из ошибки любого провайдера: Anthropic SDK или своего адаптера.
func httpStatus(err error) (status int, ra time.Duration, ok bool) {
	var apierr *anthropic.Error
	if errors.As(err, &apierr) {
		return apierr.StatusCode, retryAfter(apierr.Response), true
	}
	var herr *HTTPError
	if errors.As(err, &herr) {
		return herr.StatusCode, herr.RetryAfter, true
	}
	return 0, 0, false
}

func retryAfter(r *http.Response) time.Duration {
	if r == nil {
		return 0
	}
	sec, _ := strconv.Atoi(r.Header.Get("retry-after"))
	return time.Duration(sec) * time.Second
}

// Budget: ретраев не больше Ratio от числа запросов (как retry budget в Finagle/gRPC).
type Budget struct {
	mu            sync.Mutex
	tokens, Ratio float64
	Max           float64
}

func (b *Budget) onRequest() { b.mu.Lock(); b.tokens = min(b.Max, b.tokens+b.Ratio); b.mu.Unlock() }
func (b *Budget) tryRetry() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

type Retry struct {
	Next       LLM
	MaxRetries int
	Base, Cap  time.Duration
	Budget     *Budget
}

func (r *Retry) Complete(ctx context.Context, req Request) (*Response, error) {
	r.Budget.onRequest()
	for attempt := 0; ; attempt++ {
		resp, err := r.Next.Complete(ctx, req)
		if err == nil || ctx.Err() != nil {
			return resp, err // родительский дедлайн истёк: не ретраим
		}
		ok, ra := Retryable(err)
		if !ok || attempt == r.MaxRetries || !r.Budget.tryRetry() {
			return nil, err
		}
		d := min(r.Cap, r.Base<<attempt)
		d = max(time.Duration(rand.Int64N(int64(d)+1)), ra) // full jitter, но не раньше retry-after
		if dl, ok := ctx.Deadline(); ok && time.Until(dl) < d {
			return nil, err // не успеем: пусть решает fallback выше
		}
		select {
		case <-ctx.Done():
			return nil, errors.Join(err, ctx.Err())
		case <-time.After(d):
		}
	}
}
