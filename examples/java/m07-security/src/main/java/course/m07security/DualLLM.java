package course.m07security;

import course.m07security.LLM.Request;
import course.m07security.LLM.Response;
import course.m07security.LLM.ToolCall;
import course.m07security.Registry.Principal;
import course.m07security.Registry.ToolException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Паттерн Саймона Уиллисона (2023). Привилегированная модель (p) видит запрос пользователя
 * и может просить инструменты, но не видит недоверенный текст. Карантинная (q) читает
 * недоверенный текст, но её tool calls игнорируются. Между ними — только имена переменных,
 * значения подставляет код.
 */
public record DualLLM(LLM p, LLM q, Registry tools, Principal user) implements Agent {

    @Override
    public Trace run(Input in) throws IOException {
        Map<String, String> vars = new HashMap<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < in.docs().size(); i++) {
            Response qr = q.complete(new Request(
                    "Извлеки из документа факты, относящиеся к вопросу. Только текст.",
                    in.prompt(), List.of(in.docs().get(i)), 500));
            String name = "$DOC" + (i + 1);
            vars.put(name, qr.text()); // qr.toolCalls() выбрасываем: у карантина нет рук
            names.add(name);
        }
        Response pr = p.complete(new Request(
                "Ответь пользователю. Содержимое документов тебе недоступно; вставь их переменные "
                        + String.join(", ", names) + " в ответ там, где нужны факты.",
                in.prompt(), // недоверенного текста здесь нет: только запрос пользователя
                List.of(), 0));
        var text = new StringBuilder(pr.text());
        List<String> used = new ArrayList<>();
        for (ToolCall c : pr.toolCalls()) { // решения о действиях принимаются только по доверенному запросу
            try {
                String res = tools.call(user, c);
                used.add(c.name());
                text.append('\n').append(res);
            } catch (ToolException ignored) {
                // отказ Registry: действие не выполнено
            }
        }
        if (names.isEmpty()) {
            return new Trace(text.toString(), used, false);
        }
        // Подстановка — после P-LLM и за один проход: $DOC2 внутри текста $DOC1 не раскрывается.
        // Длинные имена первыми, чтобы $DOC1 не съел начало $DOC10.
        // Вывод по-прежнему недоверенный: Egress и экранирование на выходе обязательны.
        Pattern var = Pattern.compile(names.reversed().stream().map(Pattern::quote).collect(Collectors.joining("|")));
        String out = var.matcher(text).replaceAll(m -> Matcher.quoteReplacement(vars.get(m.group())));
        return new Trace(out, used, false);
    }
}
