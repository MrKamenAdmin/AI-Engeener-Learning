package agent

import (
	"context"
	"encoding/json"
	"errors"
	"strings"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// MCPTools превращает инструменты MCP-сервера в инструменты цикла: tools/list → определения
// для Messages API, tool_use → tools/call. Политику подтверждений задаёт host: autoApprove —
// allowlist имён, которые можно вызывать без человека. Аннотации сервера — подсказка от
// недоверенной стороны: они могут ужесточить политику, но не ослабить её.
func MCPTools(ctx context.Context, s *mcp.ClientSession, autoApprove map[string]bool) (map[string]Tool, error) {
	tools := map[string]Tool{}
	for t, err := range s.Tools(ctx, nil) {
		if err != nil {
			return nil, err
		}
		raw, err := json.Marshal(t.InputSchema)
		if err != nil {
			return nil, err
		}
		var schema struct {
			Properties any      `json:"properties"`
			Required   []string `json:"required"`
		}
		if err := json.Unmarshal(raw, &schema); err != nil {
			return nil, err
		}
		name := t.Name
		readOnly := t.Annotations != nil && t.Annotations.ReadOnlyHint
		tools[name] = Tool{
			Param: anthropic.ToolParam{
				Name:        name,
				Description: anthropic.String(t.Description),
				InputSchema: anthropic.ToolInputSchemaParam{Properties: schema.Properties, Required: schema.Required},
			},
			Dangerous: !autoApprove[name] || !readOnly,
			Run: func(ctx context.Context, input json.RawMessage) (string, error) {
				res, err := s.CallTool(ctx, &mcp.CallToolParams{Name: name, Arguments: input})
				if err != nil {
					return "", err // протокольная ошибка или обрыв соединения
				}
				var b strings.Builder
				for _, c := range res.Content {
					if tc, ok := c.(*mcp.TextContent); ok {
						b.WriteString(tc.Text)
					}
				}
				if res.IsError {
					return "", errors.New(b.String()) // ошибка инструмента: модель увидит текст и исправится
				}
				return b.String(), nil
			},
		}
	}
	return tools, nil
}
