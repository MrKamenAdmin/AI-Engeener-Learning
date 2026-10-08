package course.m05rag;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import course.m05rag.Agentic.Decision;
import course.m05rag.Agentic.Limits;
import course.m05rag.Agentic.Planner;
import course.m05rag.Agentic.Searcher;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RagTest {
    static Searcher fakeSearch(Map<String, List<Hit>> m) {
        return (q, k) -> m.getOrDefault(q, List.of());
    }

    /** Отдаёт решения по очереди; последнее повторяется. */
    static final class Scripted implements Planner {
        final List<Decision> plans = new ArrayList<>();
        int calls;
        boolean partial;

        Scripted(Decision... ds) { plans.addAll(List.of(ds)); }

        @Override public Decision plan(String question, List<Hit> evidence) {
            return plans.get(Math.min(calls++, plans.size() - 1));
        }

        @Override public String answer(String question, List<Hit> ev, boolean partial) {
            this.partial = partial;
            return "ответ по %d чанкам".formatted(ev.size());
        }
    }

    static final Limits LIM = new Limits(3, 5, 5, 20);

    @Test void simpleQuestionIsOneRound() throws IOException {
        var s = fakeSearch(Map.of("часы работы", List.of(new Hit(1, "9–18"))));
        var res = Agentic.answer(s, new Scripted(Decision.ok()), "часы работы", LIM);
        assertTrue(res.iters == 1 && res.searches == 1 && !res.partial, "простой вопрос — один раунд: " + res);
    }

    @Test void multiHop() throws IOException {
        String q = "Кто руководит командой, которая владеет биллингом?";
        var s = fakeSearch(Map.of(
                q, List.of(new Hit(1, "Биллингом владеет команда Payments"), new Hit(7, "Биллинг: SLA")),
                "руководитель команды Payments", List.of(new Hit(2, "Payments руководит Анна"), new Hit(1, ""))));
        var p = new Scripted(Decision.more("руководитель команды Payments"), Decision.ok());
        var res = Agentic.answer(s, p, q, LIM);
        assertTrue(res.iters == 2 && res.searches == 2 && !res.partial, "multi-hop за два раунда: " + res);
        assertEquals(3, res.evidence.size()); // ID 1 пришёл дважды — дедупликация
    }

    @Test void stopsOnBudget() throws IOException {
        var p = new Scripted();
        for (int n = 1; n <= 10; n++) { // модель всегда недовольна и всегда придумывает новый запрос
            p.plans.add(Decision.more("q" + n));
        }
        var res = Agentic.answer(fakeSearch(Map.of()), p, "вопрос", LIM);
        assertTrue(res.iters == LIM.maxIters() && res.partial && p.partial && res.searches <= LIM.maxSearches(),
                "бюджет: " + res);
    }

    @Test void detectsLoop() throws IOException {
        var p = new Scripted(Decision.more("вопрос")); // просит искать то же самое
        var res = Agentic.answer(fakeSearch(Map.of()), p, "вопрос", LIM);
        assertTrue(res.searches == 1 && res.partial, "повтор запроса — остановка: " + res);
    }

    @Test void rerankHits() throws IOException {
        var mapper = new ObjectMapper();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/rerank", ex -> {
            JsonNode in = mapper.readTree(ex.getRequestBody());
            boolean ok = in.path("documents").size() == 3 && in.path("top_k").asInt() == 2;
            byte[] body = (ok
                    ? "{\"object\":\"list\",\"data\":[{\"index\":2,\"relevance_score\":0.91},{\"index\":9,\"relevance_score\":0.8},{\"index\":0,\"relevance_score\":0.12}],\"model\":\"rerank-3\",\"usage\":{\"total_tokens\":42}}"
                    : "bad request").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ok ? 200 : 400, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        srv.start();
        try {
            var v = new Rerank.Voyage(HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + srv.getAddress().getPort(), "k", "rerank-3");
            List<Hit> hits = List.of(new Hit(10, ""), new Hit(11, ""), new Hit(12, ""));

            var r = Rerank.rerankHits(v, "429 ретраи", hits, 2, 0.3);
            assertFalse(r.degraded());
            assertEquals(1, r.hits().size(), "ждали только ID 12 (0.12 ниже порога, index 9 — мусор)");
            assertEquals(12, r.hits().get(0).hit().id());

            Rerank.Reranker failing = (q, docs, k) -> { throw new IOException("timeout"); };
            r = Rerank.rerankHits(failing, "q", hits, 2, 0.3);
            assertTrue(r.degraded() && r.hits().size() == 2 && r.hits().get(0).hit().id() == 10,
                    "при отказе — исходный порядок: " + r);
        } finally {
            srv.stop(0);
        }
    }
}
