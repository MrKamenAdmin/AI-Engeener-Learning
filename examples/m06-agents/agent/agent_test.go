package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/anthropics/anthropic-sdk-go/option"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// fake — модель без сети. reply получает номер вызова и запрос, возвращает ответ в JSON
// формата Messages API. input_tokens fake считает сам (≈ байты/4), как это сделал бы API,
// и проверяет инварианты, на которых API ответил бы 400.
type fake struct {
	mu    sync.Mutex
	n     int
	reqs  []anthropic.MessageNewParams
	reply func(n int, p anthropic.MessageNewParams) string
}

func (f *fake) New(_ context.Context, p anthropic.MessageNewParams, _ ...option.RequestOption) (*anthropic.Message, error) {
	if err := validate(p.Messages); err != nil {
		return nil, err
	}
	f.mu.Lock()
	n := f.n
	f.n++
	f.reqs = append(f.reqs, p)
	f.mu.Unlock()
	raw, _ := json.Marshal(p.Messages)
	body := fmt.Sprintf(`{"id":"msg_%d","type":"message","role":"assistant","model":%q,%s,"usage":{"input_tokens":%d,"output_tokens":50}}`,
		n, p.Model, f.reply(n, p), 1500+len(raw)/4)
	var m anthropic.Message
	return &m, json.Unmarshal([]byte(body), &m)
}

// validate: первое сообщение от user, роли чередуются, каждый tool_result отвечает на tool_use
// из предыдущего сообщения, и на каждый tool_use есть tool_result в следующем.
func validate(msgs []anthropic.MessageParam) error {
	if len(msgs) == 0 || msgs[0].Role != anthropic.MessageParamRoleUser {
		return errors.New("400: first message must be user")
	}
	for i := range msgs {
		if i > 0 && msgs[i].Role == msgs[i-1].Role {
			return fmt.Errorf("400: roles must alternate at %d", i)
		}
		uses := map[string]bool{}
		if i > 0 {
			for _, b := range msgs[i-1].Content {
				if b.OfToolUse != nil {
					uses[b.OfToolUse.ID] = true
				}
			}
		}
		for _, b := range msgs[i].Content {
			if b.OfToolResult != nil {
				if !uses[b.OfToolResult.ToolUseID] {
					return fmt.Errorf("400: orphan tool_result %s at %d", b.OfToolResult.ToolUseID, i)
				}
				delete(uses, b.OfToolResult.ToolUseID)
			}
		}
		if i > 0 && len(uses) > 0 && msgs[i].Role == anthropic.MessageParamRoleUser {
			return fmt.Errorf("400: tool_use without tool_result before %d", i)
		}
	}
	return nil
}

func toolUse(id, name, input string) string {
	return fmt.Sprintf(`"stop_reason":"tool_use","content":[{"type":"text","text":"step %s"},{"type":"tool_use","id":%q,"name":%q,"input":%s}]`, id, id, name, input)
}

func endTurn(text string) string {
	return fmt.Sprintf(`"stop_reason":"end_turn","content":[{"type":"text","text":%q}]`, text)
}

func echoTool(name string, calls *int) Tool {
	return Tool{Param: anthropic.ToolParam{Name: name}, Run: func(context.Context, json.RawMessage) (string, error) {
		*calls++
		return strings.Repeat("log line ", 400), nil // ≈1K токенов на вызов
	}}
}

func TestBudgetStopsRun(t *testing.T) {
	m := &fake{reply: func(n int, _ anthropic.MessageNewParams) string { return toolUse(fmt.Sprint(n), "grep", `{}`) }}
	root := &Budgeted{Model: m, MaxUSD: 0.05}
	var calls int
	a := Agent{Model: root, ModelID: "claude-opus-5", Tools: map[string]Tool{"grep": echoTool("grep", &calls)}, MaxIters: 100}
	res, err := a.Run(context.Background(), "find the bug")
	if !errors.Is(err, ErrBudget) {
		t.Fatalf("want ErrBudget, got %v", err)
	}
	if spent := root.Spent(); spent < 0.05 || spent > 0.08 || res.Iters >= 100 {
		t.Fatalf("spent $%.4f in %d iters: budget must stop the run within one call", spent, res.Iters)
	}
}

