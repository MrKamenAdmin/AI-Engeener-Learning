package course.m08evals;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Проверка траекторий агента: итог в среде плюс ограничения на путь, и агрегат pass^k. */
public final class Trajectories {
    private Trajectories() {}

    /** Один вызов инструмента (поля как в Step из модуля 6). */
    public record Step(String tool, String input, boolean isError) {}

    /** Запись одного прогона (trial) агента на задаче. state — снимок среды после прогона. */
    public record Trajectory(String taskId, List<Step> steps, Map<String, String> state, double costUsd) {}

    /** {first, then}: первый вызов first раньше первого вызова then. В JSON — пара ["a", "b"]. */
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    public record Order(String first, String then) {}

    /**
     * Правильное поведение на задаче. Эталонная траектория не нужна: проверяем итог и ограничения
     * на путь. wantState — главный критерий; required — должны быть вызваны; allowed — полезны,
     * но не обязательны; forbidden — ни одного вызова.
     */
    public record Spec(Map<String, String> wantState, List<String> required, List<String> allowed,
                       List<String> forbidden, List<Order> before, int maxSteps, double maxCostUsd) {
        public Spec {
            wantState = wantState == null ? Map.of() : wantState;
            required = required == null ? List.of() : required;
            allowed = allowed == null ? List.of() : allowed;
            forbidden = forbidden == null ? List.of() : forbidden;
            before = before == null ? List.of() : before;
        }
    }

    /**
     * Результат проверки одного прогона. success — итог верный и ни одно жёсткое ограничение
     * не нарушено; failures — «вид: детали», сырьё для error analysis; precision — доля вызовов
     * из required ∪ allowed; recall — доля required, которые были вызваны; redundant — повтор
     * успешного вызова с теми же аргументами.
     */
    public record Verdict(boolean success, List<String> failures, double precision, double recall, int redundant) {}

    public static Verdict check(Spec s, Trajectory t) {
        List<String> failures = new ArrayList<>();
        new TreeMap<>(s.wantState()).forEach((k, want) -> {
            String got = t.state().getOrDefault(k, "");
            if (!got.equals(want)) {
                failures.add("state: %s = \"%s\", want \"%s\"".formatted(k, got, want));
            }
        });
        Map<String, Integer> first = new HashMap<>(); // индекс первого вызова инструмента
        Set<String> done = new HashSet<>(); // успешные вызовы tool+input
        int useful = 0, redundant = 0;
        for (int i = 0; i < t.steps().size(); i++) {
            Step st = t.steps().get(i);
            first.putIfAbsent(st.tool(), i);
            String key = st.tool() + "\0" + st.input();
            if (done.contains(key)) {
                redundant++; // повтор после ошибки — это восстановление, а не лишний шаг
            }
            if (!st.isError()) {
                done.add(key);
            }
            if (s.required().contains(st.tool()) || s.allowed().contains(st.tool())) {
                useful++;
            }
            if (s.forbidden().contains(st.tool())) {
                failures.add("forbidden: %s at step %d".formatted(st.tool(), i + 1));
            }
        }
        int called = 0;
        for (String r : s.required()) {
            if (first.containsKey(r)) {
                called++;
            } else {
                failures.add("required: " + r + " not called");
            }
        }
        for (Order o : s.before()) {
            Integer b = first.get(o.then());
            if (b == null) {
                continue;
            }
            Integer a = first.get(o.first());
            if (a == null || a > b) {
                failures.add("order: " + o.first() + " must precede " + o.then());
            }
        }
        if (s.maxSteps() > 0 && t.steps().size() > s.maxSteps()) {
            failures.add("steps: %d > limit %d".formatted(t.steps().size(), s.maxSteps()));
        }
        if (s.maxCostUsd() > 0 && t.costUsd() > s.maxCostUsd()) {
            failures.add(String.format(java.util.Locale.ROOT, "cost: $%.3f > limit $%.3f", t.costUsd(), s.maxCostUsd()));
        }
        return new Verdict(failures.isEmpty(), List.copyOf(failures),
                ratio(useful, t.steps().size()), ratio(called, s.required().size()), redundant);
    }

    /**
     * Агрегат по набору задач, на каждой не меньше k прогонов. passHatK — pass^k: все k попыток
     * задачи успешны; средние — по всем прогонам; failureKinds — state/forbidden/order/... → число прогонов.
     */
    public record Summary(int tasks, int runs, double successRate, double passHatK, double meanSteps,
                          double meanCostUsd, double meanPrecision, double meanRecall, int redundant,
                          Map<String, Integer> failureKinds) {}

    public static Summary aggregate(Map<String, Spec> specs, List<Trajectory> runs, int k) {
        Map<String, List<Boolean>> byTask = new TreeMap<>();
        Map<String, Integer> kinds = new TreeMap<>();
        double steps = 0, cost = 0, precision = 0, recall = 0;
        int redundant = 0;
        for (Trajectory t : runs) {
            Spec spec = specs.get(t.taskId());
            if (spec == null) {
                throw new IllegalArgumentException("no spec for task \"" + t.taskId() + "\"");
            }
            Verdict v = check(spec, t);
            byTask.computeIfAbsent(t.taskId(), x -> new ArrayList<>()).add(v.success());
            steps += t.steps().size();
            cost += t.costUsd();
            precision += v.precision();
            recall += v.recall();
            redundant += v.redundant();
            Set<String> seen = new TreeSet<>();
            for (String f : v.failures()) {
                seen.add(f.substring(0, f.indexOf(':')));
            }
            seen.forEach(kind -> kinds.merge(kind, 1, Integer::sum));
        }
        int n = runs.size();
        if (n == 0) {
            return new Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, kinds);
        }
        int success = 0;
        double passHatK = 0;
        for (var e : byTask.entrySet()) {
            List<Boolean> res = e.getValue();
            if (res.size() < k) {
                throw new IllegalArgumentException("task \"%s\": %d runs < k=%d".formatted(e.getKey(), res.size(), k));
            }
            int c = (int) res.stream().filter(ok -> ok).count();
            success += c;
            passHatK += passHatK(res.size(), c, k);
        }
        return new Summary(byTask.size(), n, (double) success / n, passHatK / byTask.size(),
                steps / n, cost / n, precision / n, recall / n, redundant, kinds);
    }

    /** Несмещённая оценка pass^k по n прогонам с c успехами: C(c,k)/C(n,k). */
    static double passHatK(int n, int c, int k) {
        double p = 1;
        for (int i = 0; i < k; i++) {
            p *= (double) (c - i) / (n - i);
        }
        return Math.max(0, p);
    }

    static double ratio(int a, int b) {
        return b == 0 ? 1 : (double) a / b;
    }
}
