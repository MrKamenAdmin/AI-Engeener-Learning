package course.m08evals;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import course.m08evals.Stats.Interval;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Гейт «PR против baseline» для CI: парный bootstrap, noise floor, критичные срезы. */
public final class Gate {
    private Gate() {}

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Строка results.jsonl (формат мини-фреймворка модуля; лишние поля вроде output игнорируются).
     * Повторы одного кейса — несколько строк с тем же id. status — "ok" или "error"
     * (инфраструктура, не качество).
     */
    public record CaseResult(String id, String tag, String status, boolean passed) {}

    public static List<CaseResult> readResults(Reader r) throws IOException {
        try (var it = JSON.readerFor(CaseResult.class).<CaseResult>readValues(r)) {
            return it.readAll();
        }
    }

    /**
     * minPassRate — абсолютный порог на PR, например 0.85; noiseFloor — разброс pass rate между
     * повторами baseline, например 0.03; criticalTags — срезы, где допустимо только 100%;
     * maxErrorRate — выше прогон невалиден, например 0.1; resamples — например 10000.
     */
    public record Config(double minPassRate, double noiseFloor, List<String> criticalTags,
                         double maxErrorRate, int resamples, long seed) {}

    /** n — общих кейсов в срезе; base, cur — pass rate на общих кейсах. */
    public record SliceDiff(String tag, int n, double base, double cur, Interval diff) {}

    /** problems: непустой список = гейт красный. overall == null, если общих кейсов нет. */
    public record Report(Interval cur, SliceDiff overall, List<SliceDiff> slices, List<String> regressed,
                         List<String> fixed, List<String> problems, List<String> warnings) {
        public boolean passed() {
            return problems.isEmpty();
        }

        /** Отчёт для комментария в PR. */
        public String markdown() {
            var sb = new StringBuilder();
            sb.append("## Evals: ").append(passed() ? "✅ гейт пройден" : "❌ гейт не пройден").append("\n\n");
            sb.append(f("PR: **%.1f%%** (95%% CI %.1f–%.1f)\n\n", 100 * cur.est(), 100 * cur.lo(), 100 * cur.hi()));
            problems.forEach(p -> sb.append("- ❌ ").append(p).append('\n'));
            warnings.forEach(w -> sb.append("- ⚠️ ").append(w).append('\n'));
            if (overall != null) {
                sb.append("\n| срез | n | main | PR | Δ, п.п. | 95% CI |\n|---|---|---|---|---|---|\n");
                List<SliceDiff> rows = new ArrayList<>(List.of(overall));
                rows.addAll(slices);
                for (SliceDiff s : rows) {
                    sb.append(f("| %s | %d | %.1f%% | %.1f%% | %+.1f | [%+.1f; %+.1f] |\n", s.tag(), s.n(),
                            100 * s.base(), 100 * s.cur(), 100 * s.diff().est(), 100 * s.diff().lo(), 100 * s.diff().hi()));
                }
            }
            sb.append(f("\nСтало хуже (%d): %s\n\nСтало лучше (%d): %s\n",
                    regressed.size(), list(regressed), fixed.size(), list(fixed)));
            return sb.toString();
        }
    }

    /** score — доля успешных повторов среди повторов без инфраструктурных ошибок. */
    record CaseScore(String tag, double score) {}

    record Scores(Map<String, CaseScore> byId, double errorRate) {}

    /** Сворачивает повторы в оценку кейса и считает долю инфраструктурных ошибок. */
    static Scores scores(List<CaseResult> rows) {
        record Acc(String tag, int[] passRuns) {}
        Map<String, Acc> m = new HashMap<>();
        int errs = 0;
        for (CaseResult r : rows) {
            Acc a = m.computeIfAbsent(r.id(), id -> new Acc(r.tag(), new int[2]));
            if (!"ok".equals(r.status())) {
                errs++;
                continue;
            }
            a.passRuns()[1]++;
            if (r.passed()) a.passRuns()[0]++;
        }
        Map<String, CaseScore> out = new TreeMap<>(); // TreeMap: кейсы по id, детерминированный bootstrap
        m.forEach((id, a) -> {
            if (a.passRuns()[1] > 0) {
                out.put(id, new CaseScore(a.tag(), (double) a.passRuns()[0] / a.passRuns()[1]));
            }
        });
        return new Scores(out, rows.isEmpty() ? 0 : (double) errs / rows.size());
    }

