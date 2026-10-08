package agentgate

import (
	"errors"
	"reflect"
	"strings"
	"testing"
)

func TestPlan(t *testing.T) {
	tests := []struct {
		name       string
		in         []Change
		checks     [][]string
		violations int
	}{
		{"только docs", []Change{{'M', "README.md"}}, nil, 0},
		{"go-код", []Change{{'M', "internal/retrieve/rrf.go"}}, [][]string{{"make", "check"}}, 0},
		{"go.mod", []Change{{'M', "go.mod"}}, [][]string{{"make", "check"}}, 0},
		{"промпт", []Change{{'M', "prompts/answer.v2.txt"}}, [][]string{{"make", "eval-smoke"}}, 0},
		{"код и промпт", []Change{{'A', "internal/generate/cite.go"}, {'M', "prompts/answer.v2.txt"}},
			[][]string{{"make", "check"}, {"make", "eval-smoke"}}, 0},
		{"новая миграция можно", []Change{{'A', "migrations/002_feedback.sql"}}, nil, 0},
		{"правка старой миграции", []Change{{'M', "migrations/001_chunks.sql"}}, nil, 1},
		{"удаление миграции", []Change{{'D', "migrations/001_chunks.sql"}}, nil, 1},
		{"эталон evals", []Change{{'M', "evals/data/golden.jsonl"}, {'M', "internal/x.go"}},
			[][]string{{"make", "check"}}, 1},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			checks, v := Plan(tt.in)
			if !reflect.DeepEqual(checks, tt.checks) {
				t.Errorf("checks = %v, want %v", checks, tt.checks)
			}
			if len(v) != tt.violations {
				t.Errorf("violations = %v, want %d", v, tt.violations)
			}
		})
	}
}

func TestParseNameStatus(t *testing.T) {
	got := ParseNameStatus("M\tinternal/a.go\nA\tmigrations/002 new.sql\n\nD\told.go\n")
	want := []Change{{'M', "internal/a.go"}, {'A', "migrations/002 new.sql"}, {'D', "old.go"}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
}

// fakeGit отвечает на вызовы git заготовками и считает упавшими проверки из failing.
func fakeGit(diff, untracked string, failing map[string]bool) Runner {
	return func(name string, args ...string) (string, error) {
		cmd := name + " " + strings.Join(args, " ")
		switch {
		case strings.HasPrefix(cmd, "git merge-base"):
			return "abc123\n", nil
		case strings.HasPrefix(cmd, "git diff"):
			return diff, nil
		case strings.HasPrefix(cmd, "git ls-files"):
			return untracked, nil
		case failing[cmd]:
			return "--- FAIL: TestFilter (0.00s)\nFAIL\n", errors.New("exit status 2")
		}
		return "ok\n", nil
	}
}

func TestGate(t *testing.T) {
	tests := []struct {
		name, diff, untracked string
		failing               map[string]bool
		code                  int
		stderr                string
	}{
		{"чисто", "M\tREADME.md\n", "", nil, 0, ""},
		{"тесты зелёные", "M\tinternal/retrieve/rrf.go\n", "", nil, 0, ""},
		{"тесты красные", "M\tinternal/retrieve/rrf.go\n", "", map[string]bool{"make check": true}, 2, "FAIL: TestFilter"},
		{"новый промпт без evals нельзя", "", "prompts/answer.v3.txt\n", map[string]bool{"make eval-smoke": true}, 2, "make eval-smoke"},
		{"эталон тронут", "M\tevals/data/golden.jsonl\n", "", nil, 2, "golden.jsonl"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var b strings.Builder
			code := Gate(fakeGit(tt.diff, tt.untracked, tt.failing), "main", &b)
			if code != tt.code || !strings.Contains(b.String(), tt.stderr) {
				t.Fatalf("code=%d stderr=%q, want code=%d containing %q", code, b.String(), tt.code, tt.stderr)
			}
		})
	}
}
