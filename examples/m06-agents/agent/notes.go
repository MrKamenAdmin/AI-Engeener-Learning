package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"

	"github.com/anthropics/anthropic-sdk-go"
)

// Notes — заметки агента в одном файле (NOTES.md, progress): чтение и полная перезапись.
// Дописывания нет намеренно: с лимитом размера агент вынужден сжимать заметки, а не копить лог.
type Notes struct {
	Path     string
	MaxBytes int
}

func (n Notes) Tool() Tool {
	return Tool{
		Param: anthropic.ToolParam{
			Name: "notes",
			Description: anthropic.String("Your persistent notes file; it survives context compaction and restarts. " +
				"op=read returns the whole file. op=write REPLACES the whole file with content. " +
				fmt.Sprintf("Limit %d bytes. Keep: goal, plan, done/todo lists, decisions with reasons, open questions. ", n.MaxBytes) +
				"Do not paste tool outputs: store paths and IDs to re-fetch them. Read notes after a compaction."),
			InputSchema: anthropic.ToolInputSchemaParam{
				Properties: map[string]any{
					"op":      map[string]any{"type": "string", "enum": []string{"read", "write"}},
					"content": map[string]any{"type": "string", "description": "full new content, required for write"},
				},
				Required: []string{"op"},
			},
		},
		Run: n.run,
	}
}

func (n Notes) run(_ context.Context, input json.RawMessage) (string, error) {
	var in struct {
		Op      string `json:"op"`
		Content string `json:"content"`
	}
	if err := json.Unmarshal(input, &in); err != nil {
		return "", err
	}
	switch in.Op {
	case "read":
		b, err := os.ReadFile(n.Path)
		if errors.Is(err, fs.ErrNotExist) {
			return "(no notes yet)", nil
		}
		return string(b), err
	case "write":
		if len(in.Content) > n.MaxBytes {
			return "", fmt.Errorf("notes are %d bytes, limit is %d: condense them — merge done items, "+
				"drop details you can re-fetch, keep decisions and next steps", len(in.Content), n.MaxBytes)
		}
		// Запись через временный файл и rename: обрыв посреди записи не оставит полфайла.
		tmp := n.Path + ".tmp"
		if err := os.WriteFile(tmp, []byte(in.Content), 0o644); err != nil {
			return "", err
		}
		if err := os.Rename(tmp, n.Path); err != nil {
			return "", err
		}
		return fmt.Sprintf("saved %d/%d bytes", len(in.Content), n.MaxBytes), nil
	default:
		return "", fmt.Errorf("unknown op %q: use read or write", in.Op)
	}
}
