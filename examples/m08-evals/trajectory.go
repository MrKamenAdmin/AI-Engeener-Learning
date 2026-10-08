package evals

import (
	"fmt"
	"maps"
	"slices"
	"strings"
)

// Step — один вызов инструмента (поля как в agent.Step из модуля 6).
type Step struct {
	Tool    string `json:"tool"`
	Input   string `json:"input"`
	IsError bool   `json:"is_error,omitempty"`
}

// Trajectory — запись одного прогона (trial) агента на задаче.
type Trajectory struct {
	TaskID  string            `json:"task_id"`
	Steps   []Step            `json:"steps"`
	State   map[string]string `json:"final_state"` // снимок среды после прогона
	CostUSD float64           `json:"cost_usd"`
}

// Spec — правильное поведение на задаче. Эталонная траектория не нужна:
// проверяем итог и ограничения на путь.
type Spec struct {
	WantState  map[string]string `json:"want_state"` // главный критерий: состояние среды
	Required   []string          `json:"required"`   // должны быть вызваны
	Allowed    []string          `json:"allowed"`    // полезны, но не обязательны
	Forbidden  []string          `json:"forbidden"`  // ни одного вызова
	Before     [][2]string       `json:"before"`     // {A, B}: первый A раньше первого B
	MaxSteps   int               `json:"max_steps"`
	MaxCostUSD float64           `json:"max_cost_usd"`
}

// Verdict — результат проверки одного прогона.
type Verdict struct {
	Success   bool     // итог верный и ни одно жёсткое ограничение не нарушено
	Failures  []string // "вид: детали" — сырьё для error analysis
	Precision float64  // доля вызовов из Required ∪ Allowed
	Recall    float64  // доля Required, которые были вызваны
	Redundant int      // повтор успешного вызова с теми же аргументами
}

func Check(s Spec, t Trajectory) Verdict {
	var v Verdict
	fail := func(kind, format string, a ...any) {
		v.Failures = append(v.Failures, kind+": "+fmt.Sprintf(format, a...))
	}
	for _, k := range slices.Sorted(maps.Keys(s.WantState)) {
		if got := t.State[k]; got != s.WantState[k] {
			fail("state", "%s = %q, want %q", k, got, s.WantState[k])
		}
	}
	first := map[string]int{} // индекс первого вызова инструмента
	done := map[string]bool{} // успешные вызовы tool+input
	useful := 0
	for i, st := range t.Steps {
		if _, ok := first[st.Tool]; !ok {
			first[st.Tool] = i
		}
		key := st.Tool + "\x00" + st.Input
		if done[key] {
			v.Redundant++ // повтор после ошибки — это восстановление, а не лишний шаг
		}
		if !st.IsError {
			done[key] = true
		}
		if slices.Contains(s.Required, st.Tool) || slices.Contains(s.Allowed, st.Tool) {
			useful++
		}
		if slices.Contains(s.Forbidden, st.Tool) {
			fail("forbidden", "%s at step %d", st.Tool, i+1)
		}
	}
	called := 0
	for _, r := range s.Required {
		if _, ok := first[r]; ok {
			called++
		} else {
			fail("required", "%s not called", r)
		}
	}
	for _, p := range s.Before {
		b, ok := first[p[1]]
		if !ok {
			continue
		}
		if a, ok := first[p[0]]; !ok || a > b {
			fail("order", "%s must precede %s", p[0], p[1])
		}
	}
	if s.MaxSteps > 0 && len(t.Steps) > s.MaxSteps {
		fail("steps", "%d > limit %d", len(t.Steps), s.MaxSteps)
	}
	if s.MaxCostUSD > 0 && t.CostUSD > s.MaxCostUSD {
		fail("cost", "$%.3f > limit $%.3f", t.CostUSD, s.MaxCostUSD)
	}
	v.Success = len(v.Failures) == 0
	v.Precision = ratio(useful, len(t.Steps))
	v.Recall = ratio(called, len(s.Required))
	return v
}

// Summary — агрегат по набору задач, на каждой не меньше k прогонов.
type Summary struct {
	Tasks, Runs   int
	SuccessRate   float64        // доля успешных прогонов
	PassHatK      float64        // pass^k: все k попыток задачи успешны
	MeanSteps     float64        // по всем прогонам
	MeanCostUSD   float64        // по всем прогонам
	MeanPrecision float64        // tool-call precision
	MeanRecall    float64        // tool-call recall
	Redundant     int            // лишних повторных вызовов всего
	FailureKinds  map[string]int // state/forbidden/order/... → число прогонов
}

func Aggregate(specs map[string]Spec, runs []Trajectory, k int) (Summary, error) {
	s := Summary{FailureKinds: map[string]int{}}
	byTask := map[string][]bool{}
	for _, t := range runs {
		spec, ok := specs[t.TaskID]
		if !ok {
			return Summary{}, fmt.Errorf("no spec for task %q", t.TaskID)
		}
		v := Check(spec, t)
		byTask[t.TaskID] = append(byTask[t.TaskID], v.Success)
		s.MeanSteps += float64(len(t.Steps))
		s.MeanCostUSD += t.CostUSD
		s.MeanPrecision += v.Precision
		s.MeanRecall += v.Recall
		s.Redundant += v.Redundant
		kinds := map[string]bool{}
		for _, f := range v.Failures {
			kind, _, _ := strings.Cut(f, ":")
			kinds[kind] = true
		}
		for kind := range kinds {
			s.FailureKinds[kind]++
		}
	}
	s.Tasks, s.Runs = len(byTask), len(runs)
	if s.Runs == 0 {
		return s, nil
	}
	success := 0
	for id, res := range byTask {
		if len(res) < k {
			return Summary{}, fmt.Errorf("task %q: %d runs < k=%d", id, len(res), k)
		}
		c := 0
		for _, ok := range res {
			if ok {
				c++
			}
		}
		success += c
		s.PassHatK += passHatK(len(res), c, k)
	}
	n := float64(s.Runs)
	s.SuccessRate = float64(success) / n
	s.PassHatK /= float64(s.Tasks)
	s.MeanSteps /= n
	s.MeanCostUSD /= n
	s.MeanPrecision /= n
	s.MeanRecall /= n
	return s, nil
}

// passHatK — несмещённая оценка pass^k по n прогонам с c успехами: C(c,k)/C(n,k).
func passHatK(n, c, k int) float64 {
	p := 1.0
	for i := range k {
		p *= float64(c-i) / float64(n-i)
	}
	return max(0, p)
}

func ratio(a, b int) float64 {
	if b == 0 {
		return 1
	}
	return float64(a) / float64(b)
}
