package course.m09prod.llmmw;

import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Проверка квоты тенанта до вызова и учёт фактической стоимости после. */
public record Metered(LLM next, Spent spent, Add add, ToDoubleFunction<String> limit) implements LLM {

    @FunctionalInterface
    public interface Spent {
        double get(String tenant) throws Exception; // напр. Redis GET
    }

    @FunctionalInterface
    public interface Add {
        void add(String tenant, double usd) throws Exception; // INCRBYFLOAT + EXPIRE
    }

    public static final class QuotaExceededException extends RuntimeException {
        public QuotaExceededException() {
            super("llm: tenant budget exceeded");
        }
    }

    record Price(double in, double out) {}

    // Цены $ за 1M токенов на момент написания (сентябрь 2026).
    static final Map<String, Price> PRICES = Map.of(
            "claude-opus-5", new Price(5, 25),
            "claude-sonnet-5", new Price(2, 10),
            "claude-haiku-4-5", new Price(1, 5));

    public static double costUsd(Response r) {
        Price p = PRICES.get(r.model());
        if (p == null) {
            return 0; // неизвестная модель: алертим отдельно, а не падаем
        }
        double in = r.inputTokens() + 0.1 * r.cacheReadTokens() + 1.25 * r.cacheWriteTokens();
        return (in * p.in() + r.outputTokens() * p.out()) / 1e6;
    }

    @Override
    public Response complete(Request req) throws Exception {
        double used;
        try {
            used = spent.get(req.tenant());
        } catch (Exception e) {
            used = 0; // при ошибке Redis — fail-open: квота мягкая, логируем
        }
        if (used >= limit.applyAsDouble(req.tenant())) {
            throw new QuotaExceededException();
        }
        Response resp = next.complete(req);
        try {
            add.add(req.tenant(), costUsd(resp));
        } catch (Exception ignored) {
            // учёт не должен ронять уже оплаченный ответ
        }
        return resp;
    }
}
