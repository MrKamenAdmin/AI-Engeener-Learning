package course.m05rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Rerank поверх hybrid-поиска с порогом и деградацией при отказе reranker'а. */
public final class Rerank {
    private Rerank() {}

    /** Позиция документа во входном списке и его релевантность по мнению reranker'а. */
    public record Ranked(int index, double score) {}

    public interface Reranker {
        List<Ranked> rerank(String query, List<String> docs, int topK) throws IOException, InterruptedException;
    }

    /** Reranker Voyage AI: POST {baseUrl}/rerank. */
    public record Voyage(HttpClient http, String baseUrl, String key, String model) implements Reranker {
        // baseUrl: "https://api.voyageai.com/v1"; в тестах — локальный HttpServer.
        // model: "rerank-3" (на октябрь 2026); "rerank-2.5" ещё доступна.
        private static final ObjectMapper JSON = new ObjectMapper();

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Resp(List<Item> data) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Item(int index, @JsonProperty("relevance_score") double relevanceScore) {}

        @Override
        public List<Ranked> rerank(String query, List<String> docs, int topK) throws IOException, InterruptedException {
            byte[] body = JSON.writeValueAsBytes(Map.of("query", query, "documents", docs, "model", model, "top_k", topK));
            var req = HttpRequest.newBuilder(URI.create(baseUrl + "/rerank"))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                String msg = resp.body().substring(0, Math.min(2048, resp.body().length()));
                throw new IOException("voyage rerank: status " + resp.statusCode() + ": " + msg);
            }
            return JSON.readValue(resp.body(), Resp.class).data().stream()
                    .map(d -> new Ranked(d.index(), d.relevanceScore()))
                    .toList();
        }
    }

    public record Scored(Hit hit, double score) {}

    public record Reranked(List<Scored> hits, boolean degraded) {}

    /**
     * Переранжирует кандидатов и отсекает всё ниже minScore. Если reranker недоступен,
     * отдаёт исходный порядок (RRF) и degraded = true: хуже, чем с rerank, лучше, чем 500.
     */
    public static Reranked rerankHits(Reranker r, String query, List<Hit> hits, int topK, double minScore) {
        List<String> docs = hits.stream().map(h -> h.heading() + "\n" + h.content()).toList();
        List<Scored> out = new ArrayList<>();
        List<Ranked> ranked;
        try {
            ranked = new ArrayList<>(r.rerank(query, docs, topK));
        } catch (IOException | RuntimeException e) {
            hits.stream().limit(topK).forEach(h -> out.add(new Scored(h, 0))); // скоров нет — порог не применить
            return new Reranked(out, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            hits.stream().limit(topK).forEach(h -> out.add(new Scored(h, 0)));
            return new Reranked(out, true);
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed()); // по убыванию; порядку API не доверяем
        for (Ranked rk : ranked) {
            if (rk.index() < 0 || rk.index() >= hits.size()) {
                continue; // ответ внешнего API — недоверенный ввод
            }
            if (rk.score() < minScore || out.size() == topK) {
                break;
            }
            out.add(new Scored(hits.get(rk.index()), rk.score()));
        }
        return new Reranked(out, false); // пусто — честное «в базе нет ответа», а не повод отдать мусор модели
    }
}
