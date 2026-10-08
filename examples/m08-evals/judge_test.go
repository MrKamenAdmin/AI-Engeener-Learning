package evals

import (
	"errors"
	"math"
	"testing"
)

// labels строит пары (человек, judge) по матрице ошибок.
func labels(c Confusion) (human, judge []bool) {
	add := func(n int, h, j bool) {
		for range n {
			human, judge = append(human, h), append(judge, j)
		}
	}
	add(c.TP, true, true)
	add(c.FN, true, false)
	add(c.FP, false, true)
	add(c.TN, false, false)
	return
}

func TestKappa(t *testing.T) {
	h, j := labels(Confusion{TP: 60, FN: 10, FP: 12, TN: 18})
	c := Confuse(h, j)
	// po = 0.78; pe = 0.7·0.72 + 0.3·0.28 = 0.588; κ = 0.192/0.412 ≈ 0.466
	if got := c.Kappa(); math.Abs(got-0.466) > 0.001 {
		t.Fatalf("kappa = %.3f", got)
	}
	// «Всегда PASS» при 90% хороших: agreement 90%, κ = 0.
	if got := (Confusion{TP: 90, FP: 10}).Kappa(); got != 0 {
		t.Fatalf("always-PASS kappa = %v", got)
	}
}

func TestCorrectedPassRate(t *testing.T) {
	// Истинно 70% хороших; judge: TPR 0.9, TNR 0.8 → покажет 0.9·0.7 + 0.2·0.3 = 69%.
	got, err := CorrectedPassRate(0.69, 0.9, 0.8)
	if err != nil || math.Abs(got-0.7) > 1e-9 {
		t.Fatalf("got %v, %v", got, err)
	}
	if _, err := CorrectedPassRate(0.5, 0.6, 0.4); !errors.Is(err, ErrUselessJudge) {
		t.Fatalf("want ErrUselessJudge, got %v", err)
	}
}

func TestCorrectedPassRateCI(t *testing.T) {
	h, j := labels(Confusion{TP: 63, FN: 7, FP: 6, TN: 24}) // TPR 0.9, TNR 0.8
	verdicts := make([]bool, 500)
	for i := range 345 { // judge сказал PASS на 69% прогона
		verdicts[i] = true
	}
	iv, err := CorrectedPassRateCI(verdicts, h, j, 5000, 1)
	if err != nil {
		t.Fatal(err)
	}
	if math.Abs(iv.Est-0.7) > 1e-9 || !(iv.Lo < 0.7 && 0.7 < iv.Hi) {
		t.Fatalf("got %+v", iv)
	}
	// Неопределённость TPR/TNR со 100 разметок делает интервал широким: ≈ [58%; 81%],
	// тогда как у самого прогона из 500 вердиктов полуширина около 4 п.п.
	if iv.Hi-iv.Lo < 0.15 {
		t.Fatalf("interval suspiciously narrow: %+v", iv)
	}
}
