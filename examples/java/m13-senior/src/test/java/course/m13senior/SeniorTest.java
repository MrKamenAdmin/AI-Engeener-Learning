package course.m13senior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import course.m13senior.Budget.Alert;
import course.m13senior.Budget.Metrics;
import course.m13senior.Budget.Slo;
import course.m13senior.Budget.Status;
import course.m13senior.Budget.Window;
import course.m13senior.Tco.Option;
import course.m13senior.Tco.Price;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SeniorTest {
    static final double EPS = 1e-9;
    static final Price SONNET = new Price(2, 10), HAIKU = new Price(1, 5); // Sonnet 5 и Haiku 4.5, сентябрь 2026

    // Условные варианты из схемы модуля: дешёвое API и fine-tuned малая модель на своих GPU.
    static final Option CHEAP_API = Option.api("API", Tco.apiCost(3000, 400, 0, HAIKU), 0.3, 0.9);
    static final Option SMALL_FT = Option.selfHost("self-host", 2100, 6e6, 2, 1.3, 0.92);

    static Stream<Arguments> apiCostCases() {
        return Stream.of(
                Arguments.of(SONNET, 0.0, 0.0075),
                Arguments.of(HAIKU, 0.0, 0.00375),
                Arguments.of(SONNET, 0.6, 0.00426),
                Arguments.of(HAIKU, 0.6, 0.00213));
    }

    @ParameterizedTest
    @MethodSource("apiCostCases")
    void apiCost(Price p, double cached, double want) {
        assertEquals(want, Tco.apiCost(3000, 150, cached, p), EPS);
    }

    @Test
    void monthlyTcoCrossover() {
        final double fte = 15000, low = 1e6, high = 5e7;
        assertTrue(Tco.monthly(CHEAP_API, low, fte).total() < Tco.monthly(SMALL_FT, low, fte).total(),
                "на 1e6 запросов API должно быть дешевле");
        assertTrue(Tco.monthly(CHEAP_API, high, fte).total() > Tco.monthly(SMALL_FT, high, fte).total(),
                "на 5e7 запросов self-host должен быть дешевле");
        assertEquals(2, Tco.monthly(SMALL_FT, 1000, fte).units(), "минимум реплик не соблюдён");
        assertEquals(9500 / 0.9e6, Tco.monthly(CHEAP_API, low, fte).perSuccess(), EPS);
    }

    @Test
    void monthlyTcoExample() { // аналог ExampleMonthlyTCO в Go
        List<String> out = Stream.of(1e6, 5e7).map(n -> {
            var a = Tco.monthly(CHEAP_API, n, 15000);
            var s = Tco.monthly(SMALL_FT, n, 15000);
            return String.format(Locale.ROOT, "%.0e запросов/мес: API $%.0f, self-host $%.0f (реплик: %d)",
                    n, a.total(), s.total(), s.units());
        }).toList();
        assertEquals(List.of(
                "1e+06 запросов/мес: API $9500, self-host $23700 (реплик: 2)",
                "5e+07 запросов/мес: API $254500, self-host $38400 (реплик: 9)"), out);
    }

    @Test
    void thresholdMatchesWorkbook() {
        Slo slo = new Slo(0.999, Duration.ofDays(30));
        double[] want = {14.4, 6, 1};
        for (int i = 0; i < want.length; i++) {
            Alert a = Budget.DEFAULT_ALERTS.get(i);
            assertEquals(want[i], slo.threshold(a), EPS, a.name());
        }
    }

    static final Slo SLO28 = new Slo(0.99, Duration.ofDays(28));

    /** Доля плохих по окнам: ключ Duration.ZERO — фон; окно SLO целиком — period. */
    static Metrics ratio(Map<Duration, Double> r, double period) {
        return d -> {
            double bad = d.equals(SLO28.period()) ? period : r.getOrDefault(d, r.get(Duration.ZERO));
            return new Window(10000, 10000 * bad);
        };
    }

    static Stream<Arguments> checkCases() {
        Duration h = Duration.ofHours(1), m5 = Duration.ofMinutes(5), bg = Duration.ZERO;
        return Stream.of(
                Arguments.of("фон 0.5%", ratio(Map.of(bg, 0.005), 0.005), null, false),
                Arguments.of("инцидент идёт", ratio(Map.of(bg, 0.005, h, 0.2, m5, 0.2), 0.006), "page-fast", false),
                Arguments.of("инцидент закончился: длинное окно горячее, короткое нет",
                        ratio(Map.of(bg, 0.005, h, 0.2), 0.006), null, false),
                Arguments.of("бюджет окна исчерпан", ratio(Map.of(bg, 0.005), 0.012), null, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("checkCases")
    void check(String name, Metrics m, String alert, boolean freeze) {
        Status st = SLO28.check(m, Budget.DEFAULT_ALERTS);
        assertEquals(alert, st.fired() == null ? null : st.fired().name());
        assertEquals(freeze, st.freeze());
    }

    @Test
    void checkExample() { // аналог ExampleSLO_Check в Go
        // Последний час 20% ответов плохие (ошибки + оценка онлайн-судьи), до этого — фон.
        Map<Duration, Window> data = Map.of(
                Duration.ofMinutes(5), new Window(170, 34),
                Duration.ofMinutes(30), new Window(1_000, 200),
                Duration.ofHours(1), new Window(2_000, 400),
                Duration.ofHours(6), new Window(12_000, 450),
                Duration.ofHours(72), new Window(144_000, 1_090),
                SLO28.period(), new Window(1_344_000, 7_120));
        Status st = SLO28.check(data::get, Budget.DEFAULT_ALERTS);
        assertEquals("осталось бюджета 47%, алерт page-fast, заморозка false",
                String.format(Locale.ROOT, "осталось бюджета %.0f%%, алерт %s, заморозка %b",
                        st.budgetLeft() * 100, st.fired().name(), st.freeze()));
    }

    @Test
    void fromSample() {
        // 20 технических ошибок, судья оценил 200 ответов и 4 из них счёл плохими (2%).
        assertEquals(20 + 9980 * 0.02, Window.fromSample(10000, 20, 200, 4).bad(), EPS);
    }

    @Test
    void adrTemplate() throws IOException {
        // Шаблоны общие с Go-примером: examples/m13-senior/templates.
        String tmpl = Files.readString(Path.of("../../m13-senior/templates/adr.md"));
        assertEquals(List.of(), Adr.missingSections(tmpl), "шаблон неполон");
        assertFalse(Adr.reviewBy(tmpl).isPresent(), "в шаблоне нет даты, reviewBy должен вернуть empty");
        String draft = "# ADR-0001\n## Статус\n## Контекст\n## Решение\n## Альтернативы\n## Последствия\n"
                + "## Eval-доказательства\n## Стоимость на запрос\n## Данные и комплаенс\n## Режимы отказа\n";
        assertEquals(List.of("План отката", "Пересмотр"), Adr.missingSections(draft));
        assertEquals(Optional.of(LocalDate.of(2027, 3, 1)), Adr.reviewBy(
                draft + "## Пересмотр\nДата: 2027-03-01, досрочно при выводе модели.\n## Ссылки\n2030-01-01\n"));
    }
}
