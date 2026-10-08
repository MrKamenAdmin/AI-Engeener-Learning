package course.m09prod.slo;

import static course.m09prod.slo.BurnRate.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntToDoubleFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BurnRateTest {
    /** Поминутные счётчики: perMin запросов в минуту, доля ошибок errAt(minutesAgo). */
    static Function<Duration, Counts> series(double perMin, IntToDoubleFunction errAt) {
        return d -> {
            double total = 0, bad = 0;
            for (int m = 0; m < d.toMinutes(); m++) {
                total += perMin;
                bad += perMin * errAt.applyAsDouble(m);
            }
            return new Counts(total, bad);
        };
    }

    @Test
    void burnRateTest() {
        assertEquals(1, burnRate(new Counts(1000, 5), 0.995), 1e-9, "0.5% ошибок при SLO 99.5% — burn rate 1");
        assertEquals(-1, budgetLeft(new Counts(1000, 10), 0.995), 1e-9, "двойной перерасход — бюджет -100%");
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("норма", (IntToDoubleFunction) m -> 0.002, List.of()),
                Arguments.of("авария последние 20 минут: 30% плохих",
                        (IntToDoubleFunction) m -> m < 20 ? 0.30 : 0.002, List.of(14.4)),
                Arguments.of("тлеющая деградация 4 дня: 0.6% плохих", (IntToDoubleFunction) m -> 0.006, List.of(1.0)),
                Arguments.of("авария была 2 часа назад и закончилась",
                        (IntToDoubleFunction) m -> m > 120 && m < 140 ? 0.5 : 0.002, List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void firingTest(String name, IntToDoubleFunction errAt, List<Double> want) {
        final double slo = 0.995; // качество ответа: 99.5% «хороших»
        var got = firing(WORKBOOK, slo, 50, series(100, errAt)).stream().map(Rule::burn).toList();
        assertEquals(want, got);
    }

    @Test
    void firingIgnoresSparseSamples() {
        // Онлайн-judge оценивает 2% ответов: при 100 rpm это 2 оценки в минуту,
        // 10 в 5-минутном окне и 120 в часовом. 9 плохих за последние 15 минут — burn rate 15
        // в часовом окне и 60 в коротком: может быть авария, а может быть шум выборки.
        var w = series(2, m -> m < 15 ? 0.3 : 0);
        assertEquals(1, firing(WORKBOOK.subList(0, 1), 0.995, 0, w).size(), "без порога событий быстрое правило срабатывает");
        assertEquals(0, firing(WORKBOOK.subList(0, 1), 0.995, 50, w).size(), "по 10 оценкам в коротком окне не будим дежурного");
    }
}
