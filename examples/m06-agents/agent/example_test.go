package agent_test

import (
	"context"
	"fmt"
	"log"
	"os/exec"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/modelcontextprotocol/go-sdk/mcp"

	"aiec/examples/m06-agents/agent"
)

// Сборка агента для длинной задачи: общий бюджет, дешёвый субагент-исследователь
// с чистым контекстом, компакция и заметки. Пример компилируется, но не запускается
// в go test (нет строки Output): ему нужен ANTHROPIC_API_KEY.
func Example_longTask() {
	client := anthropic.NewClient() // ключ из ANTHROPIC_API_KEY
	root := &agent.Budgeted{Model: &client.Messages, MaxUSD: 5}

	researcher := agent.Agent{
		Model: root, ModelID: "claude-haiku-4-5", MaxIters: 15,
		System: "Answer the question using the read-only tools. Return facts with file paths, at most 300 words.",
		Tools:  map[string]agent.Tool{ /* grep, read_file — только чтение */ },
	}
	notes := agent.Notes{Path: "NOTES.md", MaxBytes: 8 << 10}
	lead := agent.Agent{
		Model: root, ModelID: "claude-opus-5", MaxIters: 80,
		System: "You fix bugs in this repository. Keep NOTES.md current: plan, done, decisions, next step.",
		Tools: map[string]agent.Tool{
			"notes":    notes.Tool(),
			"research": researcher.AsTool("research", "Delegate a read-only investigation; returns a short report.", 0.30, 6000),
		},
		Compact: &agent.Compactor{Model: root, ModelID: "claude-haiku-4-5", Threshold: 120_000, KeepTurns: 6},
		Confirm: agent.StdinConfirm(),
	}
	res, err := lead.Run(context.Background(), "Find and fix the deadlock in inventory.Reserve")
	fmt.Printf("%d iters, %d compactions, peak %d tokens, $%.2f: %v\n%s\n",
		res.Iters, res.Compactions, res.PeakCtx, root.Spent(), err, res.Text)
}

// Host подключает MCP-сервер дочерним процессом и отдаёт его инструменты своему циклу.
func ExampleMCPTools() {
	ctx := context.Background()
	client := mcp.NewClient(&mcp.Implementation{Name: "my-agent", Version: "v0.1.0"}, nil)
	session, err := client.Connect(ctx, &mcp.CommandTransport{Command: exec.Command("pg-mcp")}, nil)
	if err != nil {
		log.Fatal(err)
	}
	defer session.Close()
	tools, err := agent.MCPTools(ctx, session, map[string]bool{"pg_query": true})
	if err != nil {
		log.Fatal(err)
	}
	llm := anthropic.NewClient()
	a := agent.Agent{Model: &llm.Messages, ModelID: "claude-opus-5", MaxIters: 20, Tools: tools, Confirm: agent.StdinConfirm()}
	res, err := a.Run(ctx, "How many orders were paid last week?")
	fmt.Println(res.Text, err)
}
