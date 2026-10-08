package rag

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

type fakeSearch map[string][]Hit

func (f fakeSearch) Search(_ context.Context, q string, k int) ([]Hit, error) { return f[q], nil }

// scripted отдаёт решения по очереди; последнее повторяется.
type scripted struct {
	plans   []Decision
	calls   int
	partial bool
}

func (s *scripted) Plan(_ context.Context, _ string, _ []Hit) (Decision, error) {
	d := s.plans[min(s.calls, len(s.plans)-1)]
	s.calls++
	return d, nil
}

func (s *scripted) Answer(_ context.Context, _ string, ev []Hit, partial bool) (string, error) {
	s.partial = partial
	return fmt.Sprintf("ответ по %d чанкам", len(ev)), nil
}

var lim = Limits{MaxIters: 3, MaxSearches: 5, K: 5, MaxEvidence: 20}

func TestAgenticSimpleQuestionIsOneRound(t *testing.T) {
	s := fakeSearch{"часы работы": {{ID: 1, Content: "9–18"}}}
	p := &scripted{plans: []Decision{{Enough: true}}}
	res, err := AgenticAnswer(context.Background(), s, p, "часы работы", lim)
	if err != nil || res.Iters != 1 || res.Searches != 1 || res.Partial {
		t.Fatalf("простой вопрос — один раунд: %+v %v", res, err)
	}
}

func TestAgenticMultiHop(t *testing.T) {
	q := "Кто руководит командой, которая владеет биллингом?"
	s := fakeSearch{
		q: {{ID: 1, Content: "Биллингом владеет команда Payments"}, {ID: 7, Content: "Биллинг: SLA"}},
		"руководитель команды Payments": {{ID: 2, Content: "Payments руководит Анна"}, {ID: 1}},
	}
	p := &scripted{plans: []Decision{{Queries: []string{"руководитель команды Payments"}}, {Enough: true}}}
	res, err := AgenticAnswer(context.Background(), s, p, q, lim)
	if err != nil || res.Iters != 2 || res.Searches != 2 || res.Partial {
		t.Fatalf("multi-hop за два раунда: %+v %v", res, err)
	}
	if len(res.Evidence) != 3 { // ID 1 пришёл дважды — дедупликация
		t.Fatalf("evidence %d, ждали 3", len(res.Evidence))
	}
}

func TestAgenticStopsOnBudget(t *testing.T) {
	s := fakeSearch{}
	n := 0
	p := &scripted{}
	for range 10 { // модель всегда недовольна и всегда придумывает новый запрос
		n++
		p.plans = append(p.plans, Decision{Queries: []string{fmt.Sprintf("q%d", n)}})
	}
	res, err := AgenticAnswer(context.Background(), s, p, "вопрос", lim)
	if err != nil || res.Iters != lim.MaxIters || !res.Partial || !p.partial || res.Searches > lim.MaxSearches {
		t.Fatalf("бюджет: %+v %v", res, err)
	}
}

func TestAgenticDetectsLoop(t *testing.T) {
	p := &scripted{plans: []Decision{{Queries: []string{"вопрос"}}}} // просит искать то же самое
	res, _ := AgenticAnswer(context.Background(), fakeSearch{}, p, "вопрос", lim)
	if res.Searches != 1 || !res.Partial {
		t.Fatalf("повтор запроса — остановка: %+v", res)
	}
}

type failing struct{}

func (failing) Rerank(context.Context, string, []string, int) ([]Ranked, error) {
	return nil, errors.New("timeout")
}

func TestRerankHits(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			Query     string   `json:"query"`
			Documents []string `json:"documents"`
			Model     string   `json:"model"`
			TopK      int      `json:"top_k"`
		}
		if r.URL.Path != "/rerank" || json.NewDecoder(r.Body).Decode(&in) != nil || len(in.Documents) != 3 || in.TopK != 2 {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		fmt.Fprint(w, `{"object":"list","data":[{"index":2,"relevance_score":0.91},{"index":9,"relevance_score":0.8},{"index":0,"relevance_score":0.12}],"model":"rerank-3","usage":{"total_tokens":42}}`)
	}))
	defer srv.Close()
	v := &Voyage{HC: srv.Client(), BaseURL: srv.URL, Key: "k", Model: "rerank-3"}
	hits := []Hit{{ID: 10}, {ID: 11}, {ID: 12}}

	out, degraded := RerankHits(context.Background(), v, "429 ретраи", hits, 2, 0.3)
	if degraded || len(out) != 1 || out[0].ID != 12 {
		t.Fatalf("ждали только ID 12 (0.12 ниже порога, index 9 — мусор): %+v", out)
	}
	out, degraded = RerankHits(context.Background(), failing{}, "q", hits, 2, 0.3)
	if !degraded || len(out) != 2 || out[0].ID != 10 {
		t.Fatalf("при отказе — исходный порядок: %+v", out)
	}
}
