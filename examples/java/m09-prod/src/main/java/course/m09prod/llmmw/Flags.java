package course.m09prod.llmmw;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class Flags {
    private Flags() {}

    static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Что переключает флаг: промпт, модель и параметры вместе. Промпт, отлаженный под одну модель,
     * на другой ведёт себя иначе, поэтому раздельные флаги дают непроверенные комбинации.
     * name пишется в трейс (app.flag.variant) и в онлайн-evals; prompt — "answer@v13".
     */
    public record Variant(String name, String prompt, String model, @JsonProperty("max_tokens") long maxTokens) {
        public Variant(String name, String model) {
            this(name, null, model, 0);
        }
    }

    /**
     * killed — kill switch: всем control, без деплоя. percent — доля трафика на treatment, 0–100.
     * tenants — таргетинг: true — всегда treatment, false — никогда.
     */
    public record Flag(String key, boolean killed, Variant control, Variant treatment,
                       double percent, Map<String, Boolean> tenants) {

        /**
         * Детерминирован: один unit (пользователь или тенант) всегда получает один вариант,
         * а при росте percent 1 → 5 → 25 → 100 попавшие в treatment из него не выпадают.
         */
        public Variant evaluate(String tenant, String unit) {
            if (killed) {
                return control;
            }
            Boolean on = tenants == null ? null : tenants.get(tenant);
            if (on != null) {
                return on ? treatment : control;
            }
            // Ключ флага в хэше: разные флаги режут трафик независимо.
            long bucket = Integer.toUnsignedLong(fnv32a(key + "/" + unit)) % 10000;
            return bucket < percent * 100 ? treatment : control;
        }
    }

    static int fnv32a(String s) {
        int h = 0x811c9dc5;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x01000193;
        }
        return h;
    }

    /** Хранит флаги и перечитывает их на лету (файл, etcd, сервис флагов). */
    public static final class Registry {
        private final AtomicReference<Map<String, Flag>> flags = new AtomicReference<>();

        /**
         * Заменяет конфиг целиком. Битый конфиг отклоняется, и работает предыдущий:
         * опечатка в JSON не должна переключить весь прод на непроверенный промпт.
         */
        public void load(byte[] data) throws IOException {
            List<Flag> list = JSON.readValue(data, new TypeReference<>() {});
            Map<String, Flag> m = new HashMap<>();
            for (Flag f : list) {
                if (f.percent() < 0 || f.percent() > 100 || !hasModel(f.control()) || !hasModel(f.treatment())) {
                    throw new IllegalArgumentException("flags: \"" + f.key() + "\": percent 0–100 and both models required");
                }
                m.put(f.key(), f);
            }
            flags.set(Map.copyOf(m));
        }

        private static boolean hasModel(Variant v) {
            return v != null && v.model() != null && !v.model().isEmpty();
        }

        /** Вариант для запроса; если флага нет, возвращается def (поведение «как до флагов»). */
        public Variant variant(String key, String tenant, String unit, Variant def) {
            Map<String, Flag> m = flags.get();
            Flag f = m == null ? null : m.get(key);
            return f == null ? def : f.evaluate(tenant, unit);
        }
    }
}
