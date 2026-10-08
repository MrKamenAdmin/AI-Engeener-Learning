// Шлюз перед OpenAI-совместимым сервером vLLM (подойдёт и Ollama на :11434).
// Делает пять вещей: публичное имя модели → база или LoRA-адаптер в vLLM, лимит
// одновременных запросов под --max-num-seqs, ретраи на 429/5xx, пока клиенту
// ещё ничего не отправлено, таймаут на первый байт стрима и метрики TTFT/TPOT.
package course.m10models;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class Gateway implements HttpHandler {

    /**
     * upstream — http://vllm:8000; apiKey — --api-key vLLM, пусто — без авторизации;
     * models — публичное имя → имя в vLLM: база или адаптер из --lora-modules;
     * maxInFlight ≈ max-num-seqs × число реплик; queueWait — сколько запрос ждёт свободный слот, потом 429;
     * firstByte — стрим: нет первого байта за это время — отменяем попытку и повторяем;
     * timeout — общий потолок на запрос; backoff — база экспоненциального backoff с full jitter;
     * observe — куда отдать метрики: лог, Micrometer, OTel (модуль 9).
     */
    public record Config(String upstream, String apiKey, Map<String, String> models, int maxInFlight,
                         Duration queueWait, Duration firstByte, Duration timeout, int maxRetries,
                         Duration backoff, Consumer<Stats> observe) {}

    /** ttft — от прихода запроса до первого непустого токена; tpot — (последний токен − первый) / (токенов − 1). */
    public static final class Stats {
        public final String model;
        public int status, attempts, outTokens;
        public Duration ttft = Duration.ZERO, tpot = Duration.ZERO;

        Stats(String model) {
            this.model = model;
        }

        @Override
        public String toString() {
            return "Stats[model=%s, status=%d, attempts=%d, ttft=%s, tpot=%s, outTokens=%d]"
                    .formatted(model, status, attempts, ttft, tpot, outTokens);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY = 4 << 20;
    private static final ExecutorService VT = Executors.newVirtualThreadPerTaskExecutor();

    private final Config cfg;
    // Пул соединений HttpClient для HTTP/1.1 по умолчанию не ограничен, так что 128 параллельных
    // стримов не пересоздают соединения. HTTP/1.1 явно: vLLM не говорит h2c.
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    final Semaphore slots;

    public Gateway(Config cfg) {
        this.cfg = cfg;
        this.slots = new Semaphore(cfg.maxInFlight());
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        long start = System.nanoTime();
        try (ex) {
            serve(ex, start);
        }
    }

    private void serve(HttpExchange ex, long start) throws IOException {
        if (!ex.getRequestMethod().equals("POST") || !ex.getRequestURI().getPath().equals("/v1/chat/completions")) {
            apiError(ex, 404, "only POST /v1/chat/completions");
            return;
        }
        ObjectNode req; // остальные поля запроса пробрасываем как есть
        try {
            byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (raw.length > MAX_BODY) {
                throw new IOException("request body too large");
            }
            req = (ObjectNode) JSON.readTree(raw);
        } catch (IOException | ClassCastException e) {
            apiError(ex, 400, "bad JSON: " + e.getMessage());
            return;
        }
        String pub = req.path("model").asText();
        boolean stream = req.path("stream").asBoolean(false);
        String target = cfg.models().get(pub);
        if (target == null) {
            apiError(ex, 404, "model \"%s\" not found".formatted(pub));
            return;
        }
        req.put("model", target);
        if (stream && !req.has("stream_options")) {
            req.putObject("stream_options").put("include_usage", true); // точное число токенов в последнем чанке
        }
        byte[] body = JSON.writeValueAsBytes(req);

        var st = new Stats(pub);
        try {
            proxy(ex, body, stream, start, st);
        } finally {
            if (cfg.observe() != null) {
                cfg.observe().accept(st);
            }
        }
    }

    private void proxy(HttpExchange ex, byte[] body, boolean stream, long start, Stats st) throws IOException {
        long deadline = start + cfg.timeout().toNanos();
        // Слот: в vLLM не больше maxInFlight запросов, остальные ждут здесь, а не в его очереди.
        try {
            if (!slots.tryAcquire(cfg.queueWait().toNanos(), TimeUnit.NANOSECONDS)) {
                st.status = 429;
                ex.getResponseHeaders().set("Retry-After", "1");
                apiError(ex, st.status, "all slots busy");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            Upstream up;
            try {
                up = send(body, stream, deadline, st);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                st.status = System.nanoTime() >= deadline ? 504 : 502;
                apiError(ex, st.status, "upstream: " + e);
                return;
            }
            try (up) {
                st.status = up.resp().statusCode();
                if (stream && st.status == 200) {
                    pipeSSE(ex, up.body(), start, st);
                    return;
                }
                byte[] raw;
                try {
                    raw = up.body().readAllBytes();
                } catch (IOException e) {
                    st.status = 502;
                    apiError(ex, st.status, "upstream: " + e);
                    return;
                }
                try {
                    st.outTokens = JSON.readTree(raw).path("usage").path("completion_tokens").asInt(0);
                } catch (IOException ignored) {
                    // тело ошибки бывает не JSON — отдаём как есть
                }
                st.ttft = Duration.ofNanos(System.nanoTime() - start); // без стрима пользователь видит первый токен вместе с последним
                ex.getResponseHeaders().set("Content-Type",
                        up.resp().headers().firstValue("Content-Type").orElse("application/json"));
                ex.sendResponseHeaders(st.status, raw.length == 0 ? -1 : raw.length);
                ex.getResponseBody().write(raw);
            }
        } finally {
            slots.release();
        }
    }

    /** close() закрывает тело: соединение рвётся, и vLLM освобождает слот и KV-cache. */
    private record Upstream(HttpResponse<InputStream> resp, BufferedInputStream body) implements AutoCloseable {
        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
                // соединение и так закрывается
            }
        }
    }

    /**
     * Повторяет запрос, пока vLLM отвечает 429/502/503/504 или стрим молчит дольше firstByte.
     * Повторять можно только до первого байта клиенту: после него ответ уже частично отдан.
     */
    private Upstream send(byte[] body, boolean stream, long deadline, Stats st) throws Exception {
        for (int attempt = 1; ; attempt++) {
            st.attempts = attempt;
            Upstream up = null;
            Exception err = null;
            try {
                up = attempt(body, stream, deadline);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                err = e;
            }
            if (up != null && !retryable(up.resp().statusCode())) {
                return up;
            }
            if (attempt > cfg.maxRetries() || System.nanoTime() >= deadline) {
                if (up != null) {
                    return up; // последний 429/503 отдаём клиенту как есть
                }
                throw err;
            }
            long ceil = cfg.backoff().toNanos() << (attempt - 1);
            long delay = ceil > 0 ? ThreadLocalRandom.current().nextLong(ceil) : 0;
            if (up != null) {
                delay = Math.max(delay, retryAfterNanos(up.resp()));
                up.close();
            }
            Thread.sleep(Duration.ofNanos(Math.max(0, Math.min(delay, deadline - System.nanoTime()))));
        }
    }

    private Upstream attempt(byte[] body, boolean stream, long deadline) throws Exception {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw new HttpTimeoutException("gateway deadline exceeded");
        }
        var b = HttpRequest.newBuilder(URI.create(cfg.upstream() + "/v1/chat/completions"))
                .timeout(Duration.ofNanos(left))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (!cfg.apiKey().isEmpty()) {
            b.header("Authorization", "Bearer " + cfg.apiKey());
        }
        long wait = stream && cfg.firstByte().isPositive() ? Math.min(cfg.firstByte().toNanos(), left) : left;
        var sent = client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        var ready = sent.thenApplyAsync(Gateway::peekFirstByte, VT);
        try {
            return ready.get(wait, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            sent.cancel(true);                                    // заголовков ещё нет — обрываем запрос,
            sent.thenAccept(r -> new Upstream(r, new BufferedInputStream(r.body())).close()); // а если есть — закрываем тело
            throw new HttpTimeoutException("no first byte in " + Duration.ofNanos(wait));
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception c ? c : e;
        }
    }

    /** Ждёт первый байт тела: заголовки могли уйти, а токенов нет — реплика зависла на prefill. */
    private static Upstream peekFirstByte(HttpResponse<InputStream> resp) {
        var up = new Upstream(resp, new BufferedInputStream(resp.body()));
        try {
            up.body().mark(1);
            up.body().read(); // -1 (пустое тело) тоже годится
            up.body().reset();
            return up;
        } catch (IOException e) {
            up.close();
            throw new UncheckedIOException(e);
        }
    }

    private static long retryAfterNanos(HttpResponse<?> resp) {
        try {
            return resp.headers().firstValue("Retry-After")
                    .map(s -> TimeUnit.SECONDS.toNanos(Long.parseLong(s.trim()))).orElse(0L);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean retryable(int code) {
        return code == 429 || code == 502 || code == 503 || code == 504;
    }

    /** Пробрасывает стрим построчно и сбрасывает буфер после каждой строки. */
    private static void pipeSSE(HttpExchange ex, InputStream src, long start, Stats st) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream");
        h.set("Cache-Control", "no-cache");
        h.set("X-Accel-Buffering", "no"); // nginx перед шлюзом не должен копить стрим
        ex.sendResponseHeaders(200, 0);   // 0 — chunked: длина заранее неизвестна
        OutputStream out = ex.getResponseBody();
        var in = new BufferedReader(new InputStreamReader(src, StandardCharsets.UTF_8));
        long first = 0, last = 0;
        int chunks = 0;
        while (true) {
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                break; // upstream оборвал стрим
            }
            if (line == null) {
                break;
            }
            try {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                return; // клиент ушёл: Upstream.close() оборвёт запрос к vLLM, генерация остановится
            }
            if (!line.startsWith("data:")) {
                continue;
            }
            JsonNode c;
            try {
                c = JSON.readTree(line.substring(5));
            } catch (JsonProcessingException e) {
                continue; // "[DONE]" не JSON — пропускаем
            }
            // reasoning — у reasoning-моделей; в старых vLLM поле звалось reasoning_content.
            JsonNode delta = c.path("choices").path(0).path("delta");
            if (!delta.path("content").asText("").isEmpty() || !delta.path("reasoning").asText("").isEmpty()) {
                long now = System.nanoTime();
                if (first == 0) {
                    first = now;
                }
                last = now;
                chunks++;
            }
            if (c.hasNonNull("usage")) {
                st.outTokens = c.path("usage").path("completion_tokens").asInt();
            }
        }
        if (st.outTokens == 0) {
            st.outTokens = chunks; // без usage: чанк ≈ токен, если stream_interval = 1
        }
        if (first != 0) {
            st.ttft = Duration.ofNanos(first - start);
            if (st.outTokens > 1) {
                st.tpot = Duration.ofNanos((last - first) / (st.outTokens - 1));
            }
        }
    }

    static void apiError(HttpExchange ex, int code, String msg) throws IOException {
        byte[] b = JSON.writeValueAsBytes(Map.of("error", Map.of("message", msg, "code", code)));
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }
}
