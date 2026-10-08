package rag

import (
	"bytes"
	"cmp"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"slices"
)

// Ranked — позиция документа во входном списке и его релевантность по мнению reranker'а.
type Ranked struct {
	Index int
	Score float64
}

type Reranker interface {
	Rerank(ctx context.Context, query string, docs []string, topK int) ([]Ranked, error)
}

// Voyage — reranker Voyage AI: POST {BaseURL}/rerank.
type Voyage struct {
	HC      *http.Client
	BaseURL string // "https://api.voyageai.com/v1"; в тестах — httptest
	Key     string
	Model   string // "rerank-3" (на октябрь 2026); "rerank-2.5" ещё доступна
}

func (v *Voyage) Rerank(ctx context.Context, query string, docs []string, topK int) ([]Ranked, error) {
	body, err := json.Marshal(map[string]any{"query": query, "documents": docs, "model": v.Model, "top_k": topK})
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, v.BaseURL+"/rerank", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+v.Key)
	req.Header.Set("Content-Type", "application/json")
	resp, err := v.HC.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		return nil, fmt.Errorf("voyage rerank: status %d: %s", resp.StatusCode, msg)
	}
	var out struct {
		Data []struct {
			Index          int     `json:"index"`
			RelevanceScore float64 `json:"relevance_score"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, err
	}
	ranked := make([]Ranked, 0, len(out.Data))
	for _, d := range out.Data {
		ranked = append(ranked, Ranked{Index: d.Index, Score: d.RelevanceScore})
	}
	return ranked, nil
}

type Scored struct {
	Hit
	Score float64
}

// RerankHits переранжирует кандидатов и отсекает всё ниже minScore. Если reranker
// недоступен, отдаёт исходный порядок (RRF) и degraded = true: хуже, чем с rerank, лучше, чем 500.
func RerankHits(ctx context.Context, r Reranker, query string, hits []Hit, topK int, minScore float64) (out []Scored, degraded bool) {
	docs := make([]string, len(hits))
	for i, h := range hits {
		docs[i] = h.Heading + "\n" + h.Content
	}
	ranked, err := r.Rerank(ctx, query, docs, topK)
	if err != nil {
		for _, h := range hits[:min(topK, len(hits))] {
			out = append(out, Scored{Hit: h}) // скоров нет — порог не применить
		}
		return out, true
	}
	slices.SortFunc(ranked, func(a, b Ranked) int { return cmp.Compare(b.Score, a.Score) }) // по убыванию; порядку API не доверяем
	for _, rk := range ranked {
		if rk.Index < 0 || rk.Index >= len(hits) {
			continue // ответ внешнего API — недоверенный ввод
		}
		if rk.Score < minScore || len(out) == topK {
			break
		}
		out = append(out, Scored{Hit: hits[rk.Index], Score: rk.Score})
	}
	return out, false // пусто — честное «в базе нет ответа», а не повод отдать мусор модели
}
