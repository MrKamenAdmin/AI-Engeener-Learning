package course.m07security;

import course.m07security.LLM.Request;
import course.m07security.LLM.Response;
import course.m07security.LLM.ToolCall;
import course.m07security.Registry.Principal;
import course.m07security.Registry.ToolException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Один шаг агента: модель отвечает и просит инструменты, мы их исполняем через Registry
 * в рамках Budget. Полный цикл с возвратом tool_result модели — модуль 6.
 * maxOutput — max_tokens одного ответа.
 */
public record ToolAgent(LLM llm, Registry tools, Principal user, String system, int maxOutput,
                        Budget.Limits limits) implements Agent {

    @Override
    public Trace run(Input in) throws IOException {
        var b = new Budget(limits);
        try {
            b.step();
        } catch (Budget.ExceededException e) {
            throw new IOException(e);
        }
        Response resp = llm.complete(new Request(system, in.prompt(), in.docs(), maxOutput));
        var out = new StringBuilder(resp.text());
        List<String> used = new ArrayList<>();
        try {
            b.spend(resp.inputTokens() + resp.outputTokens());
        } catch (Budget.ExceededException e) {
            out.append("\n[остановлено: ").append(e.getMessage()).append(']');
            return new Trace(out.toString(), used, false);
        }
        for (ToolCall c : resp.toolCalls()) {
            try {
                b.step();
            } catch (Budget.ExceededException e) {
                out.append("\n[остановлено: ").append(e.getMessage()).append(']');
                break;
            }
            try {
                String res = tools.call(user, c);
                used.add(c.name());
                out.append('\n').append(res);
            } catch (ToolException e) {
                out.append("\n[").append(c.name()).append(": ").append(e.getMessage()).append(']'); // в настоящем цикле — tool_result с is_error
            }
        }
        return new Trace(out.toString(), used, false);
    }
}
