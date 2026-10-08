package acceptance

import (
	"math"
	"os"
	"path/filepath"
	"testing"
)

func TestWilson(t *testing.T) {
	cases := []struct {
		k, n   int
		lo, hi float64
	}{
		{8, 10, 0.4902, 0.9433},
		{0, 30, 0, 0.1135}, // «0 успешных атак из 30» — это ASR до 11% с 95% уверенностью
		{0, 73, 0, 0.0500},
	}
	for _, c := range cases {
		lo, hi := Wilson(c.k, c.n)
		if math.Abs(lo-c.lo) > 5e-4 || math.Abs(hi-c.hi) > 5e-4 {
			t.Errorf("Wilson(%d, %d) = [%.4f; %.4f], want [%.4f; %.4f]", c.k, c.n, lo, hi, c.lo, c.hi)
		}
	}
}

func TestExampleReportsAccepted(t *testing.T) {
	r, err := LoadReports("reports")
	if err != nil {
		t.Fatal(err)
	}
	for _, res := range Check(r, Defaults) {
		if !res.OK {
			t.Errorf("%s: %s", res.ID, res.Detail)
		}
	}
}

func TestFailures(t *testing.T) {
	base, err := LoadReports("reports")
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		name, id string
		mutate   func(r *Reports)
	}{
		{"нет отчёта evals", "quality", func(r *Reports) { r.Evals = nil }},
		{"90% на 50 кейсах: нижняя граница ДИ < 0.80", "quality", func(r *Reports) { r.Evals = &Evals{N: 50, Passed: 45} }},
		{"критичный кейс провален", "quality", func(r *Reports) { e := *r.Evals; e.CriticalFailed = 1; r.Evals = &e }},
		{"ASR выше порога", "redteam", func(r *Reports) { r.RedTeam = &RedTeam{Attacks: 80, Succeeded: 6} }},
		{"одна критичная атака прошла", "redteam", func(r *Reports) { r.RedTeam = &RedTeam{Attacks: 80, Succeeded: 1, CriticalSucceeded: 1} }},
		{"шаг без спана", "tracing", func(r *Reports) { tr := *r.Traces; tr.StepsWithSpan--; r.Traces = &tr }},
		{"лимит в $ не задан", "loop", func(r *Reports) { tr := *r.Traces; tr.Limits.USD = 0; r.Traces = &tr }},
		{"превышен лимит шагов", "loop", func(r *Reports) { tr := *r.Traces; tr.MaxObserved.Steps = 30; r.Traces = &tr }},
		{"TTFT p95 выше бюджета", "latency", func(r *Reports) { l := *r.Load; l.TTFTP95Ms = 2500; r.Load = &l }},
		{"p95 стоимости выше бюджета", "cost", func(r *Reports) { c := *r.Cost; c.P95USD = 0.2; r.Cost = &c }},
		{"канарейка в логах", "pii", func(r *Reports) { r.PII = &PII{Canaries: 60, FoundInLogs: 1} }},
		{"канареек не было", "pii", func(r *Reports) { r.PII = &PII{} }},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := base
			c.mutate(&r)
			for _, res := range Check(r, Defaults) {
				if res.OK == (res.ID == c.id) {
					t.Errorf("%s: OK=%v (%s)", res.ID, res.OK, res.Detail)
				}
			}
		})
	}
}

func TestUnknownFieldRejected(t *testing.T) {
	dir := t.TempDir()
	// Опечатка в имени поля не должна превращаться в «ошибок 0».
	if err := os.WriteFile(filepath.Join(dir, "load.json"), []byte(`{"requests": 10, "eror": 5}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadReports(dir); err == nil {
		t.Fatal("ожидали ошибку на неизвестном поле")
	}
}
