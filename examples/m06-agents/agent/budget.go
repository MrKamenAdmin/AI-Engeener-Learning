package agent

import (
	"context"
	"errors"
	"fmt"
	"sync"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/anthropics/anthropic-sdk-go/option"
)

// $ за 1M токенов (вход, выход), на момент написания — сентябрь 2026.
var prices = map[anthropic.Model][2]float64{
	"claude-fable-5-1": {10, 50},
	"claude-opus-5-5":  {4, 20},
	"claude-opus-5":    {5, 25},
	"claude-sonnet-5":  {2, 10},
	"claude-haiku-4-5": {1, 5},
}

func price(m anthropic.Model) [2]float64 {
	if p, ok := prices[m]; ok {
		return p
	}
	return prices["claude-fable-5-1"] // неизвестная модель — по самой дорогой, бюджет не должен молча обнуляться
}

// Cost — стоимость одного вызова по usage: чтение кэша 0,1×, запись 1,25× (TTL 5 минут).
func Cost(m anthropic.Model, u anthropic.Usage) float64 {
	p := price(m)
	in := float64(u.InputTokens) + 0.1*float64(u.CacheReadInputTokens) + 1.25*float64(u.CacheCreationInputTokens)
	return (in*p[0] + float64(u.OutputTokens)*p[1]) / 1e6
}

var ErrBudget = errors.New("agent: budget exhausted")

// Budgeted — Model с лимитом в долларах. Это декоратор, как middleware над http.RoundTripper:
// цикл о нём не знает. Вложенные Budgeted образуют иерархию: субагент тратит свой лимит
// и одновременно лимит родителя.
type Budgeted struct {
	Model
	MaxUSD float64

	mu    sync.Mutex
	spent float64
}

func (b *Budgeted) New(ctx context.Context, p anthropic.MessageNewParams, opts ...option.RequestOption) (*anthropic.Message, error) {
	if spent := b.Spent(); spent >= b.MaxUSD {
		return nil, fmt.Errorf("%w: spent $%.4f of $%.2f", ErrBudget, spent, b.MaxUSD)
	}
	// Упрощение: стоимость известна только по usage после вызова, поэтому лимит может быть
	// превышен на один вызов (на N — при N параллельных субагентах). Жёсткий лимит —
	// оценить вызов заранее через count_tokens и зарезервировать сумму под мьютексом.
	resp, err := b.Model.New(ctx, p, opts...)
	if err != nil {
		return nil, err
	}
	b.mu.Lock()
	b.spent += Cost(p.Model, resp.Usage)
	b.mu.Unlock()
	return resp, nil
}

func (b *Budgeted) Spent() float64 {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.spent
}
