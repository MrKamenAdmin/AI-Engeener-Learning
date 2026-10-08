package course.m15capstone;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;

/**
 * Гейт приёмки капстоуна (модуль 15): читает JSON-отчёты шагов CI и печатает вердикт
 * по каждому критерию. Exit-код: 0 — принято, 1 — нет, 2 — ошибка входа.
 *
 * <pre>
 * mvn -q -pl m15-capstone compile exec:java -Dexec.mainClass=course.m15capstone.AcceptanceMain -Dexec.args="-reports m15-capstone/reports"
 * mvn -q -pl m15-capstone exec:java -Dexec.mainClass=course.m15capstone.AcceptanceMain -Dexec.args="-reports m15-capstone/reports -thresholds my-thresholds.json"
 * </pre>
 */
public final class AcceptanceMain {
    public static void main(String[] args) {
        String dir = "reports", cfg = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "-reports" -> dir = args[i + 1];
                case "-thresholds" -> cfg = args[i + 1];
                default -> {
                    System.err.println("неизвестный флаг " + args[i] + "; есть -reports DIR и -thresholds FILE");
                    System.exit(2);
                }
            }
        }

        Acceptance.Thresholds t = Acceptance.DEFAULTS;
        Acceptance.Reports r;
        try {
            if (cfg != null) {
                // Поля, которых нет в файле, берутся по умолчанию.
                ObjectNode merged = Acceptance.JSON.valueToTree(t);
                merged.setAll((ObjectNode) Acceptance.JSON.readTree(Path.of(cfg).toFile()));
                t = Acceptance.JSON.treeToValue(merged, Acceptance.Thresholds.class);
            }
            r = Acceptance.loadReports(Path.of(dir));
        } catch (Exception e) {
            System.err.println("вход: " + e.getMessage());
            System.exit(2);
            return;
        }

        List<Acceptance.Result> results = Acceptance.check(r, t);
        long failed = 0;
        for (var res : results) {
            if (!res.ok()) failed++;
            System.out.printf("%s %-8s %s%n", res.ok() ? "✓" : "✗", res.id(), res.detail());
        }
        if (failed > 0) {
            System.out.printf("%nНЕ ПРИНЯТО: не выполнено %d из %d автоматических критериев%n", failed, results.size());
            System.exit(1);
        }
        System.out.println("\nПРИНЯТО: автоматические критерии выполнены; ADR, SLO и откат проверяются на защите");
    }
}
