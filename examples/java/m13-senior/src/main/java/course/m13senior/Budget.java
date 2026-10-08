package course.m13senior;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/** Error budget, burn rate и multiwindow-алерты. */
public final class Budget {
    private Budget() {}

    /** События за интервал: всего и «плохих». */
    public record Window(double total, double bad) {
        /**
         * Window для SLI качества. Технические ошибки видны на всём трафике, а качество — только
         * на выборке, которую оценил онлайн-судья (модуль 8): долю плохих в выборке переносим на остальные ответы.
         */
        public static Window fromSample(double total, double errors, double judged, double judgedBad) {
            double bad = errors;
            if (judged > 0) bad += (total - errors) * judgedBad / judged;
            return new Window(total, bad);
        }
    }

    /**
     * Правило multiwindow multi-burn-rate из Google SRE Workbook («Alerting on SLOs»):
     * за окно long сожжена доля budget бюджета, и сжигание всё ещё идёт на коротком окне shortW.
     *
     * @param budget доля бюджета всего окна SLO, например 0.02
     * @param page   true — будить дежурного, false — тикет в рабочее время
     */
    public record Alert(String name, Duration longW, Duration shortW, double budget, boolean page) {}

    /** Рекомендованные пороги SRE Workbook (таблица 5-8), от срочного к медленному. */
    public static final List<Alert> DEFAULT_ALERTS = List.of(
            new Alert("page-fast", Duration.ofHours(1), Duration.ofMinutes(5), 0.02, true),
            new Alert("page-slow", Duration.ofHours(6), Duration.ofMinutes(30), 0.05, true),
            new Alert("ticket", Duration.ofHours(72), Duration.ofHours(6), 0.10, false));

    /** События за последние d — обёртка над вашим Prometheus или ClickHouse. */
    public interface Metrics extends Function<Duration, Window> {}

    /**
     * Что говорит политика error budget прямо сейчас.
     *
     * @param budgetLeft доля оставшегося бюджета окна, бывает &lt; 0
     * @param fired      первый сработавший алерт, null — тихо
     * @param freeze     бюджет исчерпан: релизы промптов и моделей заморожены, кроме фиксов
     */
    public record Status(double budgetLeft, Alert fired, boolean freeze) {}

    /** Цель по доле хороших событий за скользящее окно, например 0.99 за 28 суток. */
    public record Slo(double target, Duration period) {
        /** Во сколько раз быстрее нормы сжигается бюджет; 1 — ровно к концу окна. */
        public double burnRate(Window w) {
            return w.total() == 0 ? 0 : w.bad() / w.total() / (1 - target);
        }

        /** Доля бюджета → порог burn rate: 2% за 1 ч при окне 30 суток → 14.4. */
        public double threshold(Alert a) {
            return a.budget() * period.toNanos() / a.longW().toNanos();
        }

        /** Применяет алерты и политику заморозки к текущим метрикам. */
        public Status check(Metrics m, List<Alert> alerts) {
            double left = 1;
            Window w = m.apply(period);
            if (w.total() > 0) left = 1 - w.bad() / ((1 - target) * w.total());
            for (Alert a : alerts) {
                double th = threshold(a);
                if (burnRate(m.apply(a.longW())) >= th && burnRate(m.apply(a.shortW())) >= th) {
                    return new Status(left, a, left <= 0);
                }
            }
            return new Status(left, null, left <= 0);
        }
    }
}
