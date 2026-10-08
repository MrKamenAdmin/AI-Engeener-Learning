package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/anthropics/anthropic-sdk-go"
)

// AsTool превращает агента в инструмент для родителя. Каждый вызов — свой список сообщений
// (чистый контекст), свой бюджет поверх бюджета родителя, и наверх уходит только итог,
// урезанный до maxResult байт: десятки тысяч токенов чтения остаются у субагента.
func (a Agent) AsTool(name, desc string, maxUSD float64, maxResult int) Tool {
	return Tool{
		Param: anthropic.ToolParam{
			Name:        name,
			Description: anthropic.String(desc),
			InputSchema: anthropic.ToolInputSchemaParam{
				Properties: map[string]any{"task": map[string]any{
					"type":        "string",
					"description": "Self-contained task: goal, scope and boundaries, expected output format. The subagent sees nothing else.",
				}},
				Required: []string{"task"},
			},
		},
		Run: func(ctx context.Context, input json.RawMessage) (string, error) {
			var in struct {
				Task string `json:"task"`
			}
			if err := json.Unmarshal(input, &in); err != nil || in.Task == "" {
				return "", errors.New("pass a non-empty task")
			}
			sub := a                                              // копия конфигурации; историю Run создаст с нуля
			sub.Model = &Budgeted{Model: a.Model, MaxUSD: maxUSD} // лимит на этот вызов, расходы видит и родитель
			res, err := sub.Run(ctx, in.Task)
			if err != nil {
				return "", fmt.Errorf("subagent stopped after %d iterations: %w; narrow the task", res.Iters, err)
			}
			return truncate(res.Text, maxResult, "\n…[truncated]"), nil
		},
	}
}
