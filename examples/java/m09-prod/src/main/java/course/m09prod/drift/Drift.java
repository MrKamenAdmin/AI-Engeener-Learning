package course.m09prod.drift;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class Drift {
    private Drift() {}

    // PSI — population stability index между эталонным распределением (например, прошлый месяц)
    // и текущим (последние сутки) по категориям: темы, языки, бакеты длины.
    // Эмпирика из кредитного скоринга: < 0.1 — стабильно, 0.1–0.25 — заметный сдвиг, > 0.25 — сильный.
    public static double psi(Map<String, Double> ref, Map<String, Double> cur) {
        final double eps = 1e-4; // пустая корзина дала бы ln(0) = −∞
        Shares s = shares(ref, cur);
        double psi = 0;
        for (String k : s.p().keySet()) {
            double a = Math.max(s.p().get(k), eps), b = Math.max(s.q().get(k), eps);
            psi += (b - a) * Math.log(b / a);
        }
        return psi;
    }

    /** JS — дивергенция Йенсена–Шеннона по основанию 2: симметрична, ограничена [0, 1], пустые корзины не ломают формулу. */
    public static double js(Map<String, Double> ref, Map<String, Double> cur) {
        Shares s = shares(ref, cur);
        double js = 0;
        for (String k : s.p().keySet()) {
            double p = s.p().get(k), q = s.q().get(k), m = (p + q) / 2;
            js += kl(p, m) / 2 + kl(q, m) / 2;
        }
        return js;
    }

    private static double kl(double a, double m) {
        return a == 0 ? 0 : a * Math.log(a / m) / Math.log(2);
    }

    private record Shares(Map<String, Double> p, Map<String, Double> q) {}

    // shares переводит счётчики в доли по объединению ключей: новая тема в текущем окне —
    // тоже сигнал дрейфа, её нельзя потерять.
    private static Shares shares(Map<String, Double> a, Map<String, Double> b) {
        double sa = a.values().stream().mapToDouble(Double::doubleValue).sum();
        double sb = b.values().stream().mapToDouble(Double::doubleValue).sum();
        Set<String> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        Map<String, Double> p = new HashMap<>(), q = new HashMap<>();
        for (String k : keys) {
            p.put(k, a.getOrDefault(k, 0.0) / sa);
            q.put(k, b.getOrDefault(k, 0.0) / sb);
        }
        return new Shares(p, q);
    }

    /** Корзина длины запроса в токенах: длина — самый дешёвый признак дрейфа. */
    public static String lengthBucket(int tokens) {
        if (tokens < 50) return "<50";
        if (tokens < 200) return "50-199";
        if (tokens < 1000) return "200-999";
        return "1000+";
    }
}