    public static Report compare(List<CaseResult> base, List<CaseResult> cur, Config cfg) {
        List<String> problems = new ArrayList<>(), warnings = new ArrayList<>();
        List<String> regressed = new ArrayList<>(), fixed = new ArrayList<>();
        Scores b = scores(base == null ? List.of() : base), c = scores(cur);
        if (c.errorRate() > cfg.maxErrorRate() || b.errorRate() > cfg.maxErrorRate()) {
            problems.add(f("прогон невалиден: инфраструктурных ошибок %.0f%% (PR), %.0f%% (baseline)",
                    100 * c.errorRate(), 100 * b.errorRate()));
        }

        double[] all = c.byId().values().stream().mapToDouble(CaseScore::score).toArray();
        Interval curIv = Stats.bootstrap(all, cfg.resamples(), cfg.seed());
        if (!(curIv.est() >= cfg.minPassRate())) {
            problems.add(f("pass rate %.1f%% ниже порога %.0f%%", 100 * curIv.est(), 100 * cfg.minPassRate()));
        }

        // Пары по id: сравниваем только кейсы, которые есть в обоих прогонах.
        Map<String, List<double[]>> byTag = new TreeMap<>();
        List<double[]> pairs = new ArrayList<>();
        for (var e : c.byId().entrySet()) {
            String id = e.getKey();
            CaseScore cc = e.getValue();
            if (cfg.criticalTags().contains(cc.tag()) && cc.score() < 1) {
                problems.add(f("критичный кейс %s [%s]: %.0f%% повторов прошло", id, cc.tag(), 100 * cc.score()));
            }
            CaseScore bb = b.byId().get(id);
            if (bb == null) {
                continue;
            }
            double[] pair = {bb.score(), cc.score()};
            pairs.add(pair);
            byTag.computeIfAbsent(cc.tag(), t -> new ArrayList<>()).add(pair);
            if (cc.score() < bb.score()) regressed.add(id);
            else if (cc.score() > bb.score()) fixed.add(id);
        }
        int missing = c.byId().size() - pairs.size();
        if (missing > 0) {
            warnings.add(missing + " кейсов нет в baseline: в сравнение не вошли");
        }
        if (pairs.isEmpty()) {
            warnings.add("нет общих кейсов с baseline: проверены только абсолютные пороги");
            return new Report(curIv, null, List.of(), regressed, fixed, problems, warnings);
        }

        SliceDiff overall = diff("всё", pairs, cfg);
        Interval d = overall.diff();
        if (d.hi() < 0) {
            problems.add(f("значимое падение: Δ %+.1f п.п., 95%% CI [%+.1f; %+.1f]", 100 * d.est(), 100 * d.lo(), 100 * d.hi()));
        } else if (d.est() < -cfg.noiseFloor()) {
            problems.add(f("падение %.1f п.п. больше noise floor %.1f п.п.", -100 * d.est(), 100 * cfg.noiseFloor()));
        }
        // Срезы — предупреждения, а не красный гейт: при 10 срезах один «значимо
        // упадёт» случайно (множественные сравнения). Жёсткие правила — у критичных.
        List<SliceDiff> slices = new ArrayList<>();
        byTag.forEach((tag, ps) -> {
            SliceDiff s = diff(tag, ps, cfg);
            slices.add(s);
            if (s.diff().hi() < 0 || s.diff().est() < -cfg.noiseFloor()) {
                warnings.add(f("срез %s: %.0f%% → %.0f%% (n=%d)", tag, 100 * s.base(), 100 * s.cur(), s.n()));
            }
        });
        return new Report(curIv, overall, slices, regressed, fixed, problems, warnings);
    }

    private static SliceDiff diff(String tag, List<double[]> pairs, Config cfg) {
        double[] x = pairs.stream().mapToDouble(p -> p[0]).toArray();
        double[] y = pairs.stream().mapToDouble(p -> p[1]).toArray();
        return new SliceDiff(tag, x.length, Stats.mean(x), Stats.mean(y),
                Stats.pairedBootstrap(x, y, cfg.resamples(), cfg.seed()));
    }

    private static String list(List<String> ids) {
        if (ids.isEmpty()) {
            return "—";
        }
        final int limit = 20;
        if (ids.size() > limit) {
            return "`" + String.join("`, `", ids.subList(0, limit)) + "`" + " и ещё " + (ids.size() - limit);
        }
        return "`" + String.join("`, `", ids) + "`";
    }

    private static String f(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }
}
