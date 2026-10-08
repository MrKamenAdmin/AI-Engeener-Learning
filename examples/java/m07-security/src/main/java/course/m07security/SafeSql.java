package course.m07security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** SQL: модель выбирает из allowlist, а не пишет запрос. */
public final class SafeSql {
    private SafeSql() {}

    /** То, что модель возвращает через structured output вместо сырого SQL. where: колонка = значение. */
    public record QuerySpec(String table, List<String> columns, Map<String, String> where, int limit) {
        public QuerySpec {
            columns = columns == null ? List.of() : columns;
            where = where == null ? Map.of() : where;
        }
    }

    /** Готовый запрос для PreparedStatement: значения — только в args. */
    public record Select(String sql, List<Object> args) {}

    /**
     * Собирает параметризованный SELECT. schema — allowlist «таблица → разрешённые колонки»:
     * в SQL попадают только эти строки. tenantId берётся из сессии, а не от модели.
     * Исполнять под read-only ролью с statement_timeout (модуль 12, разбор text-to-SQL).
     */
    public static Select buildSelect(Map<String, List<String>> schema, QuerySpec q, String tenantId)
            throws NotAllowedException {
        List<String> cols = schema.get(q.table());
        if (cols == null) {
            throw new NotAllowedException("table \"" + q.table() + "\"");
        }
        if (q.columns().isEmpty()) {
            throw new NotAllowedException("empty column list");
        }
        for (String c : q.columns()) {
            if (!cols.contains(c)) {
                throw new NotAllowedException("column \"" + c + "\"");
            }
        }
        for (String k : q.where().keySet()) {
            if (!cols.contains(k)) {
                throw new NotAllowedException("filter \"" + k + "\"");
            }
        }
        var sql = new StringBuilder("SELECT " + String.join(", ", q.columns()) + " FROM " + q.table() + " WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>(List.of(tenantId));
        // TreeMap: детерминированный SQL, удобно в тестах и в кэше планов.
        new TreeMap<>(q.where()).forEach((k, v) -> {
            sql.append(" AND ").append(k).append(" = ?");
            args.add(v);
        });
        sql.append(" LIMIT ").append(Math.clamp(q.limit(), 1, 100));
        return new Select(sql.toString(), List.copyOf(args));
    }
}
