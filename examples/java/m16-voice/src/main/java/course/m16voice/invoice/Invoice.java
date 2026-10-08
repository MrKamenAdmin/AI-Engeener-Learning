package course.m16voice.invoice;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Проверка полей, которые VLM извлекла из скана счёта.
 * Выход модели — недоверенный ввод: суммы сверяются арифметикой, а не на глаз.
 *
 * <p>Ответ модели по JSON-схеме. Суммы — строкой «как напечатано»: модель только переписывает,
 * а разбор числа детерминирован и тестируется. date — YYYY-MM-DD.
 */
public record Invoice(String number, String date, List<Line> lines, String total) {

    public record Line(String name, String amount) {}

    /** Все найденные расхождения; пустой список — счёт сходится. */
    public List<String> validate() {
        var errs = new ArrayList<String>();
        if (number == null || number.isBlank()) {
            errs.add("нет номера");
        }
        try {
            LocalDate.parse(date);
        } catch (DateTimeParseException | NullPointerException e) {
            errs.add("дата \"" + date + "\": " + e.getMessage());
        }
        long sum = 0;
        for (int i = 0; i < lines.size(); i++) {
            try {
                sum += kopecks(lines.get(i).amount());
            } catch (IllegalArgumentException e) {
                errs.add("строка " + (i + 1) + ": " + e.getMessage());
            }
        }
        try {
            long t = kopecks(total);
            if (errs.isEmpty() && sum != t) {
                // Классика VLM: перепутанные 3/8, потерянный разряд, строка прочитана дважды.
                errs.add("сумма строк " + sum + " ≠ итог " + t + " (коп.)");
            }
        } catch (IllegalArgumentException e) {
            errs.add("итог: " + e.getMessage());
        }
        return errs;
    }

    /** Разбирает «12 345,67», «12345.67», «1 200» в копейки без float. */
    public static long kopecks(String s) {
        String clean = s.strip().replace(" ", "").replace(" ", "").replace(" ", "").replace(',', '.');
        int dot = clean.indexOf('.');
        String rub = dot < 0 ? clean : clean.substring(0, dot);
        String kop = dot < 0 ? "" : clean.substring(dot + 1);
        if (kop.length() == 1) kop += "0";
        if (rub.isEmpty() || kop.length() > 2 || kop.startsWith("-")) {
            throw new IllegalArgumentException("сумма \"" + s + "\"");
        }
        try {
            long r = Long.parseLong(rub);
            long k = Long.parseLong("0" + kop);
            return rub.startsWith("-") ? r * 100 - k : r * 100 + k; // и «-0,50» тоже
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("сумма \"" + s + "\": " + e.getMessage(), e);
        }
    }
}