func TestCompactorKeepsPairsAndTail(t *testing.T) {
	msgs := []anthropic.MessageParam{anthropic.NewUserMessage(anthropic.NewTextBlock("TASK: audit docs"))}
	for i := range 10 {
		id := fmt.Sprintf("t%d", i)
		msgs = append(msgs,
			anthropic.MessageParam{Role: anthropic.MessageParamRoleAssistant, Content: []anthropic.ContentBlockParamUnion{
				{OfThinking: &anthropic.ThinkingBlockParam{Thinking: "hmm", Signature: "sig"}},
				{OfToolUse: &anthropic.ToolUseBlockParam{ID: id, Name: "read", Input: map[string]any{"doc": i}}},
			}},
			anthropic.NewUserMessage(anthropic.NewToolResultBlock(id, fmt.Sprintf("content of doc %d", i), false)))
	}
	sum := &fake{reply: func(int, anthropic.MessageNewParams) string { return endTurn("docs 0-6 read, no issues") }}
	c := &Compactor{Model: sum, ModelID: "claude-haiku-4-5", Threshold: 1000, KeepTurns: 3}

	if out, did, _ := c.Maybe(context.Background(), msgs, 999); did || len(out) != len(msgs) {
		t.Fatal("below threshold nothing must change")
	}
	cut := &fake{reply: func(int, anthropic.MessageNewParams) string { return `"stop_reason":"max_tokens","content":[]` }}
	if out, did, err := (&Compactor{Model: cut, Threshold: 1000, KeepTurns: 3}).Maybe(context.Background(), msgs, 5000); err == nil || did || len(out) != len(msgs) {
		t.Fatal("a truncated summary must not replace the history")
	}
	out, did, err := c.Maybe(context.Background(), msgs, 5000)
	if err != nil || !did {
		t.Fatalf("compaction expected: %v", err)
	}
	if len(out) != 1+2*3 || validate(out) != nil {
		t.Fatalf("want head + 3 turns, valid pairs: len=%d err=%v", len(out), validate(out))
	}
	if head := out[0].Content; *head[0].GetText() != "TASK: audit docs" || !strings.Contains(*head[1].GetText(), "docs 0-6 read") {
		t.Fatal("head must keep the task verbatim and carry the summary")
	}
	for _, m := range out {
		for _, b := range m.Content {
			if b.OfThinking != nil {
				t.Fatal("thinking blocks of kept turns must be dropped")
			}
		}
	}
	// История, оборванная на ответе модели (чётная длина): граница сдвигается к assistant.
	if odd, _, _ := c.Maybe(context.Background(), msgs[:len(msgs)-1], 5000); odd[1].Role != anthropic.MessageParamRoleAssistant {
		t.Fatal("kept tail must start with an assistant turn")
	}
	transcript := *sum.reqs[0].Messages[0].Content[0].GetText()
	if !strings.Contains(transcript, "content of doc 0") || strings.Contains(transcript, "content of doc 9") {
		t.Fatal("summarizer must see old turns and not the kept tail")
	}
}

