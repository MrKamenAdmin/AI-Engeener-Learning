package course.m11data;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Примеры модуля 11 «Данные и governance»: PII-редактор (Pii), near-duplicate детектор (Dedup),
 * markdown-сплиттер (MdSplit).
 */
public final class Pii {
    private Pii() {}

    /** Найденный фрагмент PII: границы (индексы char) в исходном тексте. */
    public record Finding(String kind, int start, int end, String value) {}

    /** valid == null — проверки нет, только формат. */
    private record Detector(String kind, Pattern re, Predicate<String> valid) {}

    // Порядок = приоритет при пересечениях: сначала то, что подтверждено контрольной суммой.
    private static final List<Detector> DETECTORS = List.of(
            new Detector("CARD", Pattern.compile("\\b(?:\\d{4}[ -]){3}\\d{4}\\b|\\b\\d{13,19}\\b"), Pii::luhn),
            new Detector("SNILS", Pattern.compile("\\b\\d{3}-?\\d{3}-?\\d{3}[ -]?\\d{2}\\b"), Pii::snils),
            new Detector("INN", Pattern.compile("\\b\\d{12}\\b|\\b\\d{10}\\b"), Pii::inn),
            new Detector("EMAIL", Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"), null),
            new Detector("PHONE", Pattern.compile("(?:\\+7|\\b8)[ (-]*\\d{3}[ )-]*\\d{3}[ -]?\\d{2}[ -]?\\d{2}\\b"), null),
            // Паспорт РФ: 4 цифры серии + 6 цифр номера. Контрольной суммы нет — без пробела
            // перед номером не ловим, иначе каждое 10-значное число станет «паспортом».
            new Detector("PASSPORT", Pattern.compile("\\b\\d{2} ?\\d{2} \\d{6}\\b"), null));

    /** Непересекающиеся находки, отсортированные по позиции. */
    public static List<Finding> find(String text) {
        List<Finding> all = new ArrayList<>();
        for (Detector d : DETECTORS) {
            Matcher m = d.re().matcher(text);
            while (m.find()) {
                String v = m.group();
                if (d.valid() != null && !d.valid().test(digitsOnly(v))) continue;
                all.add(new Finding(d.kind(), m.start(), m.end(), v));
            }
        }
        // all уже упорядочен по приоритету детектора; берём находку, если она не пересекается с принятыми.
        // ponytail: O(n²) по числу находок — для сообщений и документов хватает, для гигабайтных дампов нужен interval tree.
        List<Finding> out = new ArrayList<>();
        for (Finding f : all) {
            if (out.stream().noneMatch(o -> f.start() < o.end() && o.start() < f.end())) out.add(f);
        }
        out.sort(Comparator.comparingInt(Finding::start));
        return out;
    }

    /** Текст без PII и словарь токен → исходное значение. */
    public record Redacted(String text, Map<String, String> vault) {}

    /**
     * Заменяет PII. key == null — маскирование ([CARD]); иначе — псевдонимизация:
     * [CARD:3f9a1c2b] = HMAC(key, тип+нормализованное значение). Одно и то же значение даёт
     * один и тот же токен во всех документах — можно считать, джойнить и искать по нему, не видя PII.
     * Словарь (vault) нужен только для обратной подстановки: храните его отдельно,
     * с отдельными правами и сроком жизни, или не храните вовсе, если восстановление не нужно.
     */
    public record Redactor(byte[] key) {
        public Redacted redact(String text) {
            StringBuilder b = new StringBuilder();
            Map<String, String> vault = new HashMap<>();
            int last = 0;
            for (Finding f : find(text)) {
                b.append(text, last, f.start());
                String tok = "[" + f.kind() + "]";
                if (key != null) {
                    tok = "[" + f.kind() + ":" + pseudonym(f) + "]";
                    vault.put(tok, f.value());
                }
                b.append(tok);
                last = f.end();
            }
            b.append(text.substring(last));
            return new Redacted(b.toString(), vault);
        }

        private String pseudonym(Finding f) {
            String norm = f.value().toLowerCase(Locale.ROOT);
            if (!f.kind().equals("EMAIL")) {
                norm = digitsOnly(f.value());
                if (f.kind().equals("PHONE") && norm.startsWith("8")) {
                    norm = "7" + norm.substring(1); // 8 912… и +7 912… — один и тот же номер
                }
            }
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                byte[] sum = mac.doFinal((f.kind() + ":" + norm).getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(sum, 0, 4); // 32 бита: читаемо; для больших баз берите 8+ байт
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Обратная подстановка, например в ответе модели перед показом пользователю. */
    public static String restore(String text, Map<String, String> vault) {
        for (var e : vault.entrySet()) text = text.replace(e.getKey(), e.getValue());
        return text;
    }

    static String digitsOnly(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) if (c >= '0' && c <= '9') b.append(c);
        return b.toString();
    }

    /** Контрольная сумма номеров карт (ISO/IEC 7812). */
    static boolean luhn(String d) {
        int sum = 0;
        for (int i = d.length() - 1; i >= 0; i--) {
            int n = d.charAt(i) - '0';
            if ((d.length() - 1 - i) % 2 == 1) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
        }
        return sum % 10 == 0;
    }

    /** Контрольные цифры ИНН: 10 знаков (организация) или 12 (физлицо, ИП). */
    static boolean inn(String d) {
        return switch (d.length()) {
            case 10 -> innCheck(d, 2, 4, 10, 3, 5, 9, 4, 6, 8) == d.charAt(9) - '0';
            case 12 -> innCheck(d, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8) == d.charAt(10) - '0'
                    && innCheck(d, 3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8) == d.charAt(11) - '0';
            default -> false;
        };
    }

    private static int innCheck(String d, int... w) {
        int s = 0;
        for (int i = 0; i < w.length; i++) s += w[i] * (d.charAt(i) - '0');
        return s % 11 % 10;
    }

    /**
     * Контрольное число СНИЛС: веса 9…1 по первым 9 цифрам, затем mod 101 (100 и 101 → 00).
     * Номера не больше 001-001-998 не проверяются — для детектора считаем их невалидными.
     */
    static boolean snils(String d) {
        if (d.length() != 11 || d.substring(0, 9).compareTo("001001998") <= 0) return false;
        int s = 0;
        for (int i = 0; i < 9; i++) s += (d.charAt(i) - '0') * (9 - i);
        s %= 101;
        if (s == 100) s = 0;
        return s == (d.charAt(9) - '0') * 10 + (d.charAt(10) - '0');
    }
}
