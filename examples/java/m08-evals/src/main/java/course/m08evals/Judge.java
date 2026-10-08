package course.m08evals;

import course.m08evals.Stats.Interval;
import java.util.Arrays;
import java.util.SplittableRandom;

/** Калибровка LLM-judge по человеческой разметке: TPR/TNR, κ Коэна, поправка Rogan–Gladen. */
public final class Judge {
    private Judge() {}

    /** Сверка вердиктов judge с человеческой разметкой. Положительный класс — PASS. */
    public record Confusion(int tp, int fn, int fp, int tn) {
        public static Confusion of(boolean[] human, boolean[] judge) {
            int tp = 0, fn = 0, fp = 0, tn = 0;
            for (int i = 0; i < human.length; i++) {
                boolean h = human[i], j = judge[i];
                if (h && j) tp++;
                else if (h) fn++; // хороший ответ, judge забраковал: ложная тревога
                else if (j) fp++; // плохой ответ, judge пропустил: дефект уйдёт в прод
                else tn++;
            }
            return new Confusion(tp, fn, fp, tn);
        }

        /** Доля хороших (по людям) ответов, которые judge признал PASS. */
        public double tpr() {
            return (double) tp / (tp + fn);
        }

        /** Доля плохих ответов, которые judge поймал. Для CI-гейта это главная цифра. */
        public double tnr() {
            return (double) tn / (tn + fp);
        }

        /** Cohen's κ = (po − pe) / (1 − pe): согласие сверх случайного. */
        public double kappa() {
            double n = tp + fn + fp + tn;
            double po = (tp + tn) / n;
            double humanPass = (tp + fn) / n, judgePass = (tp + fp) / n;
            double pe = humanPass * judgePass + (1 - humanPass) * (1 - judgePass);
            if (pe == 1) {
                return Double.NaN; // оба ставят одну метку всем кейсам: κ не определена
            }
            return (po - pe) / (1 - pe);
        }
    }

    public static final class UselessJudgeException extends Exception {
        UselessJudgeException() {
            super("judge is no better than chance (TPR+TNR <= 1)");
        }
    }

    /**
     * Поправка Rogan–Gladen (1978): истинная доля PASS по доле PASS, которую показал judge,
     * и его TPR/TNR на размеченном тестовом наборе.
     * observed = TPR·θ + (1−TNR)·(1−θ)  ⇒  θ = (observed + TNR − 1) / (TPR + TNR − 1).
     */
    public static double correctedPassRate(double observed, double tpr, double tnr) throws UselessJudgeException {
        double den = tpr + tnr - 1;
        if (!(den > 0)) { // ловит и NaN, когда в выборке нет одного из классов
            throw new UselessJudgeException();
        }
        return Math.clamp((observed + tnr - 1) / den, 0.0, 1.0);
    }

    /**
     * Интервал для скорректированной доли. Ресемплируются и вердикты judge на прогоне,
     * и калибровочные пары: TPR/TNR, измеренные на 100–200 кейсах, сами шумят,
     * и этот шум часто больше шума прогона.
     */
    public static Interval correctedPassRateCi(boolean[] verdicts, boolean[] human, boolean[] judge, int b, long seed)
            throws UselessJudgeException {
        var c = Confusion.of(human, judge);
        double est = correctedPassRate(passRate(verdicts), c.tpr(), c.tnr());
        var r = new SplittableRandom(seed);
        boolean[] v = new boolean[verdicts.length], h = new boolean[human.length], j = new boolean[human.length];
        double[] stats = new double[b];
        int n = 0;
        for (int it = 0; it < b; it++) {
            for (int i = 0; i < v.length; i++) {
                v[i] = verdicts[r.nextInt(verdicts.length)];
            }
            for (int i = 0; i < h.length; i++) {
                int k = r.nextInt(human.length);
                h[i] = human[k];
                j[i] = judge[k];
            }
            var cb = Confusion.of(h, j);
            try {
                stats[n] = correctedPassRate(passRate(v), cb.tpr(), cb.tnr());
                n++;
            } catch (UselessJudgeException e) {
                // ресемпл без одного из классов: пропускаем
            }
        }
        if (n == 0) {
            throw new UselessJudgeException();
        }
        stats = Arrays.copyOf(stats, n);
        Arrays.sort(stats);
        return new Interval(est, Stats.quantile(stats, 0.025), Stats.quantile(stats, 0.975));
    }

    static double passRate(boolean[] v) {
        int k = 0;
        for (boolean ok : v) {
            if (ok) k++;
        }
        return (double) k / v.length;
    }
}
