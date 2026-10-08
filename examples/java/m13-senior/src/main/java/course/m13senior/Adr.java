package course.m13senior;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Проверка ADR в CI. */
public final class Adr {
    private Adr() {}

    /**
     * Разделы, без которых ADR по AI-системе не выносится на ревью.
     * Заголовок в документе может быть длиннее: «## Стоимость на запрос» закрывает «Стоимость».
     */
    public static final List<String> SECTIONS = List.of(
            "Статус", "Контекст", "Решение", "Альтернативы", "Последствия",
            "Eval-доказательства", "Стоимость", "Данные и комплаенс",
            "Режимы отказа", "План отката", "Пересмотр");

    /** Обязательные разделы, которых нет среди заголовков «## …». */
    public static List<String> missingSections(String md) {
        List<String> heads = md.lines().map(String::strip)
                .filter(l -> l.startsWith("## ")).map(l -> l.substring(3)).toList();
        return SECTIONS.stream().filter(need -> heads.stream().noneMatch(h -> h.startsWith(need))).toList();
    }

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * Дата из раздела «## Пересмотр». Ночная джоба в CI открывает задачу на каждый ADR,
     * у которого дата прошла или не указана: модели устаревают, цены меняются.
     */
    public static Optional<LocalDate> reviewBy(String md) {
        int i = md.indexOf("\n## Пересмотр");
        if (i < 0) return Optional.empty();
        String sec = md.substring(i + "\n## Пересмотр".length());
        int end = sec.indexOf("\n## ");
        if (end >= 0) sec = sec.substring(0, end);
        Matcher m = ISO_DATE.matcher(sec);
        if (!m.find()) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(m.group()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
