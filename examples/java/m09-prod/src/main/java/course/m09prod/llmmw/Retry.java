package course.m09prod.llmmw;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/** Ретраи с full jitter, уважением retry-after, retry budget и проверкой дедлайна. */
public record Retry(LLM next, int maxRetries, Duration base, Duration cap, Budget budget) implements LLM {

    /** retry — повтор может закончиться иначе; retryAfter — подсказка сервера. */
    public record Verdict(boolean retry, Duration retryAfter) {}

    record Status(int code, Duration retryAfter) {}

    public static Verdict retryable(Throwable e) {
        Status s = httpStatus(e);
        if (s != null) {
            int c = s.code();
            boolean ok = c == 408 || c == 409 || c == 429 || c >= 500; // 529 overloaded входит сюда
            return new Verdict(ok, ok ? s.retryAfter() : Duration.ZERO); // 400/401/403/404/413: повтор даст то же самое
        }
        boolean network = e instanceof IOException || e instanceof AnthropicIoException
                || e instanceof TimeoutException; // сеть или таймаут попытки
        return new Verdict(network, Duration.ZERO);
    }

    /** HTTP-статус из ошибки любого провайдера: Anthropic SDK или своего адаптера. null — не HTTP-ошибка. */
    static Status httpStatus(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof AnthropicServiceException a) {
                var ra = a.headers().values("retry-after");
                return new Status(a.statusCode(), retryAfter(ra.isEmpty() ? null : ra.getFirst()));
            }
            if (t instanceof OpenAiCompat.HttpError h) {
                return new Status(h.statusCode(), h.retryAfter());
            }
        }
        return null;
    }

    static boolean isRateLimit(Throwable e) {
        Status s = httpStatus(e);
        return s != null && s.code() == 429;
    }

    static Duration retryAfter(String header) {
        try {
            return header == null ? Duration.ZERO : Duration.ofSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return Duration.ZERO; // HTTP-date и мусор не разбираем
        }
    }

    /** Ретраев не больше ratio от числа запросов (как retry budget в Finagle/gRPC). */
    public static final class Budget {
        private final double ratio, max;
        private double tokens;

        public Budget(double ratio, double max) {
            this.ratio = ratio;
            this.max = max;
        }

        synchronized void onRequest() {
            tokens = Math.min(max, tokens + ratio);
        }

        synchronized boolean tryRetry() {
            if (tokens < 1) {
                return false;
            }
            tokens--;
            return true;
        }
    }

    @Override
    public Response complete(Request req) throws Exception {
        budget.onRequest();
        for (int attempt = 0; ; attempt++) {
            try {
                return next.complete(req);
            } catch (Exception e) {
                if (req.expired()) {
                    throw e; // дедлайн запроса истёк: не ретраим
                }
                Verdict v = retryable(e);
                if (!v.retry() || attempt == maxRetries || !budget.tryRetry()) {
                    throw e;
                }
                long ceil = Math.min(cap.toNanos(), base.toNanos() << attempt);
                Duration jitter = Duration.ofNanos(ThreadLocalRandom.current().nextLong(ceil + 1));
                Duration d = jitter.compareTo(v.retryAfter()) < 0 ? v.retryAfter() : jitter; // full jitter, но не раньше retry-after
                if (req.timeLeft().map(left -> left.compareTo(d) < 0).orElse(false)) {
                    throw e; // не успеем: пусть решает fallback выше
                }
                Thread.sleep(d); // interrupt потока = отмена запроса
            }
        }
    }
}
