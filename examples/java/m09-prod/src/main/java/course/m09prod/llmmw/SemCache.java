package course.m09prod.llmmw;

// CREATE EXTENSION IF NOT EXISTS vector;
// CREATE TABLE llm_cache (
//   id bigserial PRIMARY KEY,
//   scope text NOT NULL,              -- hash(tenant, model, system prompt, версия промпта)
//   query text NOT NULL,
//   embedding vector(1024) NOT NULL,
//   response text NOT NULL,
//   created_at timestamptz NOT NULL DEFAULT now());
// CREATE INDEX ON llm_cache USING hnsw (embedding vector_cosine_ops);
// CREATE INDEX ON llm_cache (scope, created_at);
// Фильтр по scope + HNSW: в pgvector ≥ 0.8 включите SET hnsw.iterative_scan = relaxed_order,
// иначе после фильтрации может не остаться кандидатов. Вектор передаём текстом '[...]'::vector,
// чтобы не тянуть зависимость; с com.pgvector:pgvector — setObject(i, new PGvector(emb)).

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import javax.sql.DataSource;

/** threshold — cosine similarity, калибруется на размеченных парах. */
public record SemCache(LLM next, DataSource db, Embedder embed, double threshold, Duration ttl) implements LLM {

    @FunctionalInterface
    public interface Embedder {
        float[] embed(String text) throws Exception;
    }

    private static final Logger LOG = System.getLogger(SemCache.class.getName());

    @Override
    public Response complete(Request req) throws Exception {
        String q = req.lastUserText();
        if (q.isEmpty() || req.messages().size() > 1) { // кэшируем только первый вопрос без истории
            return next.complete(req);
        }
        float[] emb;
        try {
            emb = embed.embed(q);
        } catch (Exception e) {
            return next.complete(req); // кэш — оптимизация, а не точка отказа
        }
        String vec = pgVector(emb), scope = scopeKey(req);

        try (var c = db.getConnection(); var st = c.prepareStatement("""
                SELECT response, 1 - (embedding <=> ?::vector) FROM llm_cache
                WHERE scope = ? AND created_at > now() - make_interval(secs => ?)
                ORDER BY embedding <=> ?::vector LIMIT 1""")) {
            st.setString(1, vec);
            st.setString(2, scope);
            st.setDouble(3, ttl.toSeconds());
            st.setString(4, vec);
            try (var rs = st.executeQuery()) {
                if (rs.next() && rs.getDouble(2) >= threshold) {
                    return Response.of(rs.getString(1), req.model(), "end_turn").withRoute("semcache");
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "semcache lookup", e);
        }

        Response resp = next.complete(req);
        if ("end_turn".equals(resp.stopReason())) { // не кэшируем обрезанные ответы и refusal
            try (var c = db.getConnection(); var st = c.prepareStatement(
                    "INSERT INTO llm_cache (scope, query, embedding, response) VALUES (?, ?, ?::vector, ?)")) {
                st.setString(1, scope);
                st.setString(2, q);
                st.setString(3, vec);
                st.setString(4, resp.text());
                st.executeUpdate();
            } catch (SQLException e) {
                LOG.log(Level.WARNING, "semcache store", e);
            }
        }
        return resp;
    }

    static String pgVector(float[] v) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(v[i]);
        }
        return sb.append(']').toString();
    }

    static String scopeKey(Request r) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256")
                .digest((r.tenant() + "\0" + r.model() + "\0" + r.system()).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(h);
    }
}
