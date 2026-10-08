package llmmw

// CREATE EXTENSION IF NOT EXISTS vector;
// CREATE TABLE llm_cache (
//   id bigserial PRIMARY KEY,
//   scope text NOT NULL,              -- hash(tenant, model, system prompt, версия промпта)
//   query text NOT NULL,
//   embedding vector(1024) NOT NULL,
//   response text NOT NULL,
//   created_at timestamptz NOT NULL DEFAULT now());
// CREATE INDEX ON llm_cache USING hnsw (embedding vector_cosine_ops);
// CREATE INDEX ON llm_cache (scope, created_at);
// Фильтр по scope + HNSW: в pgvector ≥ 0.8 включите SET hnsw.iterative_scan = relaxed_order,
// иначе после фильтрации может не остаться кандидатов. Пул: pgxvec.RegisterTypes в AfterConnect.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/pgvector/pgvector-go"
)

type SemCache struct {
	Next      LLM
	DB        *pgxpool.Pool
	Embed     func(ctx context.Context, text string) ([]float32, error)
	Threshold float64 // cosine similarity; калибруется на размеченных парах
	TTL       time.Duration
}

func (c *SemCache) Complete(ctx context.Context, req Request) (*Response, error) {
	q := lastUserText(req)
	if q == "" || len(req.Messages) > 1 { // кэшируем только первый вопрос без истории
		return c.Next.Complete(ctx, req)
	}
	emb, err := c.Embed(ctx, q)
	if err != nil {
		return c.Next.Complete(ctx, req) // кэш — оптимизация, а не точка отказа
	}
	vec, scope := pgvector.NewVector(emb), scopeKey(req)

	var text string
	var sim float64
	err = c.DB.QueryRow(ctx, `
		SELECT response, 1 - (embedding <=> $1) FROM llm_cache
		WHERE scope = $2 AND created_at > now() - make_interval(secs => $3)
		ORDER BY embedding <=> $1 LIMIT 1`, vec, scope, c.TTL.Seconds()).Scan(&text, &sim)
	switch {
	case err == nil && sim >= c.Threshold:
		return &Response{Text: text, Model: req.Model, StopReason: "end_turn", Route: "semcache"}, nil
	case err != nil && !errors.Is(err, pgx.ErrNoRows):
		slog.WarnContext(ctx, "semcache lookup", "err", err)
	}

	resp, err := c.Next.Complete(ctx, req)
	if err != nil {
		return nil, err
	}
	if resp.StopReason == "end_turn" { // не кэшируем обрезанные ответы и refusal
		if _, err := c.DB.Exec(ctx, `INSERT INTO llm_cache (scope, query, embedding, response)
			VALUES ($1, $2, $3, $4)`, scope, q, vec, resp.Text); err != nil {
			slog.WarnContext(ctx, "semcache store", "err", err)
		}
	}
	return resp, nil
}

func scopeKey(r Request) string {
	h := sha256.Sum256([]byte(r.Tenant + "\x00" + r.Model + "\x00" + r.System))
	return hex.EncodeToString(h[:])
}
