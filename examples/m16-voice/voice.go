// Package voice — голосовой ход каскадом STT → LLM → TTS на горутинах и каналах:
// стриминг на каждом шаге, замер латентности по этапам и barge-in через отмену context
// с обрезкой истории до фактически произнесённого.
package voice

import (
	"context"
	"errors"
	"math"
	"slices"
	"strings"
	"time"
)

type Message struct {
	Role        string // "user" | "assistant"
	Text        string
	Interrupted bool // ответ оборван пользователем: в Text только произнесённое
}

type Transcript struct {
	Text  string
	Final bool // false — частичная гипотеза, ещё может измениться
}

// Chunk — кусок синтезированного звука и текст, который в нём звучит
// (выравнивание: многие TTS-API отдают таймкоды слов или символов).
type Chunk struct {
	Audio []byte
	Text  string
	Dur   time.Duration
}

// Контракт всех реализаций: выходной канал закрывается в конце и при отмене ctx,
// отправка в канал всегда через select с ctx.Done() — иначе отмена оставит висящие горутины.
// ponytail: ошибки посреди стрима не передаются, в проде — поле Err в событии.
type STT interface {
	Stream(ctx context.Context, audio <-chan []byte) (<-chan Transcript, error)
}

type LLM interface {
	Stream(ctx context.Context, history []Message) (<-chan string, error)
}

type TTS interface {
	Stream(ctx context.Context, text string) (<-chan Chunk, error)
}

// Player проигрывает чанк и возвращает nil, только если чанк прозвучал целиком.
type Player interface {
	Play(ctx context.Context, c Chunk) error
}

// Timings — латентность, отсчитанная от конца речи пользователя.
type Timings struct {
	STT         time.Duration // финальный транскрипт (endpointing + финализация)
	LLM         time.Duration // первый токен ответа
	TTS         time.Duration // первый чанк синтезированного звука
	Audio       time.Duration // начало воспроизведения: voice-to-voice без сети и аудиостека
	Interrupted bool
}

var (
	ErrBargeIn  = errors.New("voice: пользователь перебил")
	ErrNoSpeech = errors.New("voice: нет финального транскрипта")
)

type Agent struct {
	STT     STT
	LLM     LLM
	TTS     TTS
	Player  Player
	History []Message
}

// Turn проводит один ход. audio закрывается, когда пользователь замолчал (VAD);
// сигнал в bargeIn означает, что пользователь заговорил во время ответа.
func (a *Agent) Turn(ctx context.Context, audio <-chan []byte, bargeIn <-chan struct{}) (Timings, error) {
	var tm Timings
	ctx, cancel := context.WithCancelCause(ctx)
	defer cancel(nil)

	// Пересылаем аудио в STT и запоминаем момент конца речи — точку отсчёта бюджета.
	in, ended := make(chan []byte), make(chan time.Time, 1)
	go func() {
		defer close(in)
		for f := range audio {
			select {
			case in <- f:
			case <-ctx.Done():
				return
			}
		}
		ended <- time.Now()
	}()

	transcripts, err := a.STT.Stream(ctx, in)
	if err != nil {
		return tm, err
	}
	var user string
	for t := range transcripts {
		if t.Final {
			user = t.Text
			break
		}
	}
	sttAt := time.Now()
	if user == "" {
		return tm, errors.Join(ErrNoSpeech, context.Cause(ctx))
	}
	t0 := sttAt // финал пришёл раньше конца аудио (eager endpointing)
	select {
	case t0 = <-ended:
	default:
	}

	a.History = append(a.History, Message{Role: "user", Text: user})
	deltas, err := a.LLM.Stream(ctx, a.History)
	if err != nil {
		return tm, err
	}

	// Barge-in: одна отмена останавливает LLM, TTS и воспроизведение сразу.
	go func() {
		select {
		case <-bargeIn:
			cancel(ErrBargeIn)
		case <-ctx.Done():
		}
	}()

	// LLM → предложения: TTS стартует на первой фразе, а не на всём ответе.
	sentences := make(chan string, 4)
	var llmAt time.Time
	go func() {
		defer close(sentences)
		var buf string
		for d := range deltas {
			if llmAt.IsZero() {
				llmAt = time.Now()
			}
			buf += d
			if s, rest, ok := cutSentence(buf); ok {
				select {
				case sentences <- s:
				case <-ctx.Done():
					return
				}
				buf = rest
			}
		}
		if s := strings.TrimSpace(buf); s != "" && ctx.Err() == nil {
			sentences <- s
		}
	}()

	// Предложения → звук. Синтез идёт быстрее реального времени, буфер chunks
	// даёт TTS работать над следующей фразой, пока звучит текущая.
	chunks := make(chan Chunk, 8)
	var ttsAt time.Time
	go func() {
		defer close(chunks)
		for s := range sentences {
			if ctx.Err() != nil {
				continue // дочитываем канал, чтобы этап выше завершился
			}
			stream, err := a.TTS.Stream(ctx, s)
			if err != nil {
				cancel(err)
				continue
			}
			for c := range stream {
				if ttsAt.IsZero() {
					ttsAt = time.Now()
				}
				select {
				case chunks <- c:
				case <-ctx.Done():
				}
			}
		}
	}()

	// Воспроизведение. Канал дочитывается до закрытия даже после отмены:
	// так все горутины гарантированно завершились до чтения llmAt и ttsAt.
	var spoken []string
	var audioAt time.Time
	cut := false // часть ответа не прозвучала
	for c := range chunks {
		if ctx.Err() != nil {
			cut = true
			continue
		}
		if audioAt.IsZero() {
			audioAt = time.Now()
		}
		if a.Player.Play(ctx, c) != nil { // недоигранный чанк в историю не попадает
			cut = true
			continue
		}
		spoken = append(spoken, strings.TrimSpace(c.Text))
	}

	// В историю — только произнесённое: модель не должна «помнить», что сказала то,
	// чего пользователь не услышал.
	tm.Interrupted = errors.Is(context.Cause(ctx), ErrBargeIn)
	if text := strings.Join(spoken, " "); text != "" {
		if cut {
			text += "…"
		}
		a.History = append(a.History, Message{Role: "assistant", Text: text, Interrupted: cut})
	}
	since := func(t time.Time) time.Duration {
		if t.IsZero() {
			return 0
		}
		return t.Sub(t0)
	}
	tm.STT, tm.LLM, tm.TTS, tm.Audio = since(sttAt), since(llmAt), since(ttsAt), since(audioAt)
	if err := context.Cause(ctx); err != nil && !tm.Interrupted {
		return tm, err
	}
	return tm, nil
}

// cutSentence отрезает от буфера всё до последнего конца предложения.
// ponytail: наивно режет «т. е.» и «3.14»; настоящие агрегаторы знают сокращения и числа.
func cutSentence(buf string) (sentence, rest string, ok bool) {
	i := strings.LastIndexAny(buf, ".!?")
	if i < 0 {
		return "", buf, false
	}
	return strings.TrimSpace(buf[:i+1]), buf[i+1:], true
}

// Percentile — перцентиль методом ближайшего ранга (p в процентах); сортирует ds на месте.
func Percentile(ds []time.Duration, p float64) time.Duration {
	if len(ds) == 0 {
		return 0
	}
	slices.Sort(ds)
	i := int(math.Ceil(p/100*float64(len(ds)))) - 1
	return ds[max(i, 0)]
}
