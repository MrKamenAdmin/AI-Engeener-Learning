// Package secure — примеры к модулю 7 «Безопасность LLM-приложений и guardrails»:
// guardrails-middleware, allowlist инструментов со scopes пользователя, лимиты на запрос,
// безопасная обработка вывода (SQL, HTML, shell, markdown) и раннер red-team с метрикой ASR.
package secure

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"slices"
)

// LLM — минимальный интерфейс модели. В проде за ним anthropic-sdk-go (модуль 2),
// в тестах — fake, поэтому всё проверяется без сети и ключей.
type LLM interface {
	Complete(ctx context.Context, req Request) (Response, error)
}

type Request struct {
	System    string
	Prompt    string   // ввод пользователя
	Docs      []string // недоверенный контент: чанки RAG, веб-страницы, вывод инструментов
	MaxTokens int
}

type ToolCall struct {
	Name string
	Args json.RawMessage
}

type Response struct {
	Text                      string
	ToolCalls                 []ToolCall
	InputTokens, OutputTokens int
}

// Principal — от чьего имени действует агент. Scopes и Token приходят из OAuth-токена
// пользователя при аутентификации, а не из промпта и не из аргументов, которые выбрала модель.
type Principal struct {
	UserID string
	Scopes []string
	Token  string // токен пользователя для downstream API, а не сервисный суперключ
}

type Tool struct {
	Name    string
	Scopes  []string // нужны все
	Confirm bool     // необратимое действие: нужен человек
	Run     func(ctx context.Context, p Principal, args json.RawMessage) (string, error)
}

// Confirmer показывает человеку точное действие с аргументами и возвращает его решение.
type Confirmer func(ctx context.Context, p Principal, c ToolCall) bool

var (
	ErrUnknownTool = errors.New("tool not in allowlist")
	ErrScope       = errors.New("insufficient scope")
	ErrDeclined    = errors.New("action not confirmed")
)

// Registry — allowlist инструментов агента. Чего нет в Tools, того агент сделать не может,
// что бы ни написала модель.
type Registry struct {
	Tools   map[string]Tool
	Confirm Confirmer // nil: необратимые действия запрещены
}

func (r *Registry) Call(ctx context.Context, p Principal, c ToolCall) (string, error) {
	t, ok := r.Tools[c.Name]
	if !ok {
		return "", fmt.Errorf("%w: %q", ErrUnknownTool, c.Name)
	}
	for _, s := range t.Scopes {
		if !slices.Contains(p.Scopes, s) {
			return "", fmt.Errorf("%w: %s requires %s", ErrScope, c.Name, s)
		}
	}
	if t.Confirm && (r.Confirm == nil || !r.Confirm(ctx, p, c)) {
		return "", fmt.Errorf("%w: %s", ErrDeclined, c.Name)
	}
	// Аудит каждого вызова: расследование инцидента начинается с этого лога.
	slog.InfoContext(ctx, "tool call", "user", p.UserID, "tool", c.Name, "args", string(c.Args))
	return t.Run(ctx, p, c.Args)
}

type Limits struct {
	MaxSteps  int // вызовов модели и инструментов на один запрос пользователя
	MaxTokens int // input + output за весь запрос
}

var ErrBudget = errors.New("request budget exceeded")

// Budget считает расход одного запроса. Запрос обслуживает одна горутина;
// для параллельных tool calls замените счётчики на atomic.
type Budget struct {
	Limits
	steps, tokens int
}

func (b *Budget) Step() error {
	b.steps++
	if b.steps > b.MaxSteps {
		return fmt.Errorf("%w: steps %d > %d", ErrBudget, b.steps, b.MaxSteps)
	}
	return nil
}

func (b *Budget) Spend(tokens int) error {
	b.tokens += tokens
	if b.tokens > b.MaxTokens {
		return fmt.Errorf("%w: tokens %d > %d", ErrBudget, b.tokens, b.MaxTokens)
	}
	return nil
}

// ToolAgent — один шаг агента: модель отвечает и просит инструменты, мы их исполняем
// через Registry в рамках Budget. Полный цикл с возвратом tool_result модели — модуль 6.
type ToolAgent struct {
	LLM       LLM
	Tools     *Registry
	User      Principal
	System    string
	MaxOutput int // max_tokens одного ответа
	Limits    Limits
}

func (a *ToolAgent) Run(ctx context.Context, in Input) (Trace, error) {
	b := &Budget{Limits: a.Limits}
	if err := b.Step(); err != nil {
		return Trace{}, err
	}
	resp, err := a.LLM.Complete(ctx, Request{System: a.System, Prompt: in.Prompt, Docs: in.Docs, MaxTokens: a.MaxOutput})
	if err != nil {
		return Trace{}, err
	}
	tr := Trace{Output: resp.Text}
	if err := b.Spend(resp.InputTokens + resp.OutputTokens); err != nil {
		tr.Output += "\n[остановлено: " + err.Error() + "]"
		return tr, nil
	}
	for _, c := range resp.ToolCalls {
		if err := b.Step(); err != nil {
			tr.Output += "\n[остановлено: " + err.Error() + "]"
			break
		}
		res, err := a.Tools.Call(ctx, a.User, c)
		if err != nil {
			tr.Output += "\n[" + c.Name + ": " + err.Error() + "]" // в настоящем цикле — tool_result с is_error
			continue
		}
		tr.Tools = append(tr.Tools, c.Name)
		tr.Output += "\n" + res
	}
	return tr, nil
}
