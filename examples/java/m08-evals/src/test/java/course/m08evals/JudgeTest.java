package course.m08evals;

import static org.junit.jupiter.api.Assertions.*;

import course.m08evals.Judge.Confusion;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class JudgeTest {
    /** Строит пары (человек, judge) по матрице ошибок: [0] — human, [1] — judge. */
    static boolean[][] labels(Confusion c) {
        int n = c.tp() + c.fn() + c.fp() + c.tn();
        boolean[] h = new boolean[n], j = new boolean[n];
        Arrays.fill(h, 0, c.tp() + c.fn(), true); // TP, FN: люди сказали PASS
        Arrays.fill(j, 0, c.tp(), true);
        Arrays.fill(j, c.tp() + c.fn(), c.tp() + c.fn() + c.fp(), true); // FP: judge пропустил плохой
        return new boolean[][] {h, j};
    }

    @Test
    void kappa() {
        boolean[][] l = labels(new Confusion(60, 10, 12, 18));
        // po = 0.78; pe = 0.7·0.72 + 0.3·0.28 = 0.588; κ = 0.192/0.412 ≈ 0.466
        assertEquals(0.466, Confusion.of(l[0], l[1]).kappa(), 0.001);
        // «Всегда PASS» при 90% хороших: agreement 90%, κ = 0.
        assertEquals(0, new Confusion(90, 0, 10, 0).kappa());
    }

    @Test
    void correctedPassRate() throws Exception {
        // Истинно 70% хороших; judge: TPR 0.9, TNR 0.8 → покажет 0.9·0.7 + 0.2·0.3 = 69%.
        assertEquals(0.7, Judge.correctedPassRate(0.69, 0.9, 0.8), 1e-9);
        assertThrows(Judge.UselessJudgeException.class, () -> Judge.correctedPassRate(0.5, 0.6, 0.4));
    }

    @Test
    void correctedPassRateCi() throws Exception {
        boolean[][] l = labels(new Confusion(63, 7, 6, 24)); // TPR 0.9, TNR 0.8
        boolean[] verdicts = new boolean[500];
        Arrays.fill(verdicts, 0, 345, true); // judge сказал PASS на 69% прогона
        var iv = Judge.correctedPassRateCi(verdicts, l[0], l[1], 5000, 1);
        assertEquals(0.7, iv.est(), 1e-9);
        assertTrue(iv.lo() < 0.7 && 0.7 < iv.hi(), iv.toString());
        // Неопределённость TPR/TNR со 100 разметок делает интервал широким: ≈ [58%; 81%],
        // тогда как у самого прогона из 500 вердиктов полуширина около 4 п.п.
        assertTrue(iv.hi() - iv.lo() >= 0.15, "interval suspiciously narrow: " + iv);
    }
}
