// simulate прогоняет N голосовых ходов параллельно на фейковых провайдерах
// со случайными задержками и печатает p50/p95 по этапам.
// Задержки условные: подставьте свои замеры, чтобы получить свой бюджет.
//
//	go run ./cmd/simulate -n 200
package main

import (
	"context"
	"flag"
	"fmt"
	"math/rand/v2"
	"sync"
	"time"

	voice "aiec/examples/m16-voice"
)

const ms = time.Millisecond

func main() {
	n := flag.Int("n", 200, "число ходов")
	flag.Parse()

	var (
		mu                   sync.Mutex
		stt, llm, tts, audio []time.Duration
		wg                   sync.WaitGroup
	)
	for range *n {
		wg.Go(func() {
			ttft := 300*ms + rand.N(400*ms)
			if rand.Float64() < 0.05 {
				ttft += 1500 * ms // хвост: перегрузка провайдера, длинный промпт без кэша
			}
			a := &voice.Agent{
				STT:    voice.FakeSTT{Text: "когда приедет курьер", Endpoint: 300*ms + rand.N(300*ms)},
				LLM:    voice.FakeLLM{Reply: "Курьер будет с двух до четырёх. Позвонит за полчаса.", TTFT: ttft, PerWord: 15 * ms},
				TTS:    voice.FakeTTS{TTFA: 80*ms + rand.N(120*ms), PerWord: 300 * ms},
				Player: voice.SleepPlayer{},
			}
			audioIn := make(chan []byte, 50)
			for range 50 { // 1 с речи кадрами по 20 мс
				audioIn <- nil
			}
			close(audioIn)
			tm, err := a.Turn(context.Background(), audioIn, nil)
			if err != nil {
				fmt.Println("ход:", err)
				return
			}
			mu.Lock()
			stt, llm, tts, audio = append(stt, tm.STT), append(llm, tm.LLM), append(tts, tm.TTS), append(audio, tm.Audio)
			mu.Unlock()
		})
	}
	wg.Wait()

	fmt.Printf("%d ходов, мс от конца речи пользователя\n%-26s %6s %6s\n", len(audio), "этап", "p50", "p95")
	for _, r := range []struct {
		name string
		ds   []time.Duration
	}{{"STT: финальный транскрипт", stt}, {"LLM: первый токен", llm}, {"TTS: первый чанк", tts}, {"начало воспроизведения", audio}} {
		fmt.Printf("%-26s %6d %6d\n", r.name, voice.Percentile(r.ds, 50).Milliseconds(), voice.Percentile(r.ds, 95).Milliseconds())
	}
	fmt.Println("К этому добавьте сеть и аудиостек клиента (микрофон, кодек, jitter buffer, динамик).")
}
