package course.m07security;

import course.m07security.LLM.ToolCall;
import java.util.List;
import java.util.Map;

/**
 * Allowlist инструментов агента. Чего нет в tools, того агент сделать не может,
 * что бы ни написала модель. confirm == null: необратимые действия запрещены.
 */
public record Registry(Map<String, Tool> tools, Confirmer confirm) {
    private static final System.Logger LOG = System.getLogger("audit");

    public Registry {
        tools = tools == null ? Map.of() : Map.copyOf(tools);
    }

    /**
     * От чьего имени действует агент. scopes и token приходят из OAuth-токена пользователя
     * при аутентификации, а не из промпта и не из аргументов, которые выбрала модель.
     * token — токен пользователя для downstream API, а не сервисный суперключ.
     */
    public record Principal(String userId, List<String> scopes, String token) {
        public Principal {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    @FunctionalInterface
    public interface ToolFn {
        String run(Principal p, String argsJson) throws Exception;
    }

    /** scopes — нужны все; confirm — необратимое действие, нужен человек. */
    public record Tool(String name, List<String> scopes, boolean confirm, ToolFn run) {}

    /** Показывает человеку точное действие с аргументами и возвращает его решение. */
    @FunctionalInterface
    public interface Confirmer {
        boolean confirm(Principal p, ToolCall c);
    }

    public static final class ToolException extends Exception {
        public enum Kind { UNKNOWN_TOOL, SCOPE, DECLINED, FAILED }

        private final Kind kind;

        ToolException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    public String call(Principal p, ToolCall c) throws ToolException {
        Tool t = tools.get(c.name());
        if (t == null) {
            throw new ToolException(ToolException.Kind.UNKNOWN_TOOL, "tool not in allowlist: \"" + c.name() + "\"", null);
        }
        for (String s : t.scopes()) {
            if (!p.scopes().contains(s)) {
                throw new ToolException(ToolException.Kind.SCOPE, "insufficient scope: " + c.name() + " requires " + s, null);
            }
        }
        if (t.confirm() && (confirm == null || !confirm.confirm(p, c))) {
            throw new ToolException(ToolException.Kind.DECLINED, "action not confirmed: " + c.name(), null);
        }
        // Аудит каждого вызова: расследование инцидента начинается с этого лога.
        LOG.log(System.Logger.Level.INFO, "tool call user={0} tool={1} args={2}", p.userId(), c.name(), c.args());
        try {
            return t.run().run(p, c.args());
        } catch (Exception e) {
            throw new ToolException(ToolException.Kind.FAILED, c.name() + ": " + e.getMessage(), e);
        }
    }
}
