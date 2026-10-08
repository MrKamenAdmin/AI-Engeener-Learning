package course.m06agents;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Usage;
import java.util.Map;

/**
 * Model с лимитом в долларах. Это декоратор, как interceptor над HTTP-клиентом: цикл о нём не знает.
 * Вложенные Budgeted образуют иерархию: субагент тратит свой лимит и одновременно лимит родителя.
 */
public final class Budgeted implements Model {
    /** $ за 1M токенов (вход, выход), на момент написания — сентябрь 2026. */
    private static final Map<String, double[]> PRICES = Map.of(
            "claude-fable-5-1", new double[] {10, 50},
            "claude-opus-5-5", new double[] {4, 20},
            "claude-opus-5", new double[] {5, 25},
            "claude-sonnet-5", new double[] {2, 10},
            "claude-haiku-4-5", new double[] {1, 5});

    public static final class BudgetExceededException extends RuntimeException {
        BudgetExceededException(double spent, double max) {
            super("agent: budget exhausted: spent $%.4f of $%.2f".formatted(spent, max));
        }
    }

    private final Model inner;
    private final double maxUsd;
    private double spent; // guarded by this

    public Budgeted(Model inner, double maxUsd) {
        this.inner = inner;
        this.maxUsd = maxUsd;
    }

    static double[] price(String model) {
        // Неизвестная модель — по самой дорогой: бюджет не должен молча обнуляться.
        return PRICES.getOrDefault(model, PRICES.get("claude-fable-5-1"));
    }

    /** Стоимость одного вызова по usage: чтение кэша 0,1×, запись 1,25× (TTL 5 минут). */
    public static double cost(String model, Usage u) {
        double[] p = price(model);
        double in = u.inputTokens() + 0.1 * u.cacheReadInputTokens().orElse(0L)
                + 1.25 * u.cacheCreationInputTokens().orElse(0L);
        return (in * p[0] + u.outputTokens() * p[1]) / 1e6;
    }

    @Override
    public Message create(MessageCreateParams p) {
        double s = spent();
        if (s >= maxUsd) {
            throw new BudgetExceededException(s, maxUsd);
        }
        // Упрощение: стоимость известна только по usage после вызова, поэтому лимит может быть
        // превышен на один вызов (на N — при N параллельных субагентах). Жёсткий лимит —
        // оценить вызов заранее через count_tokens и зарезервировать сумму под блокировкой.
        Message resp = inner.create(p);
        synchronized (this) {
            spent += cost(p.model().asString(), resp.usage());
        }
        return resp;
    }

    public synchronized double spent() {
        return spent;
    }
}
