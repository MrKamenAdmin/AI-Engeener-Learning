// Package agentgate — Stop-хук Claude Code для репозитория docrag (проект A).
//
// Срабатывает, когда агент собирается закончить ход, и по списку изменённых
// файлов решает, какие проверки обязательны. Код выхода 2 — «не заканчивай»:
// Claude Code не даёт агенту остановиться и передаёт ему stderr как обратную
// связь. Код 1 — сбой самого хука, он не блокирует.
//
// Подключение в .claude/settings.json:
//
//	{"hooks": {"Stop": [{"hooks": [{"type": "command",
//	  "command": "cd \"$CLAUDE_PROJECT_DIR\" && go run ./cmd/agentgate",
//	  "timeout": 900}]}]}}
//
// Зацикливания не будет: после 8 продолжений подряд от Stop-хуков Claude Code
// завершает ход сам (счётчик сбрасывается при каждом вызове инструмента).
package agentgate

import (
	"fmt"
	"io"
	"strings"
)

// Change — изменённый файл: статус из git diff --name-status ('A', 'M', 'D') и путь.
type Change struct {
	Status byte
	Path   string
}

// Plan по изменениям возвращает обязательные проверки (цели Makefile — те же,
// что перечислены в AGENTS.md) и нарушения, которые агент должен откатить.
func Plan(cs []Change) (checks [][]string, violations []string) {
	var goCode, prompts bool
	for _, c := range cs {
		switch {
		case strings.HasPrefix(c.Path, "evals/data/"):
			violations = append(violations, c.Path+": эталон evals меняет человек отдельным PR, а не агент вместе с кодом")
		case strings.HasPrefix(c.Path, "migrations/") && c.Status != 'A':
			violations = append(violations, c.Path+": применённую миграцию не правят, добавьте новую")
		case strings.HasPrefix(c.Path, "prompts/"):
			prompts = true
		case strings.HasSuffix(c.Path, ".go"), c.Path == "go.mod", c.Path == "go.sum":
			goCode = true
		}
	}
	if goCode {
		checks = append(checks, []string{"make", "check"}) // gofmt -l, go vet, go test -race
	}
	if prompts {
		checks = append(checks, []string{"make", "eval-smoke"}) // промпт не проверить go test
	}
	return checks, violations
}

// ParseNameStatus разбирает вывод git diff --name-status --no-renames.
func ParseNameStatus(out string) []Change {
	var cs []Change
	for _, line := range strings.Split(out, "\n") {
		st, path, ok := strings.Cut(strings.TrimSpace(line), "\t")
		if ok && st != "" && path != "" {
			cs = append(cs, Change{Status: st[0], Path: path})
		}
	}
	return cs
}

// Runner выполняет команду и возвращает её объединённый stdout+stderr.
type Runner func(name string, args ...string) (string, error)

// Gate возвращает код выхода хука: 0 — можно завершать, 2 — блок, 1 — сбой хука.
func Gate(run Runner, base string, stderr io.Writer) int {
	// Сравниваем рабочее дерево с точкой ответвления: ловим и закоммиченное агентом, и нет.
	mb, err := run("git", "merge-base", "HEAD", base)
	if err != nil {
		mb = "HEAD" // ветки base нет: сравниваем с последним коммитом
	}
	diff, err := run("git", "diff", "--name-status", "--no-renames", strings.TrimSpace(mb))
	if err != nil {
		fmt.Fprintln(stderr, "agentgate: git diff:", err)
		return 1
	}
	untracked, err := run("git", "ls-files", "--others", "--exclude-standard")
	if err != nil {
		fmt.Fprintln(stderr, "agentgate: git ls-files:", err)
		return 1
	}
	cs := ParseNameStatus(diff)
	for _, p := range strings.Split(untracked, "\n") {
		if p = strings.TrimSpace(p); p != "" {
			cs = append(cs, Change{Status: 'A', Path: p})
		}
	}

	checks, violations := Plan(cs)
	if len(violations) > 0 {
		fmt.Fprintln(stderr, "Нельзя завершать: изменены защищённые файлы. Откатите их и опишите в отчёте, что нужно поменять:")
		for _, v := range violations {
			fmt.Fprintln(stderr, "  -", v)
		}
		return 2
	}
	for _, c := range checks {
		if out, err := run(c[0], c[1:]...); err != nil {
			fmt.Fprintf(stderr, "Нельзя завершать: `%s` не прошёл. Исправьте причину, не ослабляя тесты и не трогая эталон, и запустите снова.\n%s\n",
				strings.Join(c, " "), tail(out, 30))
			return 2
		}
	}
	return 0
}

// tail оставляет последние n строк: агенту нужен конец лога, а не 5000 строк контекста.
func tail(s string, n int) string {
	lines := strings.Split(strings.TrimRight(s, "\n"), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.Join(lines, "\n")
}
