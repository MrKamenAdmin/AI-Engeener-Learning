package evals

import (
	"encoding/json"
	"fmt"
	"io"
	"maps"
	"slices"
	"strings"
)

// CaseResult — строка results.jsonl (формат мини-фреймворка модуля; лишние поля
// вроде output игнорируются). Повторы одного кейса — несколько строк с тем же id.
type CaseResult struct {
	ID     string `json:"id"`
	Tag    string `json:"tag"`
	Status string `json:"status"` // "ok" или "error" (инфраструктура, не качество)
	Passed bool   `json:"passed"`
}

func ReadResults(r io.Reader) ([]CaseResult, error) {
	var out []CaseResult
	dec := json.NewDecoder(r)
	for dec.More() {
		var c CaseResult
		if err := dec.Decode(&c); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, nil
}

type GateConfig struct {
	MinPassRate  float64  // абсолютный порог на PR, например 0.85
	NoiseFloor   float64  // разброс pass rate между повторами baseline, например 0.03
	CriticalTags []string // срезы, где допустимо только 100%
	MaxErrorRate float64  // выше — прогон невалиден, например 0.1
	Resamples    int      // число bootstrap-ресемплов, например 10000
	Seed         uint64
}

type SliceDiff struct {
	Tag       string
	N         int     // общих кейсов в срезе
	Base, Cur float64 // pass rate на общих кейсах
	Diff      Interval
}

type GateReport struct {
	Cur       Interval // pass rate PR по всем его кейсам
	Overall   SliceDiff
	Slices    []SliceDiff
	Regressed []string // кейсы, где PR хуже baseline
	Fixed     []string
	Problems  []string // непустой список = гейт красный
	Warnings  []string
}

func (r GateReport) Passed() bool { return len(r.Problems) == 0 }

type caseScore struct {
	tag   string
	score float64 // доля успешных повторов среди повторов без инфраструктурных ошибок
}

// scores сворачивает повторы в оценку кейса и считает долю инфраструктурных ошибок.
func scores(rows []CaseResult) (map[string]caseScore, float64) {
	type acc struct {
		tag        string
		pass, runs int
	}
	m, errs := map[string]*acc{}, 0
	for _, r := range rows {
		a := m[r.ID]
		if a == nil {
			a = &acc{tag: r.Tag}
			m[r.ID] = a
		}
		if r.Status != "ok" {
			errs++
			continue
		}
		a.runs++
		if r.Passed {
			a.pass++
		}
	}
	out := map[string]caseScore{}
	for id, a := range m {
		if a.runs > 0 {
			out[id] = caseScore{a.tag, float64(a.pass) / float64(a.runs)}
		}
	}
	if len(rows) == 0 {
		return out, 0
	}
	return out, float64(errs) / float64(len(rows))
}

func Compare(base, cur []CaseResult, cfg GateConfig) GateReport {
	var rep GateReport
	b, bErr := scores(base)
	c, cErr := scores(cur)
	if cErr > cfg.MaxErrorRate || bErr > cfg.MaxErrorRate {
		rep.Problems = append(rep.Problems, fmt.Sprintf(
			"прогон невалиден: инфраструктурных ошибок %.0f%% (PR), %.0f%% (baseline)", 100*cErr, 100*bErr))
	}

	all := make([]float64, 0, len(c))
	for _, id := range slices.Sorted(maps.Keys(c)) {
		all = append(all, c[id].score)
	}
	rep.Cur = Bootstrap(all, cfg.Resamples, cfg.Seed)
	if !(rep.Cur.Est >= cfg.MinPassRate) {
		rep.Problems = append(rep.Problems, fmt.Sprintf("pass rate %.1f%% ниже порога %.0f%%", 100*rep.Cur.Est, 100*cfg.MinPassRate))
	}

	// Пары по id: сравниваем только кейсы, которые есть в обоих прогонах.
	byTag := map[string][2][]float64{}
	var bs, cs []float64
	for _, id := range slices.Sorted(maps.Keys(c)) {
		cc := c[id]
		if slices.Contains(cfg.CriticalTags, cc.tag) && cc.score < 1 {
			rep.Problems = append(rep.Problems, fmt.Sprintf("критичный кейс %s [%s]: %.0f%% повторов прошло", id, cc.tag, 100*cc.score))
		}
		bb, ok := b[id]
		if !ok {
			continue
		}
		bs, cs = append(bs, bb.score), append(cs, cc.score)
		t := byTag[cc.tag]
		byTag[cc.tag] = [2][]float64{append(t[0], bb.score), append(t[1], cc.score)}
		switch {
		case cc.score < bb.score:
			rep.Regressed = append(rep.Regressed, id)
		case cc.score > bb.score:
			rep.Fixed = append(rep.Fixed, id)
		}
	}
	if n := len(c) - len(bs); n > 0 {
		rep.Warnings = append(rep.Warnings, fmt.Sprintf("%d кейсов нет в baseline: в сравнение не вошли", n))
	}
	if len(bs) == 0 {
		rep.Warnings = append(rep.Warnings, "нет общих кейсов с baseline: проверены только абсолютные пороги")
		return rep
	}

	diff := func(tag string, x, y []float64) SliceDiff {
		return SliceDiff{Tag: tag, N: len(x), Base: mean(x), Cur: mean(y),
			Diff: PairedBootstrap(x, y, cfg.Resamples, cfg.Seed)}
	}
	rep.Overall = diff("всё", bs, cs)
	if d := rep.Overall.Diff; d.Hi < 0 {
		rep.Problems = append(rep.Problems, fmt.Sprintf("значимое падение: Δ %+.1f п.п., 95%% CI [%+.1f; %+.1f]", 100*d.Est, 100*d.Lo, 100*d.Hi))
	} else if d.Est < -cfg.NoiseFloor {
		rep.Problems = append(rep.Problems, fmt.Sprintf("падение %.1f п.п. больше noise floor %.1f п.п.", -100*d.Est, 100*cfg.NoiseFloor))
	}
	// Срезы — предупреждения, а не красный гейт: при 10 срезах один «значимо
	// упадёт» случайно (множественные сравнения). Жёсткие правила — у критичных.
	for _, tag := range slices.Sorted(maps.Keys(byTag)) {
		s := diff(tag, byTag[tag][0], byTag[tag][1])
		rep.Slices = append(rep.Slices, s)
		if s.Diff.Hi < 0 || s.Diff.Est < -cfg.NoiseFloor {
			rep.Warnings = append(rep.Warnings, fmt.Sprintf("срез %s: %.0f%% → %.0f%% (n=%d)", tag, 100*s.Base, 100*s.Cur, s.N))
		}
	}
	return rep
}

// Markdown — отчёт для комментария в PR.
func (r GateReport) Markdown() string {
	var sb strings.Builder
	status := "✅ гейт пройден"
	if !r.Passed() {
		status = "❌ гейт не пройден"
	}
	fmt.Fprintf(&sb, "## Evals: %s\n\n", status)
	fmt.Fprintf(&sb, "PR: **%.1f%%** (95%% CI %.1f–%.1f)\n\n", 100*r.Cur.Est, 100*r.Cur.Lo, 100*r.Cur.Hi)
	for _, p := range r.Problems {
		fmt.Fprintf(&sb, "- ❌ %s\n", p)
	}
	for _, w := range r.Warnings {
		fmt.Fprintf(&sb, "- ⚠️ %s\n", w)
	}
	if r.Overall.N > 0 {
		sb.WriteString("\n| срез | n | main | PR | Δ, п.п. | 95% CI |\n|---|---|---|---|---|---|\n")
		for _, s := range append([]SliceDiff{r.Overall}, r.Slices...) {
			fmt.Fprintf(&sb, "| %s | %d | %.1f%% | %.1f%% | %+.1f | [%+.1f; %+.1f] |\n",
				s.Tag, s.N, 100*s.Base, 100*s.Cur, 100*s.Diff.Est, 100*s.Diff.Lo, 100*s.Diff.Hi)
		}
	}
	fmt.Fprintf(&sb, "\nСтало хуже (%d): %s\n\nСтало лучше (%d): %s\n",
		len(r.Regressed), list(r.Regressed), len(r.Fixed), list(r.Fixed))
	return sb.String()
}

func list(ids []string) string {
	if len(ids) == 0 {
		return "—"
	}
	const limit = 20
	if len(ids) > limit {
		return "`" + strings.Join(ids[:limit], "`, `") + "`" + fmt.Sprintf(" и ещё %d", len(ids)-limit)
	}
	return "`" + strings.Join(ids, "`, `") + "`"
}
