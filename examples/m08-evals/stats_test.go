package evals

import (
	"math"
	"testing"
)

func ones(n, k int) []float64 { // n кейсов, первые k прошли
	x := make([]float64, n)
	for i := range k {
		x[i] = 1
	}
	return x
}

func TestBootstrapCloseToWilson(t *testing.T) {
	// 80/100: Уилсон даёт [71.1%; 86.7%]; bootstrap должен быть рядом.
	iv := Bootstrap(ones(100, 80), 10000, 1)
	if iv.Est != 0.8 || math.Abs(iv.Lo-0.711) > 0.02 || math.Abs(iv.Hi-0.867) > 0.02 {
		t.Fatalf("got %+v", iv)
	}
}

func TestBootstrapDegeneratesAtAllPass(t *testing.T) {
	// 50/50 прошли: bootstrap даёт нулевую ширину, хотя Уилсон — [92.9%; 100%].
	// Поэтому для одной доли при малом n или p у края — Уилсон, а не bootstrap.
	iv := Bootstrap(ones(50, 50), 2000, 1)
	if iv.Lo != 1 || iv.Hi != 1 {
		t.Fatalf("got %+v", iv)
	}
}

func TestPairedDetectsWhatUnpairedMisses(t *testing.T) {
	// base 80/100, cand 85/100: cand чинит 5 кейсов и ничего не ломает.
	base, cand := ones(100, 80), ones(100, 85)
	d := PairedBootstrap(base, cand, 10000, 1)
	if d.Lo <= 0 {
		t.Fatalf("paired CI must exclude 0, got %+v", d)
	}
	// Непарно те же числа неразличимы: интервалы двух версий пересекаются.
	b, c := Bootstrap(base, 10000, 2), Bootstrap(cand, 10000, 3)
	if b.Hi < c.Lo {
		t.Fatalf("unpaired intervals should overlap: %+v vs %+v", b, c)
	}
}

func TestClusterBootstrapWithRepeats(t *testing.T) {
	// 3 повтора на кейс: оценка кейса — доля успешных повторов.
	scores := []float64{1, 1, 2.0 / 3, 0, 1, 1.0 / 3, 1, 1, 1, 0}
	iv := Bootstrap(scores, 5000, 1)
	if !(iv.Lo < iv.Est && iv.Est < iv.Hi) {
		t.Fatalf("got %+v", iv)
	}
}
