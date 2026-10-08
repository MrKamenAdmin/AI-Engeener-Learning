// Проверенные примеры кода модуля 8 «Evals»: bootstrap-интервалы, калибровка judge
// (κ, Rogan–Gladen), SRM-проверка A/B, проверка траекторий агента и гейт «PR против baseline» для CI.
package course.m08evals;

import java.util.Arrays;
import java.util.SplittableRandom;

public final class Stats {
    private Stats() {}

    /** Точечная оценка и 95%-й интервал. */
    public record Interval(double est, double lo, double hi) {}

    /**
     * Перцентильный bootstrap-интервал среднего по кейсам. scores[i] — результат кейса i:
     * 0/1 или доля успешных повторов. Во втором случае это кластерный bootstrap:
     * повторы одного кейса ресемплируются вместе.
     */
    public static Interval bootstrap(double[] scores, int b, long seed) {
        int n = scores.length;
        if (n == 0 || b <= 0) {
            return new Interval(Double.NaN, Double.NaN, Double.NaN);
        }
        var r = new SplittableRandom(seed);
        double[] stats = new double[b];
        for (int k = 0; k < b; k++) {
            double s = 0;
            for (int i = 0; i < n; i++) {
                s += scores[r.nextInt(n)];
            }
            stats[k] = s / n;
        }
        Arrays.sort(stats);
        return new Interval(mean(scores), quantile(stats, 0.025), quantile(stats, 0.975));
    }

    /**
     * Интервал разности cand − base на одних и тех же кейсах. Ресемплируются кейсы целиком
     * (обе версии вместе), поэтому разброс сложности кейсов вычитается и интервал уже,
     * чем у двух независимых bootstrap.
     */
    public static Interval pairedBootstrap(double[] base, double[] cand, int b, long seed) {
        if (base.length != cand.length) {
            throw new IllegalArgumentException("base and cand must be aligned by case");
        }
        double[] d = new double[base.length];
        for (int i = 0; i < d.length; i++) {
            d[i] = cand[i] - base[i];
        }
        return bootstrap(d, b, seed);
    }

    /** stats должен быть отсортирован. */
    static double quantile(double[] stats, double p) {
        return stats[(int) (p * (stats.length - 1))];
    }

    static double mean(double[] x) {
        return Arrays.stream(x).sum() / x.length;
    }
}
