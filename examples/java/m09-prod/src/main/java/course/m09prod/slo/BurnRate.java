package course.m09prod.slo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class BurnRate {
    private BurnRate() {}

    /** События SLI за окно: всего и «плохих». */
    public record Counts(double total, double bad) {}

    /**
     * Во сколько раз быстрее допустимого тратится error budget:
     * 1 — бюджет кончится ровно к концу окна SLO, 14.4 — за 30 дней / 14.4 ≈ 2 дня.
     */
    public static double burnRate(Counts c, double objective) {
        return c.total() == 0 ? 0 : (c.bad() / c.total()) / (1 - objective);
    }

    /** Какая доля error budget осталась за окно SLO (уходит в минус при перерасходе). */
    public static double budgetLeft(Counts c, double objective) {
        return c.total() == 0 ? 1 : 1 - c.bad() / (c.total() * (1 - objective));
    }

    /** page = false — тикет, а не звонок дежурному. */
    public record Rule(Duration longWindow, Duration shortWindow, double burn, boolean page) {}

    /**
     * Multiwindow, multi-burn-rate правила из Google SRE Workbook для SLO на 30 дней.
     * Короткое окно = 1/12 длинного: алерт гаснет, как только инцидент закончился.
     */
    public static final List<Rule> WORKBOOK = List.of(
            new Rule(Duration.ofHours(1), Duration.ofMinutes(5), 14.4, true),   // 2% бюджета за час
            new Rule(Duration.ofHours(6), Duration.ofMinutes(30), 6, true),     // 5% за 6 часов
            new Rule(Duration.ofHours(72), Duration.ofHours(6), 1, false));     // 10% за 3 дня

    /**
     * Возвращает сработавшие правила. window(d) отдаёт счётчики за последние d.
     * minEvents отсекает решения по горстке событий: на малом трафике и на выборочном SLI
     * (онлайн-judge на 2% ответов) короткое окно — это шум, а не сигнал.
     */
    public static List<Rule> firing(List<Rule> rules, double objective, double minEvents,
                                    Function<Duration, Counts> window) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) {
            Counts lng = window.apply(r.longWindow()), shrt = window.apply(r.shortWindow());
            if (shrt.total() < minEvents) {
                continue;
            }
            if (burnRate(lng, objective) >= r.burn() && burnRate(shrt, objective) >= r.burn()) {
                out.add(r);
            }
        }
        return out;
    }
}