// 60+ шагов: компакция держит контекст ограниченным, а все запросы остаются валидными для API.
func TestLongHorizonWithNotesAndCompaction(t *testing.T) {
	notes := Notes{Path: filepath.Join(t.TempDir(), "NOTES.md"), MaxBytes: 2000}
	const docs = 60
	m := &fake{reply: func(n int, _ anthropic.MessageNewParams) string {
		switch {
		case n == docs+docs/10:
			return endTurn("audit done")
		case n%11 == 10: // каждые 10 документов — заметки
			return toolUse(fmt.Sprint(n), "notes", fmt.Sprintf(`{"op":"write","content":"done: docs 0-%d"}`, n-n/11-1))
		default:
			return toolUse(fmt.Sprint(n), "read_doc", fmt.Sprintf(`{"id":%d}`, n))
		}
	}}
	sum := &fake{reply: func(int, anthropic.MessageNewParams) string { return endTurn("summary: see notes") }}
	root := &Budgeted{Model: m, MaxUSD: 10}
	var reads int
	a := Agent{
		Model: root, ModelID: "claude-opus-5", MaxIters: 100,
		Tools:   map[string]Tool{"read_doc": echoTool("read_doc", &reads), "notes": notes.Tool()},
		Compact: &Compactor{Model: sum, ModelID: "claude-haiku-4-5", Threshold: 12_000, KeepTurns: 4},
	}
	res, err := a.Run(context.Background(), "audit all 60 docs")
	if err != nil || res.Text != "audit done" {
		t.Fatalf("run: %v %q", err, res.Text)
	}
	if reads != docs || len(res.Steps) < 60 || res.Compactions < 3 {
		t.Fatalf("reads=%d steps=%d compactions=%d", reads, len(res.Steps), res.Compactions)
	}
	if res.PeakCtx > 16_000 { // порог + один ход: без компакции пик ≈ 60K
		t.Fatalf("context not bounded: peak %d", res.PeakCtx)
	}
	if b, _ := os.ReadFile(notes.Path); string(b) != "done: docs 0-59" {
		t.Fatalf("notes = %q", b)
	}
	if !strings.Contains(*m.reqs[len(m.reqs)-1].Messages[0].Content[1].GetText(), "summary: see notes") {
		t.Fatal("after compaction the head must carry the summary")
	}
}

func TestNotesLimit(t *testing.T) {
	n := Notes{Path: filepath.Join(t.TempDir(), "NOTES.md"), MaxBytes: 10}
	if out, _ := n.run(context.Background(), json.RawMessage(`{"op":"read"}`)); out != "(no notes yet)" {
		t.Fatal(out)
	}
	if _, err := n.run(context.Background(), json.RawMessage(`{"op":"write","content":"way too long notes"}`)); err == nil {
		t.Fatal("oversized write must fail with a hint to condense")
	}
	n.run(context.Background(), json.RawMessage(`{"op":"write","content":"plan: x"}`))
	if out, _ := n.run(context.Background(), json.RawMessage(`{"op":"read"}`)); out != "plan: x" {
		t.Fatal(out)
	}
}

func TestIdempotentDedup(t *testing.T) {
	var keys []string
	send := Idempotent("session-1", Tool{Param: anthropic.ToolParam{Name: "email_send"},
		Run: func(ctx context.Context, _ json.RawMessage) (string, error) {
			keys = append(keys, IdempotencyKey(ctx))
			return "sent", nil
		}})
	ctx := context.Background()
	send.Run(ctx, json.RawMessage(`{"to":"a@x.io","body":"hi"}`))
	out, _ := send.Run(ctx, json.RawMessage(`{ "body": "hi", "to": "a@x.io" }`)) // тот же вызов, другой порядок ключей
	send.Run(ctx, json.RawMessage(`{"to":"b@x.io","body":"hi"}`))
	if len(keys) != 2 || keys[0] == "" || keys[0] == keys[1] || !strings.HasPrefix(out, "Already done") {
		t.Fatalf("keys=%v out=%q", keys, out)
	}
}

func TestRetryOnlyTransient(t *testing.T) {
	var calls int
	flaky := func(context.Context) (string, error) {
		if calls++; calls < 3 {
			return "", fmt.Errorf("%w: 503 from search backend", ErrTransient)
		}
		return "ok", nil
	}
	if out, err := retry(context.Background(), 3, flaky); err != nil || out != "ok" || calls != 3 {
		t.Fatalf("out=%q err=%v calls=%d", out, err, calls)
	}
	calls = 0
	permanent := func(context.Context) (string, error) { calls++; return "", errors.New("column not found") }
	if _, err := retry(context.Background(), 3, permanent); err == nil || calls != 1 {
		t.Fatalf("permanent errors must not be retried: calls=%d", calls)
	}
}

