package evals

import (
	"fmt"
	"strings"
	"testing"
)

var cfg = GateConfig{MinPassRate: 0.8, NoiseFloor: 0.03, CriticalTags: []string{"safety"},
	MaxErrorRate: 0.1, Resamples: 5000, Seed: 1}

// run: 100 кейсов (10 safety + 90 refund), failing — id кейсов, которые не прошли.
func run(failing ...int) []CaseResult {
	bad := map[int]bool{}
	for _, i := range failing {
		bad[i] = true
	}
	var out []CaseResult
	for i := range 100 {
		tag := "refund"
		if i < 10 {
			tag = "safety"
		}
		out = append(out, CaseResult{ID: fmt.Sprintf("c%03d", i), Tag: tag, Status: "ok", Passed: !bad[i]})
	}
	return out
}

func span(from, to int) []int {
	var s []int
	for i := from; i < to; i++ {
		s = append(s, i)
	}
	return s
}

func TestGateGreenOnSameResults(t *testing.T) {
	r := Compare(run(span(90, 100)...), run(span(90, 100)...), cfg)
	if !r.Passed() {
		t.Fatalf("problems: %v", r.Problems)
	}
}

func TestGateRedOnRegression(t *testing.T) {
	// PR ломает 8 кейсов refund и ничего не чинит: 90% → 82%.
	r := Compare(run(span(90, 100)...), run(span(82, 100)...), cfg)
	if r.Passed() || len(r.Regressed) != 8 || !strings.Contains(r.Problems[0], "значимое падение") {
		t.Fatalf("report: %+v", r)
	}
	if !strings.Contains(r.Markdown(), "c082") {
		t.Fatal("markdown must list regressed cases")
	}
}

func TestGateRedOnCriticalCase(t *testing.T) {
	r := Compare(run(), run(3), cfg) // один safety-кейс упал
	if r.Passed() || !strings.Contains(r.Problems[0], "c003") {
		t.Fatalf("problems: %v", r.Problems)
	}
}

func TestGateInvalidRunOnInfraErrors(t *testing.T) {
	cur := run()
	for i := 20; i < 35; i++ {
		cur[i].Status = "error" // 15% — 429 и таймауты
	}
	if r := Compare(run(), cur, cfg); r.Passed() {
		t.Fatal("run with 15% infra errors must be invalid")
	}
}

func TestGateWithoutBaseline(t *testing.T) {
	r := Compare(nil, run(span(90, 100)...), cfg)
	if !r.Passed() || len(r.Warnings) == 0 {
		t.Fatalf("report: %+v", r)
	}
}

func TestReadResultsIgnoresExtraFields(t *testing.T) {
	in := `{"id":"a","tag":"refund","status":"ok","passed":true,"output":"…длинный ответ…"}
{"id":"a","tag":"refund","status":"ok","passed":false}
{"id":"b","tag":"refund","status":"error","error":"429"}`
	rows, err := ReadResults(strings.NewReader(in))
	if err != nil || len(rows) != 3 {
		t.Fatalf("rows=%d err=%v", len(rows), err)
	}
	s, errRate := scores(rows)
	if s["a"].score != 0.5 || errRate != 1.0/3 {
		t.Fatalf("scores %v, errRate %v", s, errRate)
	}
}
