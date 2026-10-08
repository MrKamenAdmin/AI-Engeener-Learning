package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/anthropics/anthropic-sdk-go"
)

// Compactor сжимает историю, когда контекст перерос порог: старые ходы заменяет
// резюме от дешёвой модели, последние KeepTurns ходов оставляет дословно.
type Compactor struct {
	Model     Model           // тот же Budgeted, что у агента: суммаризация тоже стоит денег
	ModelID   anthropic.Model // "claude-haiku-4-5"
	Threshold int64           // токенов контекста; 50–70% рабочего бюджета, а не 95% окна
	KeepTurns int             // ход = ответ модели с tool_use + сообщение с tool_result
}

const summaryPrompt = `You compact the working history of an AI agent so that it can continue the task.
Preserve: the task and its acceptance criteria; decisions made and why; what is done and verified;
exact file paths, IDs, names and numbers; errors met and how they were resolved; open questions;
the immediate next step. Drop raw tool outputs that can be fetched again. At most 500 words.`

// Maybe вызывается перед каждым вызовом модели. ctxTokens — размер контекста по usage
// прошлого вызова. Возвращает новую историю и признак того, что компакция была.
func (c *Compactor) Maybe(ctx context.Context, msgs []anthropic.MessageParam, ctxTokens int64) ([]anthropic.MessageParam, bool, error) {
	if c == nil || ctxTokens < c.Threshold {
		return msgs, false, nil
	}
	// Хвост должен начинаться с ответа модели: тогда каждая пара tool_use → tool_result
	// целиком либо в резюме, либо в хвосте, и история чередует user/assistant.
	cut := min(len(msgs)-1, len(msgs)-2*c.KeepTurns)
	for cut > 1 && msgs[cut].Role != anthropic.MessageParamRoleAssistant {
		cut--
	}
	if cut <= 1 {
		return msgs, false, nil // сжимать нечего: всё — «последние ходы»
	}
	resp, err := c.Model.New(ctx, anthropic.MessageNewParams{
		Model: c.ModelID, MaxTokens: 4000,
		System:   []anthropic.TextBlockParam{{Text: summaryPrompt}},
		Messages: []anthropic.MessageParam{anthropic.NewUserMessage(anthropic.NewTextBlock(render(msgs[:cut])))},
	})
	if err != nil {
		return msgs, false, err
	}
	summary := text(resp)
	if resp.StopReason != anthropic.StopReasonEndTurn || summary == "" {
		// Обрезанное или пустое резюме хуже никакого: история заменилась бы дырой.
		return msgs, false, fmt.Errorf("compact: no summary (stop_reason %q)", resp.StopReason)
	}
	// Первый блок первого сообщения — исходная задача: её не пересказываем, а держим дословно.
	head := anthropic.NewUserMessage(msgs[0].Content[0], anthropic.NewTextBlock(
		"<summary_of_earlier_work>\n"+summary+"\n</summary_of_earlier_work>\n"+
			"Earlier turns were compacted. Re-read your notes before acting; "+
			"re-fetch files you need instead of relying on memory."))
	out := []anthropic.MessageParam{head}
	for _, m := range msgs[cut:] {
		out = append(out, withoutThinking(m))
	}
	return out, true, nil
}

// withoutThinking убирает thinking-блоки из сохранённых ходов: их подпись привязана
// к прежнему префиксу, а мы его заменили резюме. Убрать все — допустимо, модель лишь
// теряет те рассуждения; оставить — ошибка на моделях с проверкой префикса.
func withoutThinking(m anthropic.MessageParam) anthropic.MessageParam {
	kept := make([]anthropic.ContentBlockParamUnion, 0, len(m.Content))
	for _, b := range m.Content {
		if b.OfThinking == nil && b.OfRedactedThinking == nil {
			kept = append(kept, b)
		}
	}
	m.Content = kept
	return m
}

// render превращает историю в текст для суммаризатора: ему не нужны блоки tool_use
// и определения инструментов, а длинные выводы заодно обрезаются.
func render(msgs []anthropic.MessageParam) string {
	var b strings.Builder
	for _, m := range msgs {
		for _, blk := range m.Content {
			switch {
			case blk.OfText != nil:
				fmt.Fprintf(&b, "[%s] %s\n", m.Role, blk.OfText.Text)
			case blk.OfToolUse != nil:
				in, _ := json.Marshal(blk.OfToolUse.Input)
				fmt.Fprintf(&b, "[tool_use %s] %s\n", blk.OfToolUse.Name, in)
			case blk.OfToolResult != nil:
				tag := "tool_result"
				if blk.OfToolResult.IsError.Value {
					tag = "tool_error"
				}
				for _, c := range blk.OfToolResult.Content {
					if c.OfText != nil {
						fmt.Fprintf(&b, "[%s] %s\n", tag, truncate(c.OfText.Text, 2000, " …"))
					}
				}
			}
		}
	}
	return b.String()
}
