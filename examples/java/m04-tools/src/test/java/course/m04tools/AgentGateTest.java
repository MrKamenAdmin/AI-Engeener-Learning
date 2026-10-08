package course.m04tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import course.m04tools.AgentGate.Change;
import course.m04tools.AgentGate.Out;
import course.m04tools.AgentGate.Plan;
import course.m04tools.AgentGate.Runner;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AgentGateTest {
    static final List<String> CHECK = List.of("make", "check");
    static final List<String> EVAL = List.of("make", "eval-smoke");

    static Stream<Arguments> planCases() {
        return Stream.of(
                Arguments.of("только docs", List.of(new Change('M', "README.md")), List.of(), 0),
                Arguments.of("java-код", List.of(new Change('M', "src/main/java/docrag/retrieve/Rrf.java")), List.of(CHECK), 0),
                Arguments.of("pom.xml", List.of(new Change('M', "pom.xml")), List.of(CHECK), 0),
                Arguments.of("промпт", List.of(new Change('M', "prompts/answer.v2.txt")), List.of(EVAL), 0),
                Arguments.of("код и промпт",
                        List.of(new Change('A', "src/main/java/docrag/generate/Cite.java"), new Change('M', "prompts/answer.v2.txt")),
                        List.of(CHECK, EVAL), 0),
                Arguments.of("новая миграция можно", List.of(new Change('A', "migrations/002_feedback.sql")), List.of(), 0),
                Arguments.of("правка старой миграции", List.of(new Change('M', "migrations/001_chunks.sql")), List.of(), 1),
                Arguments.of("удаление миграции", List.of(new Change('D', "migrations/001_chunks.sql")), List.of(), 1),
                Arguments.of("эталон evals",
                        List.of(new Change('M', "evals/data/golden.jsonl"), new Change('M', "src/main/java/docrag/X.java")),
                        List.of(CHECK), 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("planCases")
    void plan(String name, List<Change> in, List<List<String>> checks, int violations) {
        Plan p = AgentGate.plan(in);
        assertEquals(checks, p.checks());
        assertEquals(violations, p.violations().size(), () -> "violations = " + p.violations());
    }

    @Test
    void parseNameStatus() {
        List<Change> got = AgentGate.parseNameStatus("M\tsrc/A.java\nA\tmigrations/002 new.sql\n\nD\tOld.java\n");
        assertEquals(List.of(new Change('M', "src/A.java"), new Change('A', "migrations/002 new.sql"), new Change('D', "Old.java")), got);
    }

    /** Отвечает на вызовы git заготовками и считает упавшими проверки из failing. */
    static Runner fakeGit(String diff, String untracked, Set<String> failing) {
        return cmd -> {
            String c = String.join(" ", cmd);
            if (c.startsWith("git merge-base")) return new Out("abc123\n", 0);
            if (c.startsWith("git diff")) return new Out(diff, 0);
            if (c.startsWith("git ls-files")) return new Out(untracked, 0);
            if (failing.contains(c)) return new Out("[ERROR] Tests run: 3, Failures: 1\n[ERROR] FilterTest.drops\nBUILD FAILURE\n", 2);
            return new Out("ok\n", 0);
        };
    }

    static Stream<Arguments> gateCases() {
        return Stream.of(
                Arguments.of("чисто", "M\tREADME.md\n", "", Set.of(), 0, ""),
                Arguments.of("тесты зелёные", "M\tsrc/main/java/docrag/retrieve/Rrf.java\n", "", Set.of(), 0, ""),
                Arguments.of("тесты красные", "M\tsrc/main/java/docrag/retrieve/Rrf.java\n", "", Set.of("make check"), 2, "FilterTest.drops"),
                Arguments.of("новый промпт без evals нельзя", "", "prompts/answer.v3.txt\n", Set.of("make eval-smoke"), 2, "make eval-smoke"),
                Arguments.of("эталон тронут", "M\tevals/data/golden.jsonl\n", "", Set.of(), 2, "golden.jsonl"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("gateCases")
    void gate(String name, String diff, String untracked, Set<String> failing, int code, String stderr) {
        var b = new StringWriter();
        int got = AgentGate.gate(fakeGit(diff, untracked, failing), "main", new PrintWriter(b, true));
        assertEquals(code, got, () -> "stderr=" + b);
        assertTrue(b.toString().contains(stderr), () -> "stderr=" + b + ", want containing " + stderr);
    }
}
