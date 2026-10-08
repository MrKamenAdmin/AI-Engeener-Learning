package course.m08evals;

import static org.junit.jupiter.api.Assertions.*;

import course.m08evals.Gate.CaseResult;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GateTest {
    static final Gate.Config CFG = new Gate.Config(0.8, 0.03, List.of("safety"), 0.1, 5000, 1);

    /** 100 кейсов (10 safety + 90 refund), failing — номера кейсов, которые не прошли. */
    static List<CaseResult> run(int... failing) {
        Set<Integer> bad = new java.util.HashSet<>();
        for (int i : failing) bad.add(i);
        List<CaseResult> out = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            out.add(new CaseResult("c%03d".formatted(i), i < 10 ? "safety" : "refund", "ok", !bad.contains(i)));
        }
        return out;
    }

    static int[] span(int from, int to) {
        return IntStream.range(from, to).toArray();
    }

    @Test
    void greenOnSameResults() {
        var r = Gate.compare(run(span(90, 100)), run(span(90, 100)), CFG);
        assertTrue(r.passed(), r.problems().toString());
    }

    @Test
    void redOnRegression() {
        // PR ломает 8 кейсов refund и ничего не чинит: 90% → 82%.
        var r = Gate.compare(run(span(90, 100)), run(span(82, 100)), CFG);
        assertFalse(r.passed());
        assertEquals(8, r.regressed().size());
        assertTrue(r.problems().getFirst().contains("значимое падение"), r.problems().toString());
        assertTrue(r.markdown().contains("c082"), "markdown must list regressed cases");
    }

    @Test
    void redOnCriticalCase() {
        var r = Gate.compare(run(), run(3), CFG); // один safety-кейс упал
        assertFalse(r.passed());
        assertTrue(r.problems().getFirst().contains("c003"), r.problems().toString());
    }

    @Test
    void invalidRunOnInfraErrors() {
        List<CaseResult> cur = new ArrayList<>(run());
        for (int i = 20; i < 35; i++) {
            CaseResult c = cur.get(i);
            cur.set(i, new CaseResult(c.id(), c.tag(), "error", false)); // 15% — 429 и таймауты
        }
        assertFalse(Gate.compare(run(), cur, CFG).passed(), "run with 15% infra errors must be invalid");
    }

    @Test
    void withoutBaseline() {
        var r = Gate.compare(null, run(span(90, 100)), CFG);
        assertTrue(r.passed(), r.problems().toString());
        assertFalse(r.warnings().isEmpty());
        assertTrue(r.markdown().startsWith("## Evals: ✅"));
    }

    @Test
    void readResultsIgnoresExtraFields() throws Exception {
        String in = """
                {"id":"a","tag":"refund","status":"ok","passed":true,"output":"…длинный ответ…"}
                {"id":"a","tag":"refund","status":"ok","passed":false}
                {"id":"b","tag":"refund","status":"error","error":"429"}
                """;
        List<CaseResult> rows = Gate.readResults(new StringReader(in));
        assertEquals(3, rows.size());
        var s = Gate.scores(rows);
        assertEquals(0.5, s.byId().get("a").score());
        assertEquals(1.0 / 3, s.errorRate());
    }
}
