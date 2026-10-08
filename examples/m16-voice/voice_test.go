package voice

import (
	"context"
	"errors"
	"slices"
	"sync"
	"testing"
	"testing/synctest"
	"time"
)

// Тесты идут в synctest: время фейковое, поэтому латентность проверяется точно,
// а зависшая после отмены горутина валит тест как deadlock — бесплатная проверка утечек.

const ms = time.Millisecond

// speak имитирует фразу пользователя: n кадров, затем закрытие канала (VAD: речь кончилась).
func speak(n int) <-chan []byte {
	ch := make(chan []byte, n)
	for range n {
		ch <- make([]byte, 640) // 20 мс PCM 16 кГц
	}
	close(ch)
	return ch
}

// recTTS запоминает фразы, отданные на синтез.
type recTTS struct {
	FakeTTS
	mu  sync.Mutex
	got []string
}

func (r *recTTS) Stream(ctx context.Context, text string) (<-chan Chunk, error) {
	r.mu.Lock()
	r.got = append(r.got, text)
	r.mu.Unlock()
	return r.FakeTTS.Stream(ctx, text)
}

func TestTurnStreamsBySentence(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		tts := &recTTS{FakeTTS: FakeTTS{TTFA: 15 * ms, PerWord: ms}}
		a := &Agent{
			STT:    FakeSTT{Text: "где мой заказ", Endpoint: 30 * ms},
			LLM:    FakeLLM{Reply: "Заказ в пути. Привезём завтра до обеда.", TTFT: 40 * ms, PerWord: 20 * ms},
			TTS:    tts,
			Player: SleepPlayer{},
		}
		tm, err := a.Turn(context.Background(), speak(5), nil)
		if err != nil {
			t.Fatal(err)
		}
		// endpoint 30 → TTFT +40 → первая фраза (3 слова) +2×20 → TTFA +15.
		// Без разбиения на фразы звук начался бы на 30+40+6×20+15 = 205 мс.
		if want := (Timings{STT: 30 * ms, LLM: 70 * ms, TTS: 125 * ms, Audio: 125 * ms}); tm != want {
			t.Errorf("timings = %+v, want %+v", tm, want)
		}
		if want := []string{"Заказ в пути.", "Привезём завтра до обеда."}; !slices.Equal(tts.got, want) {
			t.Errorf("TTS получил %q, want %q", tts.got, want)
		}
		want := []Message{{Role: "user", Text: "где мой заказ"}, {Role: "assistant", Text: "Заказ в пути. Привезём завтра до обеда."}}
		if !slices.Equal(a.History, want) {
			t.Errorf("history = %+v", a.History)
		}
	})
}

// bargePlayer доигрывает after чанков, а на следующем «слышит» пользователя и ждёт отмены.
type bargePlayer struct {
	after, n int
	bargeIn  chan struct{}
}

func (p *bargePlayer) Play(ctx context.Context, c Chunk) error {
	if p.n == p.after {
		close(p.bargeIn)
		<-ctx.Done()
		return context.Cause(ctx)
	}
	p.n++
	return nil
}

func TestBargeInTruncatesHistory(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		p := &bargePlayer{after: 4, bargeIn: make(chan struct{})}
		a := &Agent{
			STT:    FakeSTT{Text: "когда доставка", Endpoint: ms},
			LLM:    FakeLLM{Reply: "Доставка в четверг с десяти до двух. Могу перенести на пятницу.", TTFT: ms},
			TTS:    FakeTTS{TTFA: ms, PerWord: ms},
			Player: p,
		}
		tm, err := a.Turn(context.Background(), speak(3), p.bargeIn)
		if err != nil {
			t.Fatal(err)
		}
		if !tm.Interrupted {
			t.Error("Interrupted = false")
		}
		got := a.History[len(a.History)-1]
		want := Message{Role: "assistant", Text: "Доставка в четверг с…", Interrupted: true}
		if got != want {
			t.Errorf("в историю попало %+v, want %+v", got, want)
		}
	})
}

func TestBargeInBeforeAudio(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		bargeIn := make(chan struct{})
		close(bargeIn) // пользователь продолжил говорить, пока LLM думала
		a := &Agent{
			STT:    FakeSTT{Text: "алло", Endpoint: ms},
			LLM:    FakeLLM{Reply: "Слушаю вас.", TTFT: time.Second},
			TTS:    FakeTTS{},
			Player: SleepPlayer{},
		}
		start := time.Now()
		tm, err := a.Turn(context.Background(), speak(1), bargeIn)
		if err != nil || !tm.Interrupted {
			t.Fatalf("err=%v interrupted=%v", err, tm.Interrupted)
		}
		if d := time.Since(start); d != ms {
			t.Errorf("ход длился %v: отмена не остановила LLM", d)
		}
		if len(a.History) != 1 {
			t.Errorf("ответ не прозвучал, но попал в историю: %+v", a.History)
		}
	})
}

func TestNoSpeech(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		a := &Agent{STT: FakeSTT{}, LLM: FakeLLM{}, TTS: FakeTTS{}, Player: SleepPlayer{}}
		if _, err := a.Turn(context.Background(), speak(0), nil); !errors.Is(err, ErrNoSpeech) {
			t.Fatalf("err = %v", err)
		}
	})
}

func TestPercentile(t *testing.T) {
	var ds []time.Duration
	for i := 100; i >= 1; i-- {
		ds = append(ds, time.Duration(i)*ms)
	}
	if p50, p95 := Percentile(ds, 50), Percentile(ds, 95); p50 != 50*ms || p95 != 95*ms {
		t.Errorf("p50=%v p95=%v", p50, p95)
	}
	if Percentile(nil, 95) != 0 || Percentile([]time.Duration{7 * ms}, 0) != 7*ms {
		t.Error("граничные случаи")
	}
}
