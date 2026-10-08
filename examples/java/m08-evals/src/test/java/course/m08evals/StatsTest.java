package course.m08evals;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class StatsTest {
    /** n кейсов, первые k прошли. */
    static double[] ones(int n, int k) {
        double[] x = new double[n];
        Arrays.fill(x, 0, k, 1);
        return x;
    }

    @Test
    void bootstrapCloseToWilson() {
        // 80/100: Уилсон даёт [71.1%; 86.7%]; bootstrap должен быть рядом.
        var iv = Stats.bootstrap(ones(100, 80), 10000, 1);
        assertEquals(0.8, iv.est());
        assertEquals(0.711, iv.lo(), 0.02);
        assertEquals(0.867, iv.hi(), 0.02);
    }

    @Test
    void bootstrapDegeneratesAtAllPass() {
        // 50/50 прошли: bootstrap даёт нулевую ширину, хотя Уилсон — [92.9%; 100%].
        // Поэтому для одной доли при малом n или p у края — Уилсон, а не bootstrap.
        var iv = Stats.bootstrap(ones(50, 50), 2000, 1);
        assertEquals(1, iv.lo());
        assertEquals(1, iv.hi());
    }

    @Test
    void pairedDetectsWhatUnpairedMisses() {
        // base 80/100, cand 85/100: cand чинит 5 кейсов и ничего не ломает.
        double[] base = ones(100, 80), cand = ones(100, 85);
        var d = Stats.pairedBootstrap(base, cand, 10000, 1);
        assertTrue(d.lo() > 0, "paired CI must exclude 0, got " + d);
        // Непарно те же числа неразличимы: интервалы двух версий пересекаются.
        var b = Stats.bootstrap(base, 10000, 2);
        var c = Stats.bootstrap(cand, 10000, 3);
        assertTrue(b.hi() >= c.lo(), "unpaired intervals should overlap: " + b + " vs " + c);
    }

    @Test
    void clusterBootstrapWithRepeats() {
        // 3 повтора на кейс: оценка кейса — доля успешных повторов.
        double[] scores = {1, 1, 2.0 / 3, 0, 1, 1.0 / 3, 1, 1, 1, 0};
        var iv = Stats.bootstrap(scores, 5000, 1);
        assertTrue(iv.lo() < iv.est() && iv.est() < iv.hi(), iv.toString());
    }
}
