package course.m08evals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * evalgate сравнивает results.jsonl из PR с baseline из main и пишет Markdown-отчёт.
 * Код выхода 1 — гейт красный. Нет файла baseline — проверяются только абсолютные пороги.
 *
 * <pre>
 * mvn -q -pl m08-evals exec:java -Dexec.mainClass=course.m08evals.EvalGate \
 *     -Dexec.args="-base baseline/results.jsonl -cur out/results.jsonl -out out/report.md"
 * </pre>
 */
public final class EvalGate {
    public static void main(String[] args) throws IOException {
        Map<String, String> flags = new HashMap<>(Map.of(
                "base", "baseline/results.jsonl", // результаты main
                "cur", "out/results.jsonl", // результаты PR
                "out", "out/report.md", // куда записать отчёт
                "min-pass-rate", "0.85", // абсолютный порог
                "noise-floor", "0.03", // разброс повторов baseline
                "max-error-rate", "0.1", // доля инфраструктурных ошибок
                "critical", "safety,adversarial")); // срезы с порогом 100%
        for (int i = 0; i + 1 < args.length; i += 2) {
            String k = args[i].replaceFirst("^--?", "");
            if (!flags.containsKey(k)) {
                System.err.println("unknown flag " + args[i]);
                System.exit(2);
            }
            flags.put(k, args[i + 1]);
        }
        var cfg = new Gate.Config(Double.parseDouble(flags.get("min-pass-rate")), Double.parseDouble(flags.get("noise-floor")),
                List.of(flags.get("critical").split(",")), Double.parseDouble(flags.get("max-error-rate")), 10_000, 1);

        List<Gate.CaseResult> cur = read(Path.of(flags.get("cur")));
        List<Gate.CaseResult> base = null;
        try {
            base = read(Path.of(flags.get("base")));
        } catch (NoSuchFileException e) {
            System.err.println("baseline " + flags.get("base") + " not found: absolute thresholds only");
        }

        Gate.Report rep = Gate.compare(base, cur, cfg);
        String md = rep.markdown();
        Files.writeString(Path.of(flags.get("out")), md);
        System.out.print(md);
        if (!rep.passed()) {
            System.exit(1);
        }
    }

    private static List<Gate.CaseResult> read(Path path) throws IOException {
        try (var r = Files.newBufferedReader(path)) {
            return Gate.readResults(r);
        }
    }
}
