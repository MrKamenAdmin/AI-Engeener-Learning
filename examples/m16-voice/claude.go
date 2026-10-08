package voice

import (
	"context"

	"github.com/anthropics/anthropic-sdk-go"
)

// VoiceSystem — ответ озвучит TTS: формат и длина — часть голосового UX.
const VoiceSystem = `Ты голосовой ассистент. Ответ будет озвучен синтезатором речи, поэтому:
без markdown, списков и ссылок; одна-три короткие фразы; первая фраза — сразу по делу;
числа и даты пиши так, как их произносят. Если нужен уточняющий вопрос — задай один.`

// Claude — LLM-звено каскада поверх стримингового Messages API.
type Claude struct {
	Client anthropic.Client
	Model  string // в голосе важнее TTFT: маленькая модель, например "claude-haiku-4-5"
}

func (c Claude) Stream(ctx context.Context, history []Message) (<-chan string, error) {
	msgs := make([]anthropic.MessageParam, 0, len(history))
	for _, m := range history {
		b := anthropic.NewTextBlock(m.Text)
		if m.Role == "assistant" {
			msgs = append(msgs, anthropic.NewAssistantMessage(b))
		} else {
			msgs = append(msgs, anthropic.NewUserMessage(b)) // подряд идущие user-сообщения API склеит
		}
	}
	stream := c.Client.Messages.NewStreaming(ctx, anthropic.MessageNewParams{
		Model:     c.Model,
		MaxTokens: 300,
		System:    []anthropic.TextBlockParam{{Text: VoiceSystem}},
		Messages:  msgs,
	})
	out := make(chan string)
	go func() {
		defer close(out)
		defer stream.Close()
		for stream.Next() {
			if d, ok := stream.Current().AsAny().(anthropic.ContentBlockDeltaEvent); ok {
				if t, ok := d.Delta.AsAny().(anthropic.TextDelta); ok && !send(ctx, out, t.Text) {
					return // barge-in: отмена ctx закрывает HTTP-стрим, генерация останавливается
				}
			}
		}
		// ponytail: stream.Err() теряется по контракту интерфейса; в проде — лог и метрика.
	}()
	return out, nil
}
