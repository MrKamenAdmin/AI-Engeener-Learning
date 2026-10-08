package course.m06agents;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class McpToolsTest {

    /** Запускает main-класс сервером MCP дочерним процессом по stdio — как это делает host. */
    public static McpSyncClient spawn(Class<?> main) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var params = ServerParameters.builder(java)
                .args("-cp", System.getProperty("java.class.path"), main.getName())
                .build();
        var client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .clientInfo(new McpSchema.Implementation("host", "v0"))
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30))
                .build();
        client.initialize();
        return client;
    }

    @Test
    void hostPolicy() throws Exception {
        try (var cs = spawn(DocsServer.class)) {
            var tools = McpTools.from(cs, Set.of("docs_search", "docs_delete"));
            assertFalse(tools.get("docs_search").dangerous(), "allowlisted AND read-only runs without confirmation");
            assertTrue(tools.get("docs_delete").dangerous(), "no readOnlyHint — confirmation required");

            assertEquals("found: compaction", tools.get("docs_search").run().run("{\"q\":\"compaction\"}"));
            var e = assertThrows(McpTools.ToolErrorException.class, () -> tools.get("docs_search").run().run("{\"q\":\"\"}"));
            assertTrue(e.getMessage().contains("empty query"), "isError must become an error with the server's text");
        }
    }
}
