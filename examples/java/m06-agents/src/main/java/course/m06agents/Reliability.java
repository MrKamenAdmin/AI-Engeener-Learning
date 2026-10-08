package course.m06agents;

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Ретраи, таймауты и идемпотентность инструментов. */
public final class Reliability {
    private Reliability() {}

    /**
     * Помечает ошибку, которую имеет смысл повторить: таймаут, 429, 503. Бросайте её только
     * там, где повтор безопасен: чтение или идемпотентная запись.
     */
    public static class TransientException extends Exception {
        public TransientException(String msg) {
            super(msg);
        }
    }

    static boolean isTransient(Throwable e) {
        for (; e != null; e = e.getCause()) {
            if (e instanceof TransientException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Повторяет f при TransientException с экспоненциальной задержкой и джиттером:
     * 100, 200, 400 мс плюс случайная добавка, чтобы ретраи не шли синхронной волной.
     * Прерывание потока (отмена) прекращает ожидание.
     */
    static String retry(int attempts, Callable<String> f) throws Exception {
        for (int i = 0; ; i++) {
            try {
                return f.call();
            } catch (Exception e) {
                if (!isTransient(e) || i == attempts - 1) {
                    throw e;
                }
                long d = 100L << i;
                Thread.sleep(d + ThreadLocalRandom.current().nextLong(d));
            }
        }
    }

    /** Выполняет f на отдельном virtual thread; по таймауту прерывает его. */
    static <T> T withTimeout(Duration timeout, Callable<T> f) throws Exception {
        var task = new FutureTask<>(f);
        Thread.ofVirtual().start(task);
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new TimeoutException("tool timed out after " + timeout.toSeconds() + "s");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    private static final ThreadLocal<String> IDEM_KEY = new ThreadLocal<>();

    /**
     * Ключ текущего вызова; инструмент передаёт его во внешний API (заголовок Idempotency-Key),
     * чтобы дедуплицировал и сервер: ретрай после таймаута мог дойти до него дважды.
     */
    public static String idempotencyKey() {
        String k = IDEM_KEY.get();
        return k == null ? "" : k;
    }

    /**
     * Оборачивает мутирующий инструмент: одинаковый вызов (сессия + имя + аргументы)
     * выполняется один раз, повтор получает сохранённый результат. Агент повторяет вызовы
     * после таймаутов, компакции и рестарта — без этого письмо уйдёт дважды.
     */
    public static AgentTool idempotent(String session, AgentTool t) {
        // Упрощение: журнал в памяти процесса и без блокировки на время выполнения.
        // Для рестартов и параллельных одинаковых вызовов — таблица с UNIQUE(key) в БД.
        Map<String, String> done = new HashMap<>();
        AgentTool.Fn run = t.run();
        return t.withRun(input -> {
            String key = idemKey(session, t.name(), input);
            String prev;
            synchronized (done) {
                prev = done.get(key);
            }
            if (prev != null) {
                return "Already done earlier in this session, not repeated. Result was: " + prev;
            }
            IDEM_KEY.set(key);
            try {
                String out = run.run(input);
                synchronized (done) { // ошибки не запоминаем: повтор после ошибки должен выполниться
                    done.put(key, out);
                }
                return out;
            } finally {
                IDEM_KEY.remove();
            }
        });
    }

    private static final ObjectMapper CANON = ObjectMappers.jsonMapper().copy()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** Не зависит от порядка ключей и пробелов: {"b":1,"a":2} и {"a":2, "b":1} — один вызов. */
    static String idemKey(String session, String tool, String input) throws Exception {
        Object v;
        try {
            v = CANON.readValue(input, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid input JSON: " + e.getMessage(), e);
        }
        String canon = CANON.writeValueAsString(v);
        byte[] sum = sha256(session + "\0" + tool + "\0" + canon);
        return HexFormat.of().formatHex(Arrays.copyOf(sum, 16));
    }

    private static byte[] sha256(String s) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    }
}
