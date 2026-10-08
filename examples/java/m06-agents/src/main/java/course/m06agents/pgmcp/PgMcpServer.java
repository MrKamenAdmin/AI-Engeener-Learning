package course.m06agents.pgmcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MCP-сервер из m06.html#mcp-go: read-only SQL к Postgres (tool), описание схемы (resource)
 * и шаблон недельного отчёта (prompt).
 *
 * <pre>
 * PG_RO_DSN='jdbc:postgresql://localhost:5432/analytics?user=mcp_ro' \
 *   mvn -q -pl m06-agents exec:java -Dexec.mainClass=course.m06agents.pgmcp.PgMcpServer
 * </pre>
 */
public final class PgMcpServer {
    private PgMcpServer() {}

    /** Источник соединений: DriverManager, HikariDataSource::getConnection или null-заглушка в тестах. */
    @FunctionalInterface
    public interface Db {
        Connection connect() throws SQLException;
    }

    public record QueryOut(List<String> columns, List<List<Object>> rows, boolean truncated) {}

    static final String INPUT_SCHEMA = """
            {"type":"object","required":["sql"],"properties":{
              "sql":{"type":"string","description":"one PostgreSQL SELECT statement, no trailing semicolon"},
              "limit":{"type":"integer","description":"max rows to return, default 100, max 500"}}}""";

    static final Pattern ISO_WEEK = Pattern.compile("^\\d{4}-W(0[1-9]|[1-4]\\d|5[0-3])$");

    static final String SCHEMA_DOC = """
            # analytics
            - orders(id, user_id → users.id, created_at timestamptz, status: new|paid|refunded, total_cents bigint)
            - users(id, created_at, country char(2)) — без PII: email и имена в другой БД
            - payments(id, order_id → orders.id, paid_at, amount_cents, provider)
            Деньги — в центах; «выручка» = sum(total_cents) по status = 'paid'.""";

    public static void main(String[] args) throws InterruptedException {
        // Роль в DSN должна иметь только SELECT — это главная защита, всё остальное — пояса.
        // ponytail: соединение на вызов; под нагрузкой — пул HikariCP с maximumPoolSize=4.
        String dsn = System.getenv("PG_RO_DSN");
        var json = new JacksonMcpJsonMapper(new ObjectMapper());
        newServer(McpServer.sync(new StdioServerTransportProvider(json)), () -> DriverManager.getConnection(dsn), json);
        Thread.currentThread().join(); // транспорт читает stdin в своих потоках; stdout занят протоколом
    }

    /** spec — McpServer.sync(...) над stdio или Streamable HTTP транспортом: примитивы одни и те же. */
    public static McpSyncServer newServer(McpServer.SyncSpecification<?> spec, Db db, McpJsonMapper json) {
        var tool = McpSchema.Tool.builder()
                .name("pg_query")
                .description("Run a read-only SQL SELECT against the analytics Postgres database. "
                        + "Use for questions about orders, users and payments. Results are capped at 500 rows "
                        + "and 5s execution; aggregate in SQL instead of fetching raw rows. "
                        + "On errors the message lists what to fix.")
                .inputSchema(json, INPUT_SCHEMA)
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).build())
                .build();

        // Resource: данные, которые host сам кладёт в контекст (application-controlled).
        var schema = new SyncResourceSpecification(
                McpSchema.Resource.builder().uri("schema://analytics").name("analytics-schema").mimeType("text/markdown")
                        .description("Tables, columns and join keys of the analytics database. Attach before writing SQL.")
                        .build(),
                (ex, req) -> new McpSchema.ReadResourceResult(
                        List.of(new McpSchema.TextResourceContents(req.uri(), "text/markdown", SCHEMA_DOC))));

        // Prompt: шаблон, который пользователь выбирает явно (слэш-команда в клиенте).
        var weekly = new SyncPromptSpecification(
                new McpSchema.Prompt("weekly_report", "Orders and revenue report for one ISO week",
                        List.of(new McpSchema.PromptArgument("week", "ISO week, e.g. 2026-W40", true))),
                (ex, req) -> {
                    String week = String.valueOf(req.arguments().get("week"));
                    if (!ISO_WEEK.matcher(week).matches()) { // аргумент попадёт в промпт — валидируем как любой ввод
                        throw new IllegalArgumentException("week must look like 2026-W40, got \"" + week + "\"");
                    }
                    return new McpSchema.GetPromptResult("Weekly report for " + week, List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER, new McpSchema.TextContent("Build a report for ISO week " + week
                                    + ": orders count, revenue, average order value, and the change against the previous week. "
                                    + "Read the schema://analytics resource first, aggregate in SQL with pg_query, show the queries you ran."))));
                });

        return spec
                .serverInfo("pg-readonly", "v0.2.0")
                .jsonMapper(json)
                .validateToolInputs(true) // аргументы проверяются по inputSchema ещё до обработчика
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).resources(false, false).prompts(false).build())
                .tools(new SyncToolSpecification(tool, (ex, req) -> query(db, json, req.arguments())))
                .resources(schema)
                .prompts(weekly)
                .build();
    }

    /** Ошибка обработчика — это результат с isError, а не протокольная ошибка: модель её увидит. */
    static CallToolResult error(String msg) {
        return CallToolResult.builder().addTextContent(msg).isError(true).build();
    }

    static CallToolResult query(Db db, McpJsonMapper json, Map<String, Object> args) {
        String q = String.valueOf(args.getOrDefault("sql", "")).strip();
        if (q.contains(";")) {
            return error("multiple statements are not allowed; remove ';'");
        }
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 0;
        if (limit <= 0 || limit > 500) {
            limit = 100;
        }
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            c.setReadOnly(true); // BEGIN READ ONLY
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL statement_timeout = '5s'");
                try (ResultSet rs = st.executeQuery("SELECT * FROM (" + q + ") AS q LIMIT " + (limit + 1))) {
                    var meta = rs.getMetaData();
                    List<String> cols = new ArrayList<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        cols.add(meta.getColumnLabel(i));
                    }
                    List<List<Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.next()) {
                        if (rows.size() == limit) {
                            truncated = true;
                            break;
                        }
                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= cols.size(); i++) {
                            Object v = rs.getObject(i);
                            row.add(v == null || v instanceof Number || v instanceof Boolean ? v : v.toString());
                        }
                        rows.add(row);
                    }
                    var out = new QueryOut(cols, rows, truncated);
                    return CallToolResult.builder().structuredContent(out).addTextContent(json.writeValueAsString(out)).build();
                }
            } finally {
                c.rollback(); // только чтение — коммитить нечего
            }
        } catch (SQLException e) {
            return error("query failed: " + e.getMessage() + ". Check table/column names with information_schema");
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }
}
