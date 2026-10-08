package course.m08evals;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class SrmTest {
    @ParameterizedTest
    @CsvSource({"3.841, 1", "5.991, 2", "7.815, 3", "9.488, 4"})
    void chi2SurvivalCriticalValues(double x, int df) {
        assertEquals(0.05, Srm.chi2Survival(x, df), 0.0005, "df=" + df);
    }

    @Test
    void srm() {
        double[] half = {0.5, 0.5};
        assertTrue(Srm.test(new int[] {10000, 10300}, half).p() >= 0.001, "10000/10300 is plausible noise");
        assertTrue(Srm.test(new int[] {10000, 10600}, half).p() < 0.001, "10000/10600 must be SRM");
    }

    @Test
    void example() {
        var r = Srm.test(new int[] {10000, 10600}, new double[] {0.5, 0.5});
        assertEquals("chi2=17.5 p=3e-05", String.format(Locale.ROOT, "chi2=%.1f p=%.0e", r.chi2(), r.p()));
    }
}
