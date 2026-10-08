package course.m16voice.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import course.m16voice.invoice.Invoice.Line;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class InvoiceTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "12 345,67 | 1234567", "12345.67 | 1234567", "1 200 | 120000",
        "0,5 | 50", "-10,00 | -1000", "-0,50 | -50", "' 7 ' | 700",
    })
    void kopecks(String in, long want) {
        assertEquals(want, Invoice.kopecks(in));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12,345,67", "1.234", "abc", "12,-5"})
    void kopecksRejects(String in) {
        assertThrows(IllegalArgumentException.class, () -> Invoice.kopecks(in));
    }

    @Test
    void validate() {
        var ok = new Invoice("СЧ-118", "2026-09-30",
                List.of(new Line("Лицензия", "12 000,00"), new Line("Поддержка", "3 300,00")), "15 300,00");
        assertTrue(ok.validate().isEmpty(), () -> "валидный счёт: " + ok.validate());

        var bad = new Invoice(ok.number(), ok.date(),
                List.of(new Line("Лицензия", "12 000,00"), new Line("Поддержка", "8 300,00")), ok.total()); // VLM прочла 3 как 8
        assertFalse(bad.validate().isEmpty(), "расхождение суммы не поймано");

        var badDate = new Invoice(ok.number(), "30.09.2026", ok.lines(), ok.total());
        assertFalse(badDate.validate().isEmpty(), "формат даты не проверен");
    }
}
