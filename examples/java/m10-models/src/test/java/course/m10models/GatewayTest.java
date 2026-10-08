package course.m10models;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GatewayTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    final List<HttpServer> servers = new ArrayList<>();
    final List<Gateway.Stats> stats = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    @FunctionalInterface
    interface Handle {
        void handle(int n, HttpExchange ex, JsonNode body) throws Exception;
    }

    HttpServer serve(com.sun.net.httpserver.HttpHandler h) throws IOException {
        var srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", h);
        srv.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        srv.start();
        servers.add(srv);
        return srv;
    }

    static String url(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    /** Фейковый vLLM: handle решает, что вернуть на n-й вызов. */
    String fakeVLLM(AtomicInteger calls, Handle handle) throws IOException {
        return url(serve(ex -> {
            try (ex) {
                if (!ex.getRequestURI().getPath().equals("/v1/chat/completions")
                        || !"Bearer k".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    reply(ex, 400, "text/plain", "bad request to upstream");
                    return;
                }
                handle.handle(calls.incrementAndGet(), ex, JSON.readTree(ex.getRequestBody()));
            } catch (Exception e) {
                throw new IOException(e);
            }
        }));
    }

    static void reply(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        ex.getResponseBody().write(b);
    }

    static Gateway.Config config(String upstream, int maxInFlight, Consumer<Gateway.Stats> observe) {
        return new Gateway.Config(upstream, "k", Map.of("clf", "ticket-clf", "chat", "Qwen/Qwen3-8B"),
                maxInFlight, Duration.ofMillis(50), Duration.ofMillis(200), Duration.ofSeconds(5), 2,
                Duration.ofMillis(1), observe);
    }

    String newGW(String upstream) throws IOException {
        return url(serve(new Gateway(config(upstream, 4, stats::add))));
    }

    static HttpResponse<String> post(String gw, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(gw + "/v1/chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** observe вызывается после записи ответа: ждём его, а не полагаемся на порядок. */
    Gateway.Stats awaitStats() throws InterruptedException {
        for (int i = 0; i < 200 && stats.isEmpty(); i++) {
            Thread.sleep(5);
        }
        assertEquals(1, stats.size(), "stats = " + stats);
        return stats.getFirst();
    }

    @Test
    void nonStreamMapsModelToAdapter() throws Exception {
        String up = fakeVLLM(new AtomicInteger(), (n, ex, body) -> {
            assertEquals("ticket-clf", body.path("model").asText(), "upstream model must be LoRA adapter");
            reply(ex, 200, "application/json",
                    "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"billing\"}}],\"usage\":{\"completion_tokens\":1}}");
        });
        var rec = post(newGW(up), "{\"model\":\"clf\",\"messages\":[{\"role\":\"user\",\"content\":\"списали дважды\"}]}");
        assertEquals(200, rec.statusCode(), rec.body());
        assertTrue(rec.body().contains("billing"));
        var s = awaitStats();
        assertEquals(1, s.outTokens, s.toString());
        assertEquals(1, s.attempts, s.toString());
    }

    @Test
    void unknownModel() throws Exception {
        var rec = post(newGW("http://127.0.0.1:1"), "{\"model\":\"gpt-9\",\"messages\":[]}");
        assertEquals(404, rec.statusCode());
    }

    @Test
    void streamPassthroughAndMetrics() throws Exception {
        String up = fakeVLLM(new AtomicInteger(), (n, ex, body) -> {
            assertTrue(body.path("stream_options").path("include_usage").asBoolean(),
                    "gateway must request usage in stream");
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            var out = ex.getResponseBody();
            Consumer<String> send = s -> {
                try {
                    out.write(("data: " + s + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
            send.accept("{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}");
            Thread.sleep(30); // prefill
            for (String tok : List.of("При", "вет", "!")) {
                send.accept("{\"choices\":[{\"delta\":{\"content\":\"" + tok + "\"}}]}");
                Thread.sleep(10); // decode
            }
            send.accept("{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}");
            send.accept("[DONE]");
        });
        var rec = post(newGW(up), "{\"model\":\"chat\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"привет\"}]}");
        String out = rec.body();
        assertEquals(200, rec.statusCode());
        assertEquals("text/event-stream", rec.headers().firstValue("Content-Type").orElse(""));
        assertTrue(out.indexOf("\"При\"") < out.indexOf("\"вет\"") && out.endsWith("data: [DONE]\n\n"),
                "stream is not passed through in order:\n" + out);
        var s = awaitStats();
        assertEquals(3, s.outTokens, s.toString());
        assertTrue(s.ttft.compareTo(Duration.ofMillis(30)) >= 0, s.toString());
        assertTrue(s.tpot.compareTo(Duration.ofMillis(5)) >= 0 && s.tpot.compareTo(s.ttft) <= 0, s.toString());
    }

    @Test
    void retryOn503ThenOK() throws Exception {
        var calls = new AtomicInteger();
        String up = fakeVLLM(calls, (n, ex, body) -> {
            if (n == 1) {
                reply(ex, 503, "text/plain", "{\"error\":\"overloaded\"}");
                return;
            }
            reply(ex, 200, "application/json", "{\"choices\":[],\"usage\":{\"completion_tokens\":5}}");
        });
        var rec = post(newGW(up), "{\"model\":\"chat\",\"messages\":[]}");
        assertEquals(200, rec.statusCode());
        assertEquals(2, calls.get());
        assertEquals(2, awaitStats().attempts);
    }

    @Test
    void retriesExhaustedReturns429() throws Exception {
        var calls = new AtomicInteger();
        String up = fakeVLLM(calls, (n, ex, body) -> reply(ex, 429, "text/plain", "{\"error\":\"rate limited\"}"));
        var rec = post(newGW(up), "{\"model\":\"chat\",\"messages\":[]}");
        assertEquals(429, rec.statusCode());
        assertEquals(3, calls.get()); // 1 + maxRetries
    }

    @Test
    void noFirstByteRetries() throws Exception {
        var calls = new AtomicInteger();
        String up = fakeVLLM(calls, (n, ex, body) -> {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0); // заголовки ушли, а токенов нет: реплика зависла на prefill
            if (n == 1) {
                Thread.sleep(1000);
                return;
            }
            ex.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
                    .getBytes(StandardCharsets.UTF_8));
        });
        var rec = post(newGW(up), "{\"model\":\"chat\",\"stream\":true,\"messages\":[]}");
        assertEquals(200, rec.statusCode());
        assertEquals(2, calls.get());
        assertTrue(rec.body().contains("\"ok\""), rec.body());
    }

    @Test
    void concurrencyLimit() throws Exception {
        var release = new CountDownLatch(1);
        String up = fakeVLLM(new AtomicInteger(), (n, ex, body) -> {
            release.await();
            reply(ex, 200, "application/json", "{\"choices\":[]}");
        });
        var gw = new Gateway(config(up, 1, null)); // один слот, как --max-num-seqs 1
        String gwUrl = url(serve(gw));
        var first = HTTP.sendAsync(HttpRequest.newBuilder(URI.create(gwUrl + "/v1/chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"chat\",\"messages\":[]}")).build(),
                HttpResponse.BodyHandlers.ofString());
        while (gw.slots.availablePermits() > 0) { // ждём, пока первый запрос займёт слот
            Thread.sleep(1);
        }
        var rec = post(gwUrl, "{\"model\":\"chat\",\"messages\":[]}");
        release.countDown();
        assertEquals(429, rec.statusCode(), "second request: want 429");
        assertTrue(rec.headers().firstValue("Retry-After").isPresent(), "want Retry-After");
        assertEquals(200, first.get().statusCode(), "first request");
    }
}
