package course.m11data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import course.m11data.MdSplit.Chunk;
import course.m11data.Pii.Redactor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class DataTest {
    static Stream<Arguments> findCases() {
        return Stream.of(
                Arguments.of("карта 4111 1111 1111 1111, звоните +7 (912) 345-67-89", List.of("CARD:4111 1111 1111 1111", "PHONE:+7 (912) 345-67-89")),
                Arguments.of("карта 4111 1111 1111 1112", List.of()), // Луна не сходится — не карта
                Arguments.of("ИНН 7707083893, ИНН ИП 500100732259", List.of("INN:7707083893", "INN:500100732259")),
                Arguments.of("номер заказа 7707083894", List.of()), // 10 цифр, но контрольная цифра не та
                Arguments.of("СНИЛС 112-233-445 95", List.of("SNILS:112-233-445 95")),
                Arguments.of("СНИЛС 112-233-445 96", List.of()),
                Arguments.of("паспорт 45 12 345678, почта Ivan.Petrov+ai@mail.example.ru", List.of("PASSPORT:45 12 345678", "EMAIL:Ivan.Petrov+ai@mail.example.ru")),
                Arguments.of("тел. 89123456789 и 8 912 345 67 89", List.of("PHONE:89123456789", "PHONE:8 912 345 67 89")));
    }

    @ParameterizedTest
    @MethodSource("findCases")
    void find(String text, List<String> want) {
        assertEquals(want, Pii.find(text).stream().map(f -> f.kind() + ":" + f.value()).toList(), text);
    }

    @Test
    void redactModes() {
        String in = "Иван, карта 4111111111111111, тел +79123456789; повторно: 8 912 345-67-89";
        var masked = new Redactor(null).redact(in);
        assertEquals("Иван, карта [CARD], тел [PHONE]; повторно: [PHONE]", masked.text());
        assertTrue(masked.vault().isEmpty());

        var ps = new Redactor("test-key".getBytes(StandardCharsets.UTF_8)).redact(in);
        assertFalse(ps.text().contains("4111") || ps.text().contains("912"), "PII осталась: " + ps.text());
        // Один номер в двух записях → один токен (детерминированность).
        List<String> phones = Arrays.stream(ps.text().replace(";", " ").replace(",", " ").split("\\s+"))
                .filter(w -> w.startsWith("[PHONE:")).toList();
        assertEquals(2, phones.size(), phones.toString());
        assertEquals(phones.get(0), phones.get(1));
        // Другой ключ — другие токены: псевдонимы не сопоставить без ключа.
        var other = new Redactor("other".getBytes(StandardCharsets.UTF_8)).redact(in);
        assertNotEquals(ps.text(), other.text(), "токены не зависят от ключа");
        assertTrue(Pii.restore(ps.text(), ps.vault()).contains("4111111111111111"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111111", "5555555555554444", "2200000000000004"})
    void luhn(String d) {
        assertTrue(Pii.luhn(d), d);
    }

    @Test
    void snilsBelowThresholdIsInvalid() {
        assertFalse(Pii.snils("00100199800"), "СНИЛС ≤ 001-001-998 не проверяется и не должен считаться валидным");
    }

    @Test
    void dedup() {
        String base = "Возврат средств за годовую подписку оформляется в личном кабинете в разделе Платежи. "
                + "Деньги возвращаются на ту же карту в течение десяти рабочих дней после одобрения заявки. "
                + "Если оплата была подарочным сертификатом, возврат возможен только на баланс аккаунта. "
                + "Частичный возврат за неиспользованные месяцы рассчитывается пропорционально и без комиссии. "
                + "По вопросам возврата пишите в поддержку через форму обратной связи в приложении.";
        Dedup d = new Dedup(3);
        assertNull(d.add("v1", base), "первый документ не может быть дублем");
        assertEquals(new Dedup.Match("v1", 0), d.add("copy", "  " + base.toUpperCase(Locale.ROOT) + "!!"));
        String near = base.replace("десяти рабочих", "десяти календарных");
        Dedup.Match m = d.add("v2", near);
        assertNotNull(m, () -> "почти-дубль не найден, расстояние до v1 = "
                + Dedup.hamming(Dedup.simHash(Dedup.normalize(base)), Dedup.simHash(Dedup.normalize(near))));
        assertEquals("v1", m.dupOf());
        String other = "Для подключения API создайте ключ в консоли, ограничьте его права и храните в секрет-менеджере, а не в коде.";
        assertNull(d.add("api", other), "ложный дубль");
    }

    @Test
    void splitMarkdown() {
        String md = """
                Вступление без заголовка.

                # Тарифы
                Общий абзац.

                ## Возвраты
                | План | Срок |
                |------|------|
                | Год  | 14 дней |

                ```go
                // # это не заголовок

                func main() {}
                ```

                ### Сроки
                - пункт один
                - пункт два
                # Контакты
                Почта поддержки.""";
        assertEquals(List.of(
                new Chunk("", "Вступление без заголовка."),
                new Chunk("Тарифы", "Общий абзац."),
                new Chunk("Тарифы › Возвраты", "| План | Срок |\n|------|------|\n| Год  | 14 дней |"),
                new Chunk("Тарифы › Возвраты", "```go\n// # это не заголовок\n\nfunc main() {}\n```"),
                new Chunk("Тарифы › Возвраты › Сроки", "- пункт один\n- пункт два"),
                new Chunk("Контакты", "Почта поддержки.")), MdSplit.splitMarkdown(md, 40));
    }

    /** Пайплайн ingest в миниатюре: структура → PII → дедупликация (аналог Example в Go). */
    @Test
    void pipeline() {
        String md = "# Возвраты\nПишите на help@shop.example или звоните 8 800 555-35-35.\n\n"
                + "# Возвраты (копия)\nПишите на help@shop.example или звоните 8 800 555-35-35.";
        Redactor red = new Redactor("rotate-me".getBytes(StandardCharsets.UTF_8));
        Dedup seen = new Dedup(3);
        List<String> out = new ArrayList<>();
        List<Chunk> chunks = MdSplit.splitMarkdown(md, 800);
        for (int i = 0; i < chunks.size(); i++) {
            String text = red.redact(chunks.get(i).text()).text();
            Dedup.Match dup = seen.add(String.valueOf(i), text);
            out.add(dup != null
                    ? i + ": дубль чанка " + dup.dupOf() + ", пропускаем"
                    : i + ": [" + chunks.get(i).heading() + "] " + text);
        }
        assertEquals(List.of(
                "0: [Возвраты] Пишите на [EMAIL:3326cad8] или звоните [PHONE:7fa340c5].",
                "1: дубль чанка 0, пропускаем"), out);
    }
}
