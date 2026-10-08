package drift

import (
	"math"
	"testing"
)

func TestPSI(t *testing.T) {
	ref := map[string]float64{"billing": 500, "delivery": 300, "account": 200}
	if psi := PSI(ref, map[string]float64{"billing": 50, "delivery": 30, "account": 20}); psi > 1e-12 {
		t.Fatalf("то же распределение в другом масштабе — PSI 0, получили %v", psi)
	}
	// 50/50 → 90/10: 0.4·ln(1.8) + 0.4·ln(5) ≈ 0.879 — сильный сдвиг.
	if psi := PSI(map[string]float64{"ru": 50, "en": 50}, map[string]float64{"ru": 90, "en": 10}); math.Abs(psi-0.879) > 1e-3 {
		t.Fatalf("PSI = %v, ждали ≈ 0.879", psi)
	}
	// Новая тема, которой не было в эталоне (например, после запуска фичи), — тоже дрейф.
	cur := map[string]float64{"billing": 400, "delivery": 250, "account": 150, "refund_v2": 200}
	if psi := PSI(ref, cur); psi < 0.25 {
		t.Fatalf("новая тема на 20%% трафика должна давать PSI > 0.25, получили %v", psi)
	}
}

func TestJS(t *testing.T) {
	if js := JS(map[string]float64{"a": 1}, map[string]float64{"b": 1}); math.Abs(js-1) > 1e-12 {
		t.Fatalf("непересекающиеся распределения — JS 1, получили %v", js)
	}
	a := map[string]float64{"a": 3, "b": 1}
	b := map[string]float64{"a": 1, "b": 3}
	if JS(a, b) != JS(b, a) {
		t.Fatal("JS симметрична")
	}
}

func TestLengthBucket(t *testing.T) {
	for tok, want := range map[int]string{10: "<50", 50: "50-199", 999: "200-999", 5000: "1000+"} {
		if got := LengthBucket(tok); got != want {
			t.Fatalf("%d → %s, ждали %s", tok, got, want)
		}
	}
}
