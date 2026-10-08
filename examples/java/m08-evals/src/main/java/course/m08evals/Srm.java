package course.m08evals;

/** SRM — проверка sample ratio mismatch для A/B-экспериментов. */
public final class Srm {
    private Srm() {}

    public record Result(double chi2, double p) {}

    /**
     * χ²-критерий согласия наблюдаемых размеров групп A/B(/n) с запланированными долями.
     * Маленькое p (обычно порог 0.001) значит, что рандомизация или логирование сломаны
     * и метрики эксперимента читать нельзя.
     */
    public static Result test(int[] observed, double[] shares) {
        long total = 0;
        for (int o : observed) total += o;
        double chi2 = 0;
        for (int i = 0; i < observed.length; i++) {
            double e = total * shares[i];
            double d = observed[i] - e;
            chi2 += d * d / e;
        }
        return new Result(chi2, chi2Survival(chi2, observed.length - 1));
    }

    /**
     * P(χ²(df) ≥ x) для целого df ≥ 1 без внешних библиотек:
     * Q(1) = erfc(√(x/2)), Q(2) = e^(−x/2), Q(k+2) = Q(k) + t(k), где t(k) = (x/2)^(k/2)·e^(−x/2)/Γ(k/2+1)
     * и t(k+2) = t(k)·(x/2)/(k/2+1) — Γ считать не нужно.
     */
    static double chi2Survival(double x, int df) {
        double q, t;
        int k;
        if (df % 2 == 1) {
            q = erfc(Math.sqrt(x / 2));
            t = Math.sqrt(x / 2) * Math.exp(-x / 2) / (Math.sqrt(Math.PI) / 2); // t(1), Γ(3/2) = √π/2
            k = 1;
        } else {
            q = Math.exp(-x / 2);
            t = x / 2 * Math.exp(-x / 2); // t(2), Γ(2) = 1
            k = 2;
        }
        for (; k < df; k += 2) {
            q += t;
            t *= x / 2 / (k / 2.0 + 1);
        }
        return q;
    }

    // ponytail: в JDK нет erfc — аппроксимация Чебышёва (Numerical Recipes, erfcc),
    // относительная ошибка < 1.2e-7; для точнее — Apache Commons Math Erf.erfc.
    static double erfc(double x) {
        double z = Math.abs(x), t = 1 / (1 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? r : 2 - r;
    }
}
