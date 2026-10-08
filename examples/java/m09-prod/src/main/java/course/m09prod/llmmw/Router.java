package course.m09prod.llmmw;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

/**
 * Отправляет простое на дешёвую цепочку, сложное — на сильную. cheap и strong — обычные LLM:
 * как правило, каждая — свой Fallback со своими моделями. threshold приходит из фича-флага:
 * score ≥ threshold → strong. accept — проверка ответа дешёвой модели; false → эскалация (null — без проверки).
 */
public record Router(LLM cheap, LLM strong, Classifier classify, double threshold,
                     Predicate<Response> accept) implements LLM {

    /** Оценивает сложность запроса в [0, 1]. */
    @FunctionalInterface
    public interface Classifier {
        double score(Request req) throws Exception;
    }

    private static final List<String> HARD = List.of("почему", "сравни", "проанализируй", "спроектируй", "оптимизируй", "```");

    /**
     * Бесплатный классификатор на признаках текста. Веса и слова подбираются
     * на размеченной выборке из логов, а не на глаз.
     */
    // ponytail: словарь признаков; когда его станет мало — modelClassifier или свой маленький классификатор.
    public static double heuristic(Request req) {
        String q = req.lastUserText().toLowerCase(Locale.ROOT);
        double score = 0;
        int n = q.codePointCount(0, q.length());
        if (n > 1500) {
            score += 0.4;
        } else if (n > 400) {
            score += 0.2;
        }
        for (String w : HARD) {
            if (q.contains(w)) {
                score += 0.3;
            }
        }
        if (q.chars().filter(c -> c == '?').count() > 1) {
            score += 0.15; // несколько вопросов в одном
        }
        if (req.messages().size() > 6) {
            score += 0.15; // длинный диалог: контекст запутаннее
        }
        return Math.min(score, 1);
    }

    /**
     * Спрашивает дешёвую модель «simple или complex». Это +200–400 мс и доли цента,
     * поэтому зовите её только в «серой зоне» эвристики, а не на каждый запрос.
     */
    public static Classifier modelClassifier(LLM cheap) {
        return req -> {
            Response resp = cheap.complete(new Request(req.tenant(), "claude-haiku-4-5",
                    "Оцени, нужна ли для ответа сильная модель. Ответь одним словом: simple или complex.",
                    List.of(Msg.user(req.lastUserText())), 5, 0, req.deadline()));
            return resp.text().toLowerCase(Locale.ROOT).contains("complex") ? 1 : 0;
        };
    }

    /** Счётчики решений роутера; в проде — Micrometer Counter или та же метрика в OTel. */
    private static final Map<String, LongAdder> ROUTED = new ConcurrentHashMap<>();

    public static long routed(String decision) {
        LongAdder a = ROUTED.get(decision);
        return a == null ? 0 : a.sum();
    }

    private static void count(String decision) {
        ROUTED.computeIfAbsent(decision, k -> new LongAdder()).increment();
    }

    @Override
    public Response complete(Request req) throws Exception {
        double score;
        try {
            score = classify.score(req);
        } catch (Exception e) {
            count("classifier_error");
            score = 1; // не знаем — платим за качество, а не экономим вслепую
        }
        if (score >= threshold) {
            count("strong");
            return strong.complete(req);
        }
        count("cheap");
        // Доступность — забота Fallback внутри цепочки; роутер отвечает за качество.
        Response resp = cheap.complete(req);
        if (accept != null && !accept.test(resp)) {
            // Откат на сильную модель: платим за оба вызова. Доля эскалаций — главная метрика роутера:
            // если она растёт, порог пора поднимать (или дешёвая модель деградировала).
            count("escalated");
            return strong.complete(req);
        }
        return resp;
    }
}
