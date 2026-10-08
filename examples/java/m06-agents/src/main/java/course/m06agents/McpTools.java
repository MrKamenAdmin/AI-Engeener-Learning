package course.m06agents;

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Мост MCP → инструменты цикла. */
public final class McpTools {
    private McpTools() {}

    /** Ошибка инструмента (isError): модель увидит текст сервера и исправится. */
    public static final class ToolErrorException extends Exception {
        ToolErrorException(String msg) {
            super(msg);
        }
    }

    /**
     * Превращает инструменты MCP-сервера в инструменты цикла: tools/list → определения
     * для Messages API, tool_use → tools/call. Политику подтверждений задаёт host: autoApprove —
     * allowlist имён, которые можно вызывать без человека. Аннотации сервера — подсказка от
     * недоверенной стороны: они могут ужесточить политику, но не ослабить её.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, AgentTool> from(McpSyncClient client, Set<String> autoApprove) {
        Map<String, AgentTool> tools = new HashMap<>();
        String cursor = null;
        do {
            McpSchema.ListToolsResult page = client.listTools(cursor);
            for (McpSchema.Tool t : page.tools()) {
                Map<String, Object> schema = t.inputSchema() == null ? Map.of() : t.inputSchema();
                var props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
                var required = (List<String>) schema.getOrDefault("required", List.of());
                String name = t.name();
                boolean readOnly = t.annotations() != null && Boolean.TRUE.equals(t.annotations().readOnlyHint());
                tools.put(name, new AgentTool(
                        AgentTool.def(name, t.description(), props, required),
                        !autoApprove.contains(name) || !readOnly,
                        input -> {
                            Map<String, Object> args = ObjectMappers.jsonMapper().readValue(input, new TypeReference<>() {});
                            // Протокольная ошибка или обрыв соединения — McpError/RuntimeException из SDK.
                            McpSchema.CallToolResult res = client.callTool(new McpSchema.CallToolRequest(name, args));
                            String text = res.content().stream()
                                    .filter(c -> c instanceof McpSchema.TextContent)
                                    .map(c -> ((McpSchema.TextContent) c).text())
                                    .collect(Collectors.joining());
                            if (Boolean.TRUE.equals(res.isError())) {
                                throw new ToolErrorException(text);
                            }
                            return text;
                        }));
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        return tools;
    }
}
