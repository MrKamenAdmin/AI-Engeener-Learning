package course.m06agents;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Примеры сборки агента. Компилируются в mvn verify, но не запускаются тестами: нужен ANTHROPIC_API_KEY.
 * Запуск: {@code mvn -q -pl m06-agents exec:java -Dexec.mainClass=course.m06agents.Examples -Dexec.args=long}
 */
public final class Examples {
    private Examples() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("mcp")) {
            mcpTools();
        } else {
            longTask();
        }
    }

    /** Длинная задача: общий бюджет, дешёвый субагент-исследователь с чистым контекстом, компакция и заметки. */
    static void longTask() {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv(); // ключ из ANTHROPIC_API_KEY
        var root = new Budgeted(client.messages()::create, 5);

        var researcher = new Agent(root, "claude-haiku-4-5",
                "Answer the question using the read-only tools. Return facts with file paths, at most 300 words.",
                Map.of(/* grep, read_file — только чтение */), 15, null, null);
        var notes = new Notes(Path.of("NOTES.md"), 8 << 10);
        var lead = new Agent(root, "claude-opus-5",
                "You fix bugs in this repository. Keep NOTES.md current: plan, done, decisions, next step.",
                Map.of("notes", notes.tool(),
                        "research", researcher.asTool("research", "Delegate a read-only investigation; returns a short report.", 0.30, 6000)),
                80,
                new Compactor(root, "claude-haiku-4-5", 120_000, 6),
                Confirm.stdin());
        try {
            var res = lead.run("Find and fix the deadlock in inventory.Reserve");
            System.out.printf("%d iters, %d compactions, peak %d tokens, $%.2f%n%s%n",
                    res.iters, res.compactions, res.peakCtx, root.spent(), res.text);
        } catch (Agent.AgentException e) {
            System.out.printf("stopped after %d iters, $%.2f: %s%n", e.result.iters, root.spent(), e.getMessage());
        }
    }

    /** Host подключает MCP-сервер дочерним процессом и отдаёт его инструменты своему циклу. */
    static void mcpTools() throws Agent.AgentException {
        var transport = new StdioClientTransport(ServerParameters.builder("pg-mcp").build(), McpJsonDefaults.getMapper());
        try (var session = McpClient.sync(transport).clientInfo(new McpSchema.Implementation("my-agent", "0.1.0")).build()) {
            session.initialize();
            var tools = McpTools.from(session, Set.of("pg_query"));
            AnthropicClient llm = AnthropicOkHttpClient.fromEnv();
            var a = new Agent(llm.messages()::create, "claude-opus-5", "", tools, 20, null, Confirm.stdin());
            System.out.println(a.run("How many orders were paid last week?").text);
        }
    }
}
