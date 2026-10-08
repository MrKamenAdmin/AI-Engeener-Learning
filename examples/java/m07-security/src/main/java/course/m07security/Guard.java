package course.m07security;

import java.io.IOException;
import java.util.List;

/**
 * Проверяет текст и может вернуть его изменённым (редакция PII, вырезанные ссылки).
 * Отказ по политике — {@link Blocked}; IOException или RuntimeException значат «guard сломался».
 */
public interface Guard {
    String name();

    String check(String text) throws Blocked, IOException;

    final class Blocked extends Exception {
        private final String guard, reason;

        public Blocked(String guard, String reason) {
            super("blocked by " + guard + ": " + reason);
            this.guard = guard;
            this.reason = reason;
        }

        public String guard() {
            return guard;
        }

        public String reason() {
            return reason;
        }
    }

    final class UnavailableException extends IOException {
        public UnavailableException(String guard, Throwable cause) {
            super("guardrail unavailable: " + guard + ": " + cause.getMessage(), cause);
        }
    }

    /**
     * Прогоняет текст через guards по порядку: дешёвые первыми. По умолчанию fail-closed:
     * сломанный guard блокирует запрос. Fail-open включается явно — {@link #failOpen(Guard)}.
     */
    static String runChain(List<Guard> gs, String text) throws Blocked, UnavailableException {
        for (Guard g : gs) {
            try {
                text = g.check(text);
            } catch (IOException | RuntimeException e) {
                throw new UnavailableException(g.name(), e);
            }
        }
        return text;
    }

    /**
     * Если guard недоступен, пропускаем текст и пишем предупреждение. Только для guards,
     * чей пропуск дешевле отказа (тематический фильтр внутреннего инструмента).
     */
    static Guard failOpen(Guard g) {
        return new Guard() {
            @Override
            public String name() {
                return g.name();
            }

            @Override
            public String check(String text) throws Blocked {
                try {
                    return g.check(text);
                } catch (IOException | RuntimeException e) {
                    System.getLogger("guard").log(System.Logger.Level.WARNING,
                            "guard unavailable, fail-open guard={0} err={1}", g.name(), e.toString());
                    return text;
                }
            }
        };
    }
}
