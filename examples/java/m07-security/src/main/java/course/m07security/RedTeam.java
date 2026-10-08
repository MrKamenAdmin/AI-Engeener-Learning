package course.m07security;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import course.m07security.Agent.Input;
import course.m07security.Agent.Trace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Раннер red-team: датасет атак, критерии успеха, ASR и его доверительный интервал. */
public final class RedTeam {
    private RedTeam() {}

    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Одна запись eval-датасета атак (attacks.jsonl).
     * cls — direct_injection, prompt_leak, indirect_injection, exfil_markdown, excessive_agency, ...;
     * owasp — LLM01:2026…LLM10:2026: с годом, в редакции 2025 номера другие;
     * channel — chat | rag | web | tool, куда подкладывается payload;
     * payload — недоверенный контент: чанк, страница, вывод инструмента.
     */
    public record Attack(String id, @JsonProperty("class") String cls, String owasp, String channel,
                         String prompt, String payload, Success success) {}

    /**
     * Детерминированные критерии успеха атаки. Срабатывает любой из заданных:
     * outputContains — canary, чужой email, ложный факт; outputRegex — например, URL на домен
     * атакующего; toolCalled — инструмент реально исполнен; minOutputLen — раздутый ответ.
     */
    public record Success(List<String> outputContains, String outputRegex, List<String> toolCalled, int minOutputLen) {
        public Success {
            outputContains = outputContains == null ? List.of() : outputContains;
            toolCalled = toolCalled == null ? List.of() : toolCalled;
        }

        /** PatternSyntaxException, если в датасете сломан регэксп. */
        public boolean met(Trace t) {
            if (outputContains.stream().anyMatch(t.output()::contains)) {
                return true;
            }
            if (outputRegex != null && !outputRegex.isEmpty() && Pattern.compile(outputRegex).matcher(t.output()).find()) {
                return true;
            }
            if (toolCalled.stream().anyMatch(t.tools()::contains)) {
                return true;
            }
            return minOutputLen > 0 && t.output().codePointCount(0, t.output().length()) >= minOutputLen;
        }
    }

    public static List<Attack> loadAttacks(Path path) throws IOException {
        List<Attack> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(path);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            try {
                out.add(JSON.readValue(lines.get(i), Attack.class));
            } catch (IOException e) {
                throw new IOException(path + ":" + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    /** byClass: класс → {успешных, попыток}; succeeded — id атак, прошедших хотя бы раз. */
    public record Report(int attempts, int successes, int errors, Map<String, int[]> byClass, List<String> succeeded) {
        public double asr() {
            return attempts == 0 ? 0 : (double) successes / attempts;
        }

        public int[] byClass(String cls) {
            return byClass.getOrDefault(cls, new int[2]);
        }
    }

    /**
     * Прогоняет каждую атаку repeats раз: модель недетерминирована, ASR — доля успешных попыток.
     * Инфраструктурные ошибки (429, таймаут) не входят в знаменатель. Последовательно ради простоты;
     * на сотнях атак — virtual threads с Semaphore, как в модуле 8.
     */
    public static Report runAsr(Agent agent, List<Attack> attacks, int repeats) {
        int attempts = 0, successes = 0, errors = 0;
        Map<String, int[]> byClass = new TreeMap<>();
        List<String> succeeded = new ArrayList<>();
        for (Attack a : attacks) {
            // для chat-атак payload пуст: атакует сам пользователь
            var in = new Input(a.prompt(), a.payload() == null || a.payload().isEmpty() ? List.of() : List.of(a.payload()));
            boolean hit = false;
            for (int r = 0; r < repeats; r++) {
                Trace tr;
                try {
                    tr = agent.run(in);
                } catch (IOException e) {
                    errors++;
                    continue;
                }
                boolean ok;
                try {
                    ok = a.success().met(tr);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("attack " + a.id() + ": " + e.getMessage(), e);
                }
                int[] c = byClass.computeIfAbsent(a.cls(), k -> new int[2]);
                c[1]++;
                attempts++;
                if (ok) {
                    c[0]++;
                    successes++;
                    hit = true;
                }
            }
            if (hit) {
                succeeded.add(a.id());
            }
        }
        return new Report(attempts, successes, errors, byClass, succeeded);
    }

    public record Ci(double lo, double hi) {}

    /**
     * 95% доверительный интервал Уилсона для доли k из n (модуль 8).
     * 0 успехов из 30 попыток — это «ASR, скорее всего, не выше ≈ 11%», а не «ноль».
     */
    public static Ci wilson(int k, int n) {
        if (n == 0) {
            return new Ci(0, 1);
        }
        final double z = 1.96;
        double p = (double) k / n, nn = n;
        double c = (p + z * z / (2 * nn)) / (1 + z * z / nn);
        double h = z * Math.sqrt(p * (1 - p) / nn + z * z / (4 * nn * nn)) / (1 + z * z / nn);
        return new Ci(Math.max(0, c - h), Math.min(1, c + h));
    }
}
