package course.m09prod.drift;

import static course.m09prod.drift.Drift.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DriftTest {
    @Test
    void psiTest() {
        var ref = Map.of("billing", 500.0, "delivery", 300.0, "account", 200.0);
        assertTrue(psi(ref, Map.of("billing", 50.0, "delivery", 30.0, "account", 20.0)) <= 1e-12,
                "то же распределение в другом масштабе — PSI 0");
        // 50/50 → 90/10: 0.4·ln(1.8) + 0.4·ln(5) ≈ 0.879 — сильный сдвиг.
        assertEquals(0.879, psi(Map.of("ru", 50.0, "en", 50.0), Map.of("ru", 90.0, "en", 10.0)), 1e-3);
        // Новая тема, которой не было в эталоне (например, после запуска фичи), — тоже дрейф.
        var cur = Map.of("billing", 400.0, "delivery", 250.0, "account", 150.0, "refund_v2", 200.0);
        assertTrue(psi(ref, cur) >= 0.25, "новая тема на 20% трафика должна давать PSI > 0.25");
    }

    @Test
    void jsTest() {
        assertEquals(1, js(Map.of("a", 1.0), Map.of("b", 1.0)), 1e-12, "непересекающиеся распределения — JS 1");
        var a = Map.of("a", 3.0, "b", 1.0);
        var b = Map.of("a", 1.0, "b", 3.0);
        assertEquals(js(a, b), js(b, a), "JS симметрична");
    }

    @ParameterizedTest
    @CsvSource({"10,<50", "50,50-199", "999,200-999", "5000,1000+"})
    void lengthBucketTest(int tokens, String want) {
        assertEquals(want, lengthBucket(tokens));
    }
}
