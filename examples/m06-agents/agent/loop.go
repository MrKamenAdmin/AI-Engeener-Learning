// Package agent — агентный цикл модуля 6 (m06.html#go-loop) и всё, что делает его
// пригодным для длинных задач: бюджет в долларах, компакция, заметки, субагенты,
// ретраи и идемпотентность инструментов, мост к MCP, сессии с проверкой прогресса.
package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"maps"
	"slices"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/anthropics/anthropic-sdk-go/option"
)

// Model — всё, что циклу нужно от клиента. Ему удовлетворяет &client.Messages,
// декоратор Budgeted и fake в тестах.
type Model interface {
	New(ctx context.Context, p anthropic.MessageNewParams, opts ...option.RequestOption) (*anthropic.Message, error)
}

type Tool struct {
	Param     anthropic.ToolParam
	Dangerous bool // мутирует мир: требует подтверждения человека
	Run       func(ctx context.Context, input json.RawMessage) (string, error)
}

// Confirm спрашивает человека; вызовы сериализуются мьютексом внутри.
type Confirm func(action string) bool

type Agent struct {
	Model    Model           // &client.Messages или Budgeted поверх него
	ModelID  anthropic.Model // "claude-opus-5"
	System   string
	Tools    map[string]Tool
	MaxIters int
	Compact  *Compactor // nil — без компакции
	Confirm  Confirm    // nil — опасные действия запрещены
}

// Step — запись траектории: по ним считают eval агента (модуль 8).
type Step struct {
	Iter      int
	Tool      string
	Input     string
	IsError   bool
	CtxTokens int64 // размер контекста на вызове модели, который запросил этот шаг
}

type Result struct {
	Text          string
	Iters         int
	Steps         []Step
	PeakCtx       int64
	Compactions   int
	CompactErrors int
}

var ErrMaxIters = errors.New("agent: max iterations reached")

func (a *Agent) Run(ctx context.Context, task string) (Result, error) {
	var defs []anthropic.ToolUnionParam
	for _, name := range slices.Sorted(maps.Keys(a.Tools)) { // стабильный порядок = стабильный кэшируемый префикс
		p := a.Tools[name].Param
		defs = append(defs, anthropic.ToolUnionParam{OfTool: &p})
	}
	msgs := []anthropic.MessageParam{anthropic.NewUserMessage(anthropic.NewTextBlock(task))}
	var res Result
	var ctxTok int64 // размер контекста по usage последнего вызова

	for iter := 0; iter < a.MaxIters; iter++ {
		res.Iters = iter + 1
		var compacted bool
		var err error
		if msgs, compacted, err = a.Compact.Maybe(ctx, msgs, ctxTok); err != nil {
			res.CompactErrors++ // история не тронута: работаем дальше, попробуем на следующем шаге
		} else if compacted {
			res.Compactions++
		}
		resp, err := a.Model.New(ctx, anthropic.MessageNewParams{
			Model: a.ModelID, MaxTokens: 16000,
			// Breakpoint на system: tools и system остаются в кэше, даже когда компакция перепишет историю.
			System:   []anthropic.TextBlockParam{{Text: a.System, CacheControl: anthropic.NewCacheControlEphemeralParam()}},
			Tools:    defs,
			Messages: msgs,
			// Автоматический breakpoint на последнем блоке: каждый шаг читает всю прошлую историю по 0,1×.
			CacheControl: anthropic.NewCacheControlEphemeralParam(),
		})
		if err != nil {
			return res, fmt.Errorf("iter %d: %w", iter, err) // ErrBudget из Budgeted тоже сюда
		}
		u := resp.Usage // с кэшем input_tokens — только хвост после breakpoint, складываем всё
		ctxTok = u.InputTokens + u.CacheReadInputTokens + u.CacheCreationInputTokens + u.OutputTokens
		res.PeakCtx = max(res.PeakCtx, ctxTok)
		msgs = append(msgs, resp.ToParam())

		switch resp.StopReason {
		case anthropic.StopReasonEndTurn:
			res.Text = text(resp)
			return res, nil
		case anthropic.StopReasonMaxTokens:
			return res, errors.New("agent: response truncated (max_tokens)")
		case anthropic.StopReasonToolUse:
			// продолжаем ниже
		default:
			return res, fmt.Errorf("agent: unexpected stop_reason %q", resp.StopReason)
		}

		var calls []anthropic.ToolUseBlock
		for _, b := range resp.Content {
			if tu, ok := b.AsAny().(anthropic.ToolUseBlock); ok {
				calls = append(calls, tu)
			}
		}
		// Параллельные tool_use: исполняем конкурентно, возвращаем ВСЕ результаты одним сообщением.
		results := make([]anthropic.ContentBlockParamUnion, len(calls))
		failed := make([]bool, len(calls))
		var wg sync.WaitGroup
		for i, call := range calls {
			wg.Go(func() {
				out, isErr := a.execTool(ctx, call)
				results[i] = anthropic.NewToolResultBlock(call.ID, out, isErr)
				failed[i] = isErr
			})
		}
		wg.Wait()
		for i, call := range calls {
			res.Steps = append(res.Steps, Step{Iter: iter, Tool: call.Name, Input: string(call.Input), IsError: failed[i], CtxTokens: ctxTok})
		}
		msgs = append(msgs, anthropic.NewUserMessage(results...))
	}
	return res, fmt.Errorf("%w (%d)", ErrMaxIters, a.MaxIters)
}

func (a *Agent) execTool(ctx context.Context, call anthropic.ToolUseBlock) (string, bool) {
	t, ok := a.Tools[call.Name]
	if !ok {
		return fmt.Sprintf("unknown tool %q", call.Name), true
	}
	if t.Dangerous && (a.Confirm == nil || !a.Confirm(fmt.Sprintf("%s %s", call.Name, call.Input))) {
		return "The user denied this action. Do not retry it; propose an alternative or finish.", true
	}
	out, err := retry(ctx, 3, func(ctx context.Context) (string, error) {
		ctx, cancel := context.WithTimeout(ctx, 60*time.Second) // таймаут на попытку, а не на все
		defer cancel()
		return t.Run(ctx, call.Input)
	})
	if err != nil {
		return "error: " + err.Error(), true // модель увидит и попробует исправиться
	}
	return truncate(out, 40_000, "\n…[truncated; narrow the query]"), false // ≈10K токенов
}

func text(m *anthropic.Message) string {
	var sb strings.Builder
	for _, b := range m.Content {
		if t, ok := b.AsAny().(anthropic.TextBlock); ok {
			sb.WriteString(t.Text)
		}
	}
	return sb.String()
}

// truncate режет по границе руны: обрезанный посреди UTF-8 русский текст — мусор в контексте.
func truncate(s string, n int, tail string) string {
	if len(s) <= n {
		return s
	}
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return s[:n] + tail
}
