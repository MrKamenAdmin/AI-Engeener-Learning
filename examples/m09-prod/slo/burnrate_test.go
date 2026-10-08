package slo

import (
	"math"
	"testing"
	"time"
)

// series — поминутные счётчики: rps запросов в минуту, доля ошибок errAt(minutesAgo).
func series(perMin float64, errAt func(minAgo int) float64) func(time.Duration) Counts {
	return func(d time.Duration) Counts {
		var c Counts
		for m := 0; m < int(d.Minutes()); m++ {
			c.Total += perMin
			c.Bad += perMin * errAt(m)
		}
		return c
	}
}

func TestBurnRate(t *testing.T) {
	if br := BurnRate(Counts{Total: 1000, Bad: 5}, 0.995); math.Abs(br-1) > 1e-9 {
		t.Fatalf("0.5%% ошибок при SLO 99.5%% — burn rate 1, получили %v", br)
	}
	if left := BudgetLeft(Counts{Total: 1000, Bad: 10}, 0.995); math.Abs(left+1) > 1e-9 {
		t.Fatalf("двойной перерасход — бюджет -100%%, получили %v", left)
	}
}

func TestFiring(t *testing.T) {
	const slo = 0.995 // качество ответа: 99.5% «хороших»
	cases := []struct {
		name  string
		errAt func(int) float64
		want  []float64 // burn rate сработавших правил
	}{
		{"норма", func(int) float64 { return 0.002 }, nil},
		{"авария последние 20 минут: 30% плохих", func(m int) float64 {
			if m < 20 {
				return 0.30
			}
			return 0.002
		}, []float64{14.4}},
		{"тлеющая деградация 4 дня: 0.6% плохих", func(int) float64 { return 0.006 }, []float64{1}},
		{"авария была 2 часа назад и закончилась", func(m int) float64 {
			if m > 120 && m < 140 {
				return 0.5
			}
			return 0.002
		}, nil},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := Firing(Workbook, slo, 50, series(100, c.errAt))
			if len(got) != len(c.want) {
				t.Fatalf("сработало %v, ждали burn %v", got, c.want)
			}
			for i := range got {
				if got[i].Burn != c.want[i] {
					t.Fatalf("сработало %v, ждали burn %v", got, c.want)
				}
			}
		})
	}
}

func TestFiringIgnoresSparseSamples(t *testing.T) {
	// Онлайн-judge оценивает 2% ответов: при 100 rpm это 2 оценки в минуту,
	// 10 в 5-минутном окне и 120 в часовом. 9 плохих за последние 15 минут — burn rate 15
	// в часовом окне и 60 в коротком: может быть авария, а может быть шум выборки.
	w := series(2, func(m int) float64 {
		if m < 15 {
			return 0.3
		}
		return 0
	})
	if got := Firing(Workbook[:1], 0.995, 0, w); len(got) != 1 {
		t.Fatalf("без порога событий быстрое правило срабатывает: %v", got)
	}
	if got := Firing(Workbook[:1], 0.995, 50, w); len(got) != 0 {
		t.Fatalf("по 10 оценкам в коротком окне не будим дежурного: %v", got)
	}
}
