package course.m04tools;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * AgentGate — Stop-хук Claude Code для репозитория docrag (проект A).
 *
 * <p>Срабатывает, когда агент собирается закончить ход, и по списку изменённых
 * файлов решает, какие проверки обязательны. Код выхода 2 — «не заканчивай»:
 * Claude Code не даёт агенту остановиться и передаёт ему stderr как обратную
 * связь. Код 1 — сбой самого хука, он не блокирует.
 *
 * <p>Подключение в .claude/settings.json (jar собирается {@code mvn -pl m04-tools package}):
 *
 * <pre>
 * {"hooks": {"Stop": [{"hooks": [{"type": "command",
 *   "command": "cd \"$CLAUDE_PROJECT_DIR\" &amp;&amp; java -jar tools/agentgate.jar",
 *   "timeout": 900}]}]}}
 * </pre>
 *
 * <p>Зацикливания не будет: после 8 продолжений подряд от Stop-хуков Claude Code
 * завершает ход сам (счётчик сбрасывается при каждом вызове инструмента).
 */
public final class AgentGate {
    private AgentGate() {}

    /** Изменённый файл: статус из git diff --name-status ('A', 'M', 'D') и путь. */
    public record Change(char status, String path) {}

    /** Обязательные проверки (цели Makefile) и нарушения, которые агент должен откатить. */
    public record Plan(List<List<String>> checks, List<String> violations) {}

    /** Объединённый stdout+stderr команды и код выхода. */
    public record Out(String text, int exit) {
        public boolean ok() {
            return exit == 0;
        }
    }

    /** Выполняет команду. Ненулевой exit — ошибка (в т.ч. «не удалось запустить»). */
    @FunctionalInterface
    public interface Runner {
        Out run(String... cmd);
    }

    /**
     * По изменениям возвращает обязательные проверки (цели Makefile — те же,
     * что перечислены в AGENTS.md) и нарушения, которые агент должен откатить.
     */
    public static Plan plan(List<Change> cs) {
        boolean javaCode = false;
        boolean prompts = false;
        List<String> violations = new ArrayList<>();
        for (Change c : cs) {
            String p = c.path();
            if (p.startsWith("evals/data/")) {
                violations.add(p + ": эталон evals меняет человек отдельным PR, а не агент вместе с кодом");
            } else if (p.startsWith("migrations/") && c.status() != 'A') {
                violations.add(p + ": применённую миграцию не правят, добавьте новую");
            } else if (p.startsWith("prompts/")) {
                prompts = true;
            } else if (p.endsWith(".java") || p.equals("pom.xml") || p.endsWith("/pom.xml")) {
                javaCode = true;
            }
        }
        List<List<String>> checks = new ArrayList<>();
        if (javaCode) {
            checks.add(List.of("make", "check")); // mvn verify: компиляция, линтеры, тесты
        }
        if (prompts) {
            checks.add(List.of("make", "eval-smoke")); // промпт не проверить юнит-тестом
        }
        return new Plan(checks, violations);
    }

    /** Разбирает вывод git diff --name-status --no-renames. */
    public static List<Change> parseNameStatus(String out) {
        List<Change> cs = new ArrayList<>();
        for (String line : out.split("\n")) {
            String l = line.strip();
            int tab = l.indexOf('\t');
            if (tab > 0 && tab < l.length() - 1) {
                cs.add(new Change(l.charAt(0), l.substring(tab + 1)));
            }
        }
        return cs;
    }

    /** Возвращает код выхода хука: 0 — можно завершать, 2 — блок, 1 — сбой хука. */
    public static int gate(Runner run, String base, PrintWriter stderr) {
        // Сравниваем рабочее дерево с точкой ответвления: ловим и закоммиченное агентом, и нет.
        Out mb = run.run("git", "merge-base", "HEAD", base);
        String ref = mb.ok() ? mb.text().strip() : "HEAD"; // ветки base нет: сравниваем с последним коммитом
        Out diff = run.run("git", "diff", "--name-status", "--no-renames", ref);
        if (!diff.ok()) {
            stderr.println("agentgate: git diff: " + diff.text().strip());
            return 1;
        }
        Out untracked = run.run("git", "ls-files", "--others", "--exclude-standard");
        if (!untracked.ok()) {
            stderr.println("agentgate: git ls-files: " + untracked.text().strip());
            return 1;
        }
        List<Change> cs = parseNameStatus(diff.text());
        for (String p : untracked.text().split("\n")) {
            if (!p.isBlank()) {
                cs.add(new Change('A', p.strip()));
            }
        }

        Plan plan = plan(cs);
        if (!plan.violations().isEmpty()) {
            stderr.println("Нельзя завершать: изменены защищённые файлы. Откатите их и опишите в отчёте, что нужно поменять:");
            plan.violations().forEach(v -> stderr.println("  - " + v));
            return 2;
        }
        for (List<String> c : plan.checks()) {
            Out out = run.run(c.toArray(String[]::new));
            if (!out.ok()) {
                stderr.printf("Нельзя завершать: `%s` не прошёл. Исправьте причину, не ослабляя тесты и не трогая эталон, и запустите снова.%n%s%n",
                        String.join(" ", c), tail(out.text(), 30));
                return 2;
            }
        }
        return 0;
    }

    /** Оставляет последние n строк: агенту нужен конец лога, а не 5000 строк контекста. */
    static String tail(String s, int n) {
        List<String> lines = s.stripTrailing().lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - n), lines.size()));
    }
}
