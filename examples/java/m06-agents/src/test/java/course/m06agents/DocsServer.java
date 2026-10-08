package course.m06agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Тестовый MCP-сервер по stdio: docs_search (read-only) и docs_delete (без аннотаций). */
public final class DocsServer {
    static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}";

    public static void main(String[] args) throws InterruptedException {
        var json = new JacksonMcpJsonMapper(new ObjectMapper());
        var search = McpSchema.Tool.builder().name("docs_search").inputSchema(json, SCHEMA)
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).build()).build();
        var delete = McpSchema.Tool.builder().name("docs_delete").inputSchema(json, SCHEMA).build();
        McpServer.sync(new StdioServerTransportProvider(json))
                .serverInfo("docs", "v0")
                .jsonMapper(json)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(new SyncToolSpecification(search, (ex, req) -> {
                            String q = String.valueOf(req.arguments().getOrDefault("q", ""));
                            if (q.isEmpty()) {
                                return CallToolResult.builder().addTextContent("empty query; pass q").isError(true).build();
                            }
                            return CallToolResult.builder().addTextContent("found: " + q).build();
                        }),
                        new SyncToolSpecification(delete, (ex, req) -> CallToolResult.builder().build()))
                .build();
        Thread.currentThread().join();
    }
}
