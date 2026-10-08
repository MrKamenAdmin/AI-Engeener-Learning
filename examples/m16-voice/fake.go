package voice

import (
	"context"
	"strings"
	"time"
)

// Фейковые провайдеры с настраиваемыми задержками: тесты и симуляция без сети и ключей.

// FakeSTT отдаёт частичную гипотезу на каждый кадр и финал через Endpoint после конца аудио.
type FakeSTT struct {
	Text     string
	Endpoint time.Duration // ожидание тишины + финализация гипотезы
}

func (f FakeSTT) Stream(ctx context.Context, audio <-chan []byte) (<-chan Transcript, error) {
	out := make(chan Transcript)
	go func() {
		defer close(out)
		words := strings.Fields(f.Text)
		n := 0
		for range audio {
			if n < len(words) {
				n++
				if !send(ctx, out, Transcript{Text: strings.Join(words[:n], " ")}) {
					return
				}
			}
		}
		if sleep(ctx, f.Endpoint) == nil {
			send(ctx, out, Transcript{Text: f.Text, Final: true})
		}
	}()
	return out, nil
}

// FakeLLM печатает Reply по словам: первое через TTFT, остальные через PerWord.
type FakeLLM struct {
	Reply         string
	TTFT, PerWord time.Duration
}

func (f FakeLLM) Stream(ctx context.Context, _ []Message) (<-chan string, error) {
	out := make(chan string)
	go func() {
		defer close(out)
		if sleep(ctx, f.TTFT) != nil {
			return
		}
		for i, w := range strings.SplitAfter(f.Reply, " ") {
			if i > 0 && sleep(ctx, f.PerWord) != nil {
				return
			}
			if !send(ctx, out, w) {
				return
			}
		}
	}()
	return out, nil
}

// FakeTTS синтезирует фразу по словам: первый чанк через TTFA, каждый звучит PerWord.
type FakeTTS struct {
	TTFA, PerWord time.Duration
}

func (f FakeTTS) Stream(ctx context.Context, text string) (<-chan Chunk, error) {
	out := make(chan Chunk)
	go func() {
		defer close(out)
		if sleep(ctx, f.TTFA) != nil {
			return
		}
		for _, w := range strings.Fields(text) {
			if !send(ctx, out, Chunk{Text: w, Dur: f.PerWord}) {
				return
			}
		}
	}()
	return out, nil
}

// SleepPlayer «играет» чанк, выжидая его длительность, — динамик для тестов и симуляции.
type SleepPlayer struct{}

func (SleepPlayer) Play(ctx context.Context, c Chunk) error { return sleep(ctx, c.Dur) }

func send[T any](ctx context.Context, ch chan<- T, v T) bool {
	select {
	case ch <- v:
		return true
	case <-ctx.Done():
		return false
	}
}

func sleep(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return nil
	case <-ctx.Done():
		return context.Cause(ctx)
	}
}
