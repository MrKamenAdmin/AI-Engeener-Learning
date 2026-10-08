package course.m06agents;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import java.util.List;
import java.util.Map;

/**
 * Инструмент цикла: определение для Messages API, флаг опасности и обработчик.
 *
 * @param dangerous мутирует мир: требует подтверждения человека
 */
public record AgentTool(Tool param, boolean dangerous, Fn run) {

    /** Обработчик получает input модели как JSON-строку. Исключение — ошибка инструмента. */
    @FunctionalInterface
    public interface Fn {
        String run(String inputJson) throws Exception;
    }

    public AgentTool(Tool param, Fn run) {
        this(param, false, run);
    }

    public AgentTool withRun(Fn run) {
        return new AgentTool(param, dangerous, run);
    }

    public AgentTool withDangerous(boolean dangerous) {
        return new AgentTool(param, dangerous, run);
    }

    public String name() {
        return param.name();
    }

    /** Определение инструмента: имя, описание и JSON Schema входа. */
    public static Tool def(String name, String description, Map<String, ?> properties, List<String> required) {
        var props = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        var b = Tool.builder().name(name)
                .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(required).build());
        if (description != null && !description.isEmpty()) {
            b.description(description);
        }
        return b.build();
    }

    public static Tool def(String name) {
        return def(name, null, Map.of(), List.of());
    }
}