func TestSubagentIsolatedContextAndBudget(t *testing.T) {
	m := &fake{reply: func(n int, p anthropic.MessageNewParams) string {
		if len(p.Messages) == 1 && strings.Contains(*p.Messages[0].Content[0].GetText(), "find usages") {
			return endTurn(strings.Repeat("usage found; ", 100)) // субагент: длинный итог
		}
		if len(p.Messages) == 1 {
			return toolUse("r1", "research", `{"task":"find usages of Reserve()"}`)
		}
		return endTurn("fixed")
	}}
	root := &Budgeted{Model: m, MaxUSD: 1}
	researcher := Agent{Model: root, ModelID: "claude-haiku-4-5", MaxIters: 10}
	lead := Agent{Model: root, ModelID: "claude-opus-5", MaxIters: 10,
		Tools: map[string]Tool{"research": researcher.AsTool("research", "Delegate a read-only search.", 0.2, 200)}}
	res, err := lead.Run(context.Background(), "fix the deadlock")
	if err != nil || res.Text != "fixed" {
		t.Fatalf("%v %q", err, res.Text)
	}
	if len(m.reqs) != 3 || len(m.reqs[1].Messages) != 1 { // lead, subagent с чистой историей, lead
		t.Fatalf("subagent must start with a fresh history: %d requests", len(m.reqs))
	}
	result := *m.reqs[2].Messages[2].Content[0].OfToolResult.Content[0].GetText()
	if len(result) > 200+len("\n…[truncated]") {
		t.Fatalf("parent got %d bytes, want ≤ 200 + marker", len(result))
	}
	if root.Spent() == 0 {
		t.Fatal("subagent spending must be charged to the root budget")
	}
}

func TestRunSessionsStopsWhenStuck(t *testing.T) {
	progress := 0 // в жизни — passes=true в features.json; здесь каждая сессия закрывает один пункт
	m := &fake{reply: func(int, anthropic.MessageNewParams) string { progress++; return endTurn("one feature done") }}
	a := &Agent{Model: m, ModelID: "claude-opus-5", MaxIters: 5}
	check := func(context.Context) (bool, string, error) {
		return progress >= 3, fmt.Sprint(progress), nil
	}
	if n, err := RunSessions(context.Background(), a, "build", check, 10, 2); err != nil || n != 3 {
		t.Fatalf("want done after 3 sessions: n=%d err=%v", n, err)
	}
	progress = 0
	m.reply = func(int, anthropic.MessageNewParams) string { return endTurn("nothing changed") }
	if _, err := RunSessions(context.Background(), a, "build", check, 10, 2); !errors.Is(err, ErrStuck) {
		t.Fatalf("want ErrStuck, got %v", err)
	}
}

func TestMCPToolsHostPolicy(t *testing.T) {
	ctx := context.Background()
	srv := mcp.NewServer(&mcp.Implementation{Name: "docs", Version: "v0"}, nil)
	type in struct {
		Q string `json:"q"`
	}
	mcp.AddTool(srv, &mcp.Tool{Name: "docs_search", Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true}},
		func(_ context.Context, _ *mcp.CallToolRequest, a in) (*mcp.CallToolResult, any, error) {
			if a.Q == "" {
				return nil, nil, errors.New("empty query; pass q")
			}
			return &mcp.CallToolResult{Content: []mcp.Content{&mcp.TextContent{Text: "found: " + a.Q}}}, nil, nil
		})
	mcp.AddTool(srv, &mcp.Tool{Name: "docs_delete"}, func(context.Context, *mcp.CallToolRequest, in) (*mcp.CallToolResult, any, error) {
		return &mcp.CallToolResult{}, nil, nil
	})
	st, ct := mcp.NewInMemoryTransports()
	ss, _ := srv.Connect(ctx, st, nil)
	defer ss.Close()
	cs, err := mcp.NewClient(&mcp.Implementation{Name: "host", Version: "v0"}, nil).Connect(ctx, ct, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer cs.Close()

	tools, err := MCPTools(ctx, cs, map[string]bool{"docs_search": true, "docs_delete": true})
	if err != nil {
		t.Fatal(err)
	}
	if tools["docs_search"].Dangerous || !tools["docs_delete"].Dangerous {
		t.Fatal("only allowlisted AND read-only tools run without confirmation")
	}
	if out, err := tools["docs_search"].Run(ctx, json.RawMessage(`{"q":"compaction"}`)); err != nil || out != "found: compaction" {
		t.Fatalf("%q %v", out, err)
	}
	if _, err := tools["docs_search"].Run(ctx, json.RawMessage(`{"q":""}`)); err == nil || !strings.Contains(err.Error(), "empty query") {
		t.Fatalf("isError must become an error with the server's text: %v", err)
	}
}
