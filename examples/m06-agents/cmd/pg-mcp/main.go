// Команда pg-mcp — MCP-сервер из m06.html#mcp-go: read-only SQL к Postgres (tool),
// описание схемы (resource) и шаблон недельного отчёта (prompt).
//
//	PG_RO_DSN=postgres://mcp_ro@localhost:5432/analytics go run ./cmd/pg-mcp
package main

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log"
	"os"
	"regexp"
	"strings"

	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type QueryIn struct {
	SQL   string `json:"sql" jsonschema:"one PostgreSQL SELECT statement, no trailing semicolon"`
	Limit int    `json:"limit,omitempty" jsonschema:"max rows to return, default 100, max 500"`
}

type QueryOut struct {
	Columns   []string `json:"columns"`
	Rows      [][]any  `json:"rows"`
	Truncated bool     `json:"truncated"`
}

func main() {
	// Роль в DSN должна иметь только SELECT — это главная защита, всё остальное — пояса.
	db, err := sql.Open("pgx", os.Getenv("PG_RO_DSN"))
	if err != nil {
		log.Fatal(err) // log пишет в stderr — stdout занят протоколом
	}
	db.SetMaxOpenConns(4)
	if err := newServer(db).Run(context.Background(), &mcp.StdioTransport{}); err != nil {
		log.Fatal(err)
	}
}

func newServer(db *sql.DB) *mcp.Server {
	srv := mcp.NewServer(&mcp.Implementation{Name: "pg-readonly", Version: "v0.2.0"}, nil)
	mcp.AddTool(srv, &mcp.Tool{
		Name: "pg_query",
		Description: "Run a read-only SQL SELECT against the analytics Postgres database. " +
			"Use for questions about orders, users and payments. Results are capped at 500 rows " +
			"and 5s execution; aggregate in SQL instead of fetching raw rows. " +
			"On errors the message lists what to fix.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true},
	}, queryHandler(db))

	// Resource: данные, которые host сам кладёт в контекст (application-controlled).
	srv.AddResource(&mcp.Resource{
		URI:         "schema://analytics",
		Name:        "analytics-schema",
		MIMEType:    "text/markdown",
		Description: "Tables, columns and join keys of the analytics database. Attach before writing SQL.",
	}, func(_ context.Context, req *mcp.ReadResourceRequest) (*mcp.ReadResourceResult, error) {
		return &mcp.ReadResourceResult{Contents: []*mcp.ResourceContents{
			{URI: req.Params.URI, MIMEType: "text/markdown", Text: schemaDoc},
		}}, nil
	})

	// Prompt: шаблон, который пользователь выбирает явно (слэш-команда в клиенте).
	srv.AddPrompt(&mcp.Prompt{
		Name:        "weekly_report",
		Description: "Orders and revenue report for one ISO week",
		Arguments:   []*mcp.PromptArgument{{Name: "week", Description: "ISO week, e.g. 2026-W40", Required: true}},
	}, func(_ context.Context, req *mcp.GetPromptRequest) (*mcp.GetPromptResult, error) {
		week := req.Params.Arguments["week"]
		if !isoWeek.MatchString(week) { // аргумент попадёт в промпт — валидируем как любой ввод
			return nil, fmt.Errorf("week must look like 2026-W40, got %q", week)
		}
		return &mcp.GetPromptResult{
			Description: "Weekly report for " + week,
			Messages: []*mcp.PromptMessage{{Role: "user", Content: &mcp.TextContent{Text: "Build a report for ISO week " + week +
				": orders count, revenue, average order value, and the change against the previous week. " +
				"Read the schema://analytics resource first, aggregate in SQL with pg_query, show the queries you ran."}}},
		}, nil
	})
	return srv
}

var isoWeek = regexp.MustCompile(`^\d{4}-W(0[1-9]|[1-4]\d|5[0-3])$`)

const schemaDoc = `# analytics
- orders(id, user_id → users.id, created_at timestamptz, status: new|paid|refunded, total_cents bigint)
- users(id, created_at, country char(2)) — без PII: email и имена в другой БД
- payments(id, order_id → orders.id, paid_at, amount_cents, provider)
Деньги — в центах; «выручка» = sum(total_cents) по status = 'paid'.`

func queryHandler(db *sql.DB) func(context.Context, *mcp.CallToolRequest, QueryIn) (*mcp.CallToolResult, QueryOut, error) {
	return func(ctx context.Context, _ *mcp.CallToolRequest, in QueryIn) (*mcp.CallToolResult, QueryOut, error) {
		q := strings.TrimSpace(in.SQL)
		if strings.Contains(q, ";") {
			return nil, QueryOut{}, errors.New("multiple statements are not allowed; remove ';'")
		}
		limit := in.Limit
		if limit <= 0 || limit > 500 {
			limit = 100
		}
		tx, err := db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true}) // BEGIN READ ONLY
		if err != nil {
			return nil, QueryOut{}, err
		}
		defer tx.Rollback() // только чтение — коммитить нечего
		if _, err := tx.ExecContext(ctx, "SET LOCAL statement_timeout = '5s'"); err != nil {
			return nil, QueryOut{}, err
		}
		rows, err := tx.QueryContext(ctx, fmt.Sprintf("SELECT * FROM (%s) AS q LIMIT %d", q, limit+1))
		if err != nil {
			return nil, QueryOut{}, fmt.Errorf("query failed: %w. Check table/column names with information_schema", err)
		}
		defer rows.Close()
		out := QueryOut{}
		out.Columns, _ = rows.Columns()
		for rows.Next() {
			vals := make([]any, len(out.Columns))
			ptrs := make([]any, len(vals))
			for i := range vals {
				ptrs[i] = &vals[i]
			}
			if err := rows.Scan(ptrs...); err != nil {
				return nil, QueryOut{}, err
			}
			for i, v := range vals {
				if b, ok := v.([]byte); ok {
					vals[i] = string(b)
				}
			}
			if len(out.Rows) == limit {
				out.Truncated = true
				break
			}
			out.Rows = append(out.Rows, vals)
		}
		return nil, out, rows.Err()
	}
}
