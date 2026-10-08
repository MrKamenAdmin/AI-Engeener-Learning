package course.m07security;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Реализации {@link Guard}: от дешёвых детерминированных к внешним классификаторам. */
public final class Guards {
    private Guards() {}

    /**
     * Дешёвый детектор известных шаблонов инъекций. Ловит копипасту,
     * но не адаптивного атакующего: перефразировка, другой язык, base64 проходят.
     */
    public record Heuristic() implements Guard {
        private static final Pattern INJECTION = Pattern.compile(
                "(ignore|disregard)\\s+(all\\s+|any\\s+)?(previous|prior|above)\\s+instructions"
                        + "|игнорируй\\s+(все\\s+)?(предыдущие\\s+|прошлые\\s+|системные\\s+)?(инструкции|правила)"
                        + "|system\\s+prompt|системн\\p{L}*\\s+промпт|developer\\s+mode|ты\\s+теперь",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

        @Override
        public String name() {
            return "heuristic";
        }

        @Override
        public String check(String text) throws Blocked {
            Matcher m = INJECTION.matcher(text);
            if (m.find()) {
                throw new Blocked("heuristic", "pattern " + m.group());
            }
            return text;
        }
    }

    /** Внешний детектор: Llama Prompt Guard, Prompt Shields, LLM-judge на Haiku. */
    @FunctionalInterface
    public interface Classifier {
        double score(String text) throws IOException;
    }

    /** timeout — свой дедлайн: guard не должен съесть весь бюджет латентности. */
    public record ClassifierGuard(Classifier c, double threshold, Duration timeout) implements Guard {
        private static final ExecutorService VT = Executors.newVirtualThreadPerTaskExecutor();

        @Override
        public String name() {
            return "classifier";
        }

        @Override
        public String check(String text) throws Blocked, IOException {
            Future<Double> f = VT.submit(() -> c.score(text));
            double s;
            try {
                s = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                throw new IOException("classifier timeout " + timeout, e);
            } catch (ExecutionException e) {
                throw new IOException(e.getCause()); // что делать дальше, решает политика: runChain (closed) или failOpen
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (s >= threshold) {
                throw new Blocked("classifier", "score %.2f >= %.2f".formatted(s, threshold));
            }
            return text;
        }
    }

    /**
     * Уникальный токен в system prompt. Его появление в ответе означает дословную утечку промпта.
     * Пересказ своими словами canary не ловит: секретов в system prompt быть не должно (LLM08:2026).
     */
    public record Canary(String token) implements Guard {
        @Override
        public String name() {
            return "canary";
        }

        @Override
        public String check(String text) throws Blocked {
            if (text.contains(token)) {
                throw new Blocked("canary", "system prompt leak");
            }
            return text;
        }
    }

    /** Вырезает из markdown ссылки и картинки на домены вне allowlist (см. {@link Links#strip}). */
    public record Egress(Links.URLPolicy policy) implements Guard {
        @Override
        public String name() {
            return "egress";
        }

        @Override
        public String check(String text) {
            Links.Stripped r = Links.strip(text, policy);
            if (r.removed() > 0) { // всплеск — повод смотреть, кто подложил документ
                System.getLogger("guard").log(System.Logger.Level.WARNING, "egress: links removed count={0}", r.removed());
            }
            return r.text();
        }
    }

    /** Маскирует секреты и простые PII. Подробно о PII-редакции (NER, Presidio) — модуль 11. */
    public record Redact() implements Guard {
        private record Rule(Pattern re, String mask) {}

        private static final List<Rule> RULES = List.of(
                new Rule(Pattern.compile("sk-ant-[A-Za-z0-9_-]{10,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{36,}"), "[секрет]"),
                new Rule(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]"),
                new Rule(Pattern.compile("\\+7[\\s(-]*\\d{3}[\\s)-]*\\d{3}[\\s-]*\\d{2}[\\s-]*\\d{2}"), "[телефон]"));

        @Override
        public String name() {
            return "redact";
        }

        @Override
        public String check(String text) {
            for (Rule r : RULES) {
                text = r.re().matcher(text).replaceAll(Matcher.quoteReplacement(r.mask()));
            }
            return text;
        }
    }
}
