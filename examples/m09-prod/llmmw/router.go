package llmmw

import (
	"context"
	"expvar"
	"strings"
	"unicode/utf8"

	"github.com/anthropics/anthropic-sdk-go"
)

// Classifier оценивает сложность запроса в [0, 1].
type Classifier func(ctx context.Context, req Request) (float64, error)

// Heuristic — бесплатный классификатор на признаках текста. Веса и слова подбираются
// на размеченной выборке из логов, а не на глаз.
// ponytail: словарь признаков; когда его станет мало — ModelClassifier или свой маленький классификатор.
func Heuristic(_ context.Context, req Request) (float64, error) {
	q := strings.ToLower(lastUserText(req))
	score := 0.0
	switch n := utf8.RuneCountInString(q); {
	case n > 1500:
		score += 0.4
	case n > 400:
		score += 0.2
	}
	for _, w := range []string{"почему", "сравни", "проанализируй", "спроектируй", "оптимизируй", "```"} {
		if strings.Contains(q, w) {
			score += 0.3
		}
	}
	if strings.Count(q, "?") > 1 {
		score += 0.15 // несколько вопросов в одном
	}
	if len(req.Messages) > 6 {
		score += 0.15 // длинный диалог: контекст запутаннее
	}
	return min(score, 1), nil
}

// ModelClassifier спрашивает дешёвую модель «simple или complex». Это +200–400 мс и доли цента,
// поэтому зовите её только в «серой зоне» эвристики, а не на каждый запрос.
func ModelClassifier(cheap LLM) Classifier {
	return func(ctx context.Context, req Request) (float64, error) {
		resp, err := cheap.Complete(ctx, Request{
			Tenant: req.Tenant, Model: "claude-haiku-4-5", MaxTokens: 5,
			System:   "Оцени, нужна ли для ответа сильная модель. Ответь одним словом: simple или complex.",
			Messages: []anthropic.MessageParam{anthropic.NewUserMessage(anthropic.NewTextBlock(lastUserText(req)))},
		})
		if err != nil {
			return 0, err
		}
		if strings.Contains(strings.ToLower(resp.Text), "complex") {
			return 1, nil
		}
		return 0, nil
	}
}

// routed — счётчики решений роутера; expvar отдаёт их на /debug/vars, в проде — та же метрика в OTel.
var routed = expvar.NewMap("llm_router")

// Router отправляет простое на дешёвую цепочку, сложное — на сильную.
// Cheap и Strong — обычные LLM: как правило, каждая — свой Fallback[...] со своими моделями.
type Router struct {
	Cheap, Strong LLM
	Classify      Classifier
	Threshold     float64              // score ≥ Threshold → Strong; значение приходит из фича-флага
	Accept        func(*Response) bool // проверка ответа дешёвой модели; false → эскалация
}

func (r *Router) Complete(ctx context.Context, req Request) (*Response, error) {
	score, err := r.Classify(ctx, req)
	if err != nil {
		routed.Add("classifier_error", 1)
		score = 1 // не знаем — платим за качество, а не экономим вслепую
	}
	if score >= r.Threshold {
		routed.Add("strong", 1)
		return r.Strong.Complete(ctx, req)
	}
	routed.Add("cheap", 1)
	resp, err := r.Cheap.Complete(ctx, req)
	if err != nil {
		return nil, err // доступность — забота Fallback внутри цепочки; роутер отвечает за качество
	}
	if r.Accept != nil && !r.Accept(resp) {
		// Откат на сильную модель: платим за оба вызова. Доля эскалаций — главная метрика роутера:
		// если она растёт, порог пора поднимать (или дешёвая модель деградировала).
		routed.Add("escalated", 1)
		return r.Strong.Complete(ctx, req)
	}
	return resp, nil
}
