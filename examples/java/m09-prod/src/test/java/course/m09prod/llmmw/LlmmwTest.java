package course.m09prod.llmmw;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import course.m09prod.llmmw.Flags.Flag;
import course.m09prod.llmmw.Flags.Registry;
import course.m09prod.llmmw.Flags.Variant;
import course.m09prod.llmmw.LLM.Msg;
import course.m09prod.llmmw.LLM.Request;
import course.m09prod.llmmw.LLM.Response;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LlmmwTest {
    static Request ask(String text) {
        return new Request("acme", null, null, List.of(Msg.user(text)), 500, 0, null);
    }

    /** Фейк отвечает заданным текстом и считает вызовы. */
    static LLM fake(String text, AtomicInteger calls) {
        return req -> {
            calls.incrementAndGet();
            return Response.of(text, null, "end_turn");
        };
    }

    static boolean validJson(Response r) {
        try {
            Flags.JSON.readTree(r.text());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            простое → дешёвая            | Какие часы работы поддержки?                           | {"a":1}  | 1 | 0 | {"a":1} | cheap
            сложное → сильная            | Сравни тарифы Pro и Team и объясни, почему счёт вырос? | {}       | 0 | 1 | strong  | strong
            невалидный ответ → эскалация | Какой у меня тариф?                                    | не JSON  | 1 | 1 | strong  | escalated
            """)
    void router(String name, String q, String cheapOut, int wantCheap, int wantStrong, String wantText, String counter)
            throws Exception {
        var nc = new AtomicInteger();
        var ns = new AtomicInteger();
        var r = new Router(fake(cheapOut, nc), fake("strong", ns), Router::heuristic, 0.3, LlmmwTest::validJson);
        long before = Router.routed(counter);
        Response resp = r.complete(ask(q));
        assertEquals(wantCheap, nc.get(), "cheap");
        assertEquals(wantStrong, ns.get(), "strong");
        assertEquals(wantText, resp.text());
        assertEquals(before + 1, Router.routed(counter), "метрика " + counter + " не выросла");
    }

    @Test
    void routerClassifierErrorGoesStrong() throws Exception {
        var nc = new AtomicInteger();
        var ns = new AtomicInteger();
        var r = new Router(fake("c", nc), fake("s", ns), req -> {
            throw new IllegalStateException("haiku down");
        }, 0.5, null);
        assertEquals("s", r.complete(ask("привет")).text(), "ошибка классификатора должна вести на сильную модель");
        assertEquals(0, nc.get());
    }

    @Test
    void flagRollout() {
        var control = new Variant("v12", "claude-opus-5");
        var treatment = new Variant("v13", "claude-sonnet-5");
        var f = new Flag("answer", false, control, treatment, 10, null);
        Set<String> in10 = new HashSet<>();
        for (int i = 0; i < 20000; i++) {
            String u = "u" + i;
            if (f.evaluate("acme", u).name().equals("v13")) {
                in10.add(u);
            }
            assertEquals(f.evaluate("acme", u), f.evaluate("acme", u), "вариант должен быть детерминирован");
        }
        double share = in10.size() / 20000.0;
        assertTrue(share >= 0.09 && share <= 0.11, "доля Treatment " + share + ", ждали ≈ 0.10");

        var f30 = new Flag("answer", false, control, treatment, 30, null);
        for (String u : in10) {
            assertEquals("v13", f30.evaluate("acme", u).name(), u + " выпал из Treatment при росте процента");
        }
        var targeted = new Flag("answer", false, control, treatment, 30, Map.of("bank", false));
        assertEquals("v12", targeted.evaluate("bank", "u1").name(), "таргетинг: тенант bank исключён");
        var killed = new Flag("answer", true, control, treatment, 30, Map.of("bank", false));
        for (String u : in10) {
            assertEquals("v12", killed.evaluate("acme", u).name(), "kill switch должен вернуть всех на Control");
        }
    }

    @Test
    void registryKeepsOldConfigOnError() throws Exception {
        var r = new Registry();
        var def = new Variant("default", "claude-opus-5");
        r.load("""
                [{"key":"answer","percent":100,"control":{"name":"v12","model":"claude-opus-5"},
                  "treatment":{"name":"v13","model":"claude-sonnet-5"}}]""".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> r.load("""
                [{"key":"answer","percent":250,"control":{"model":"x"},"treatment":{"model":"y"}}]"""
                .getBytes(StandardCharsets.UTF_8)), "percent 250 должен отклоняться");
        assertEquals("v13", r.variant("answer", "acme", "u1", def).name(), "после битого конфига должен работать старый");
        assertEquals(def, r.variant("unknown", "acme", "u1", def), "неизвестный флаг → default");
    }

    @Test
    void openAiCompat() throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/chat/completions", ex -> {
            JsonNode body = Flags.JSON.readTree(ex.getRequestBody());
            String model = body.path("model").asText();
            byte[] out = new byte[0];
            int code = 200;
            switch (model) {
                case "down" -> {
                    ex.getResponseHeaders().set("retry-after", "2");
                    code = 503;
                }
                case "bad" -> code = 400;
                default -> {
                    JsonNode msgs = body.path("messages");
                    if (msgs.size() != 2 || !msgs.get(0).path("role").asText().equals("system")
                            || !msgs.get(1).path("content").asText().equals("ping")) {
                        code = 500;
                    }
                    out = """
                            {"model":"qwen3-8b","choices":[{"message":{"role":"assistant","content":"pong"},"finish_reason":"length"}],"usage":{"prompt_tokens":12,"completion_tokens":3}}"""
                            .getBytes(StandardCharsets.UTF_8);
                }
            }
            ex.sendResponseHeaders(code, out.length == 0 ? -1 : out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        srv.start();
        try {
            var llm = new OpenAiCompat(HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + srv.getAddress().getPort(), "k");
            Request req = ask("ping").withSystem("Ты бот.").withModel("qwen3-8b");
            Response resp = llm.complete(req);
            assertEquals("pong", resp.text());
            assertEquals("max_tokens", resp.stopReason());
            assertEquals(12, resp.inputTokens());

            var down = assertThrows(Exception.class, () -> llm.complete(req.withModel("down")));
            var v = Retry.retryable(down);
            assertTrue(v.retry(), "503 второго провайдера должен ретраиться");
            assertEquals(Duration.ofSeconds(2), v.retryAfter());
            assertEquals("503", GenAi.errorType(down));

            var bad = assertThrows(Exception.class, () -> llm.complete(req.withModel("bad")));
            assertFalse(Retry.retryable(bad).retry(), "400 не ретраится");
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void agentSpansAndMetrics() throws Exception {
        var spans = InMemorySpanExporter.create();
        var metrics = InMemoryMetricReader.create();
        var sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
                .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
                .build();
        var genai = new GenAi(sdk);
        LLM llm = new Traced(req -> new Response("", req.model(), "end_turn", "", 10, 5, 0, 0), "anthropic", genai);

        genai.invokeAgent("support", () -> {
            llm.complete(ask("где заказ?").withModel("claude-opus-5"));
            return genai.executeTool("lookup_order", "toolu_1", () -> "в пути");
        });

        Map<String, SpanData> byName = spans.getFinishedSpanItems().stream()
                .collect(Collectors.toMap(SpanData::getName, Function.identity()));
        SpanData agent = byName.get("invoke_agent support");
        SpanData chat = byName.get("chat claude-opus-5");
        SpanData tool = byName.get("execute_tool lookup_order");
        assertNotNull(agent, "спаны: " + byName.keySet());
        assertNotNull(chat, "спаны: " + byName.keySet());
        assertNotNull(tool, "спаны: " + byName.keySet());
        for (SpanData child : List.of(chat, tool)) {
            assertEquals(agent.getSpanId(), child.getParentSpanId(), child.getName() + " должен быть ребёнком invoke_agent");
        }

        Set<String> found = metrics.collectAllMetrics().stream().map(MetricData::getName).collect(Collectors.toSet());
        for (String want : List.of("gen_ai.client.operation.duration", "gen_ai.client.token.usage")) {
            assertTrue(found.contains(want), "нет метрики " + want + ", есть " + found);
        }
    }
}
