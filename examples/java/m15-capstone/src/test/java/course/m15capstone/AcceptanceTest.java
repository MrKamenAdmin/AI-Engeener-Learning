package course.m15capstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import course.m15capstone.Acceptance.Evals;
import course.m15capstone.Acceptance.Limits;
import course.m15capstone.Acceptance.Pii;
import course.m15capstone.Acceptance.RedTeam;
import course.m15capstone.Acceptance.Reports;
import course.m15capstone.Acceptance.Result;
import course.m15capstone.Acceptance.Traces;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class AcceptanceTest {
    static final Path REPORTS = Path.of("reports");

    @ParameterizedTest
    @CsvSource({
        "8, 10, 0.4902, 0.9433",
        "0, 30, 0, 0.1135", // «0 успешных атак из 30» — это ASR до 11% с 95% уверенностью
        "0, 73, 0, 0.0500",
    })
    void wilson(int k, int n, double lo, double hi) {
        double[] ci = Acceptance.wilson(k, n);
        assertEquals(lo, ci[0], 5e-4);
        assertEquals(hi, ci[1], 5e-4);
    }

    @Test
    void exampleReportsAccepted() throws IOException {
        for (Result res : Acceptance.check(Acceptance.loadReports(REPORTS), Acceptance.DEFAULTS)) {
            assertTrue(res.ok(), res.id() + ": " + res.detail());
        }
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                f("нет отчёта evals", "quality", r -> r.withEvals(null)),
                f("90% на 50 кейсах: нижняя граница ДИ < 0.80", "quality", r -> r.withEvals(new Evals(50, 45, 0, null))),
                f("критичный кейс провален", "quality", r -> { var e = r.evals(); return r.withEvals(new Evals(e.n(), e.passed(), 1, e.ci95())); }),
                f("ASR выше порога", "redteam", r -> r.withRedTeam(new RedTeam(80, 6, 0))),
                f("одна критичная атака прошла", "redteam", r -> r.withRedTeam(new RedTeam(80, 1, 1))),
                f("шаг без спана", "tracing", r -> { var t = r.traces(); return r.withTraces(new Traces(t.runs(), t.steps(), t.stepsWithSpan() - 1, t.limits(), t.maxObserved(), t.limitHits())); }),
                f("лимит в $ не задан", "loop", r -> { var t = r.traces(); var l = t.limits(); return r.withTraces(new Traces(t.runs(), t.steps(), t.stepsWithSpan(), new Limits(l.steps(), l.tokens(), 0), t.maxObserved(), t.limitHits())); }),
                f("превышен лимит шагов", "loop", r -> { var t = r.traces(); var m = t.maxObserved(); return r.withTraces(new Traces(t.runs(), t.steps(), t.stepsWithSpan(), t.limits(), new Limits(30, m.tokens(), m.usd()), t.limitHits())); }),
                f("TTFT p95 выше бюджета", "latency", r -> { var l = r.load(); return r.withLoad(new Acceptance.Load(l.requests(), l.errors(), 2500, l.totalP95Ms())); }),
                f("p95 стоимости выше бюджета", "cost", r -> { var c = r.cost(); return r.withCost(new Acceptance.Cost(c.requests(), c.meanUsd(), 0.2)); }),
                f("канарейка в логах", "pii", r -> r.withPii(new Pii(60, 1, 0))),
                f("канареек не было", "pii", r -> r.withPii(new Pii(0, 0, 0))));
    }

    private static Arguments f(String name, String id, UnaryOperator<Reports> mutate) {
        return Arguments.of(name, id, mutate);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void failures(String name, String id, UnaryOperator<Reports> mutate) throws IOException {
        Reports r = mutate.apply(Acceptance.loadReports(REPORTS));
        for (Result res : Acceptance.check(r, Acceptance.DEFAULTS)) {
            // Проваливается ровно один критерий — тот, который сломали.
            assertEquals(!res.id().equals(id), res.ok(), res.id() + ": " + res.detail());
        }
    }

    @Test
    void unknownFieldRejected(@TempDir Path dir) throws IOException {
        // Опечатка в имени поля не должна превращаться в «ошибок 0».
        Files.writeString(dir.resolve("load.json"), "{\"requests\": 10, \"eror\": 5}");
        assertThrows(IOException.class, () -> Acceptance.loadReports(dir));
    }
}
