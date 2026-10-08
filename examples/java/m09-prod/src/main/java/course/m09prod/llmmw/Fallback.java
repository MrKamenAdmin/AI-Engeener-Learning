package course.m09prod.llmmw;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Цепочка звеньев: первое здоровое и подходящее отвечает. */
public record Fallback(List<Route> routes) implements LLM {

    /**
     * closed → (threshold ошибок подряд) → open → (cooldown) → half-open (1 проба).
     * В проде можно взять Resilience4j CircuitBreaker; логика та же.
     */
    public static final class Breaker {
        private final int threshold;
        private final Duration cooldown;
        private int fails;
        private Instant openUntil = Instant.MIN;
        private boolean probing;

        public Breaker(int threshold, Duration cooldown) {
            this.threshold = threshold;
            this.cooldown = cooldown;
        }

        public synchronized boolean allow() {
            if (fails < threshold) {
                return true; // closed
            }
            if (Instant.now().isBefore(openUntil) || probing) {
                return false; // open, или проба уже в полёте
            }
            probing = true; // half-open: пропускаем ровно один запрос
            return true;
        }

        public synchronized void report(boolean failed) {
            probing = false;
            if (!failed) {
                fails = 0;
                return;
            }
            if (++fails >= threshold) {
                openUntil = Instant.now().plus(cooldown);
            }
        }
    }

    /**
     * llm уже обёрнут в Retry и Limiter; fits — влезет ли контекст, есть ли нужные фичи;
     * adapt — свой промпт и параметры под модель.
     */
    public record Route(String name, String model, LLM llm, Breaker breaker,
                        Predicate<Request> fits, UnaryOperator<Request> adapt) {
        public Route(String name, String model, LLM llm, Breaker breaker) {
            this(name, model, llm, breaker, r -> true, UnaryOperator.identity());
        }
    }

    @Override
    public Response complete(Request req) throws Exception {
        List<Exception> errs = new ArrayList<>();
        for (Route rt : routes) {
            if (!rt.fits().test(req)) {
                continue;
            }
            if (!rt.breaker().allow()) {
                errs.add(new Exception(rt.name() + ": circuit open"));
                continue;
            }
            Request r = rt.adapt().apply(req.withModel(rt.model()));
            try {
                Response resp = rt.llm().complete(r);
                rt.breaker().report(false);
                return resp.withRoute(rt.name());
            } catch (Exception e) {
                boolean retryable = Retry.retryable(e).retry();
                rt.breaker().report(retryable && !Retry.isRateLimit(e)); // 429 — наша квота, не авария
                if (!retryable) {
                    throw e; // 400 на A будет 400 и на B: не маскируем баг перебором
                }
                errs.add(new Exception(rt.name() + ": " + e.getMessage(), e));
                if (req.expired()) {
                    break;
                }
            }
        }
        // cause — последняя ошибка: по ней retryable и error.type видят HTTP-статус.
        var all = new Exception("fallback: all routes failed", errs.isEmpty() ? null : errs.getLast());
        errs.forEach(all::addSuppressed);
        throw all;
    }
}
