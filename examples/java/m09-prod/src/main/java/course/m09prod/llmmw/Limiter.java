package course.m09prod.llmmw;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;

/** Три token bucket'а под RPM/ITPM/OTPM провайдера, выставленные на ~90% лимита. */
public record Limiter(LLM next, TokenBucket rpm, TokenBucket itpm, TokenBucket otpm) implements LLM {

    public Limiter(LLM next, double rpm, double itpm, double otpm) {
        this(next, per(rpm), per(itpm), per(otpm));
    }

    private static TokenBucket per(double perMinute) {
        return new TokenBucket(perMinute * 0.9 / 60, perMinute * 0.9 / 6);
    }

    @Override
    public Response complete(Request req) throws Exception {
        // acquire сразу бросит исключение, если дедлайн запроса не дождётся токенов: бесплатный load shedding.
        rpm.acquire(1, req.deadline());
        itpm.acquire(Math.min(req.estInputTokens(), itpm.burst()), req.deadline());
        otpm.acquire(Math.min(req.maxTokens(), otpm.burst()), req.deadline());
        return next.complete(req);
    }

    /** Token bucket с резервированием: токены можно «занять» вперёд и подождать их. */
    public static final class TokenBucket {
        private final double perSecond, burst;
        private double tokens;
        private long last = System.nanoTime();

        public TokenBucket(double perSecond, double burst) {
            this.perSecond = perSecond;
            this.burst = burst;
            this.tokens = burst;
        }

        public double burst() {
            return burst;
        }

        public void acquire(double n, Instant deadline) throws InterruptedException {
            Duration wait;
            synchronized (this) {
                long now = System.nanoTime();
                tokens = Math.min(burst, tokens + (now - last) / 1e9 * perSecond);
                last = now;
                wait = tokens >= n ? Duration.ZERO : Duration.ofNanos((long) ((n - tokens) / perSecond * 1e9));
                if (deadline != null && Instant.now().plus(wait).isAfter(deadline)) {
                    throw new RejectedExecutionException("limiter: wait would exceed deadline");
                }
                tokens -= n;
            }
            if (wait.isPositive()) {
                Thread.sleep(wait);
            }
        }
    }
}
