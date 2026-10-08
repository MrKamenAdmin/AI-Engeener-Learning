package course.m06agents;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Агентный цикл модуля 6 (m06.html#go-loop) и всё, что делает его пригодным для длинных задач:
 * бюджет в долларах, компакция, заметки, субагенты, ретраи и идемпотентность инструментов,
 * мост к MCP, сессии с проверкой прогресса.
 *
 * @param model    {@code client.messages()::create} или {@link Budgeted} поверх него
 * @param modelId  "claude-opus-5"
 * @param compact  null — без компакции
 * @param confirm  null — опасные действия запрещены
 */
public record Agent(Model model, String modelId, String system, Map<String, AgentTool> tools,
                    int maxIters, Compactor compact, Confirm confirm) {

    static final CacheControlEphemeral EPHEMERAL = CacheControlEphemeral.builder().build();

    public Agent {
        tools = tools == null ? Map.of() : Map.copyOf(tools);
        system = system == null ? "" : system;
    }

    public Agent withModel(Model m) {
        return new Agent(m, modelId, system, tools, maxIters, compact, confirm);
    }

    /** Запись траектории: по ним считают eval агента (модуль 8). */
    public record Step(int iter, String tool, String input, boolean isError, long ctxTokens) {}

    public static final class Result {
        public String text = "";
        public int iters;
        public final List<Step> steps = new ArrayList<>();
        public long peakCtx;
        public int compactions, compactErrors;
    }

    /** Ошибка прогона; несёт частичный результат: итерации, траекторию, пик контекста. */
    public static class AgentException extends Exception {
        public final transient Result result;

        public AgentException(String msg, Throwable cause, Result result) {
            super(msg, cause);
            this.result = result;
        }
    }

    public static final class MaxItersException extends AgentException {
        MaxItersException(int max, Result r) {
            super("agent: max iterations reached (" + max + ")", null, r);
        }
    }

    record Outcome(String out, boolean isError) {}

    public Result run(String task) throws AgentException {
        // Стабильный порядок = стабильный кэшируемый префикс.
        List<ToolUnion> defs = new TreeMap<>(tools).values().stream().map(t -> ToolUnion.ofTool(t.param())).toList();
        List<MessageParam> msgs = new ArrayList<>(List.of(userText(task)));
        var res = new Result();
        long ctxTok = 0; // размер контекста по usage последнего вызова

        for (int iter = 0; iter < maxIters; iter++) {
            res.iters = iter + 1;
            if (compact != null) {
                try {
                    var compacted = compact.maybe(msgs, ctxTok);
                    if (compacted.isPresent()) {
                        msgs = new ArrayList<>(compacted.get());
                        res.compactions++;
                    }
                } catch (RuntimeException e) {
                    res.compactErrors++; // история не тронута: работаем дальше, попробуем на следующем шаге
                }
            }
            Message resp;
            try {
                resp = model.create(MessageCreateParams.builder()
                        .model(modelId).maxTokens(16000)
                        // Breakpoint на system: tools и system остаются в кэше, даже когда компакция перепишет историю.
                        .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(system).cacheControl(EPHEMERAL).build()))
                        .tools(defs)
                        .messages(msgs)
                        // Автоматический breakpoint на последнем блоке: каждый шаг читает всю прошлую историю по 0,1×.
                        .cacheControl(EPHEMERAL)
                        .build());
            } catch (RuntimeException e) {
                throw new AgentException("iter " + iter + ": " + e.getMessage(), e, res); // BudgetExceededException тоже сюда
            }
            var u = resp.usage(); // с кэшем input_tokens — только хвост после breakpoint, складываем всё
            ctxTok = u.inputTokens() + u.cacheReadInputTokens().orElse(0L)
                    + u.cacheCreationInputTokens().orElse(0L) + u.outputTokens();
            res.peakCtx = Math.max(res.peakCtx, ctxTok);
            msgs.add(resp.toParam());

            StopReason stop = resp.stopReason().orElse(null);
            if (StopReason.END_TURN.equals(stop)) {
                res.text = text(resp);
                return res;
            } else if (StopReason.MAX_TOKENS.equals(stop)) {
                throw new AgentException("agent: response truncated (max_tokens)", null, res);
            } else if (!StopReason.TOOL_USE.equals(stop)) {
                throw new AgentException("agent: unexpected stop_reason " + stop, null, res);
            }

            List<ToolUseBlock> calls = resp.content().stream().filter(ContentBlock::isToolUse).map(ContentBlock::asToolUse).toList();
            // Параллельные tool_use: исполняем конкурентно на virtual threads, возвращаем ВСЕ результаты одним сообщением.
            List<ContentBlockParam> results = new ArrayList<>();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Outcome>> futures = calls.stream().map(c -> pool.submit(() -> execTool(c))).toList();
                for (int i = 0; i < calls.size(); i++) {
                    var call = calls.get(i);
                    Outcome o = futures.get(i).get();
                    results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(call.id()).content(o.out()).isError(o.isError()).build()));
                    res.steps.add(new Step(iter, call.name(), json(call._input()), o.isError(), ctxTok));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentException("interrupted", e, res);
            } catch (ExecutionException e) {
                throw new AgentException("tool executor", e.getCause(), res); // execTool не бросает: сюда не попадаем
            }
            msgs.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build());
        }
        throw new MaxItersException(maxIters, res);
    }

    Outcome execTool(ToolUseBlock call) {
        AgentTool t = tools.get(call.name());
        if (t == null) {
            return new Outcome("unknown tool \"" + call.name() + "\"", true);
        }
        String input = json(call._input());
        if (t.dangerous() && (confirm == null || !confirm.ask(call.name() + " " + input))) {
            return new Outcome("The user denied this action. Do not retry it; propose an alternative or finish.", true);
        }
        try {
            // Таймаут на попытку, а не на все.
            String out = Reliability.retry(3, () -> Reliability.withTimeout(Duration.ofSeconds(60), () -> t.run().run(input)));
            return new Outcome(truncate(out, 40_000, "\n…[truncated; narrow the query]"), false); // ≈10K токенов
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome("error: interrupted", true);
        } catch (Exception e) {
            return new Outcome("error: " + e.getMessage(), true); // модель увидит и попробует исправиться
        }
    }

    /**
     * Превращает агента в инструмент для родителя. Каждый вызов — свой список сообщений
     * (чистый контекст), свой бюджет поверх бюджета родителя, и наверх уходит только итог,
     * урезанный до maxResult символов: десятки тысяч токенов чтения остаются у субагента.
     */
    public AgentTool asTool(String name, String desc, double maxUsd, int maxResult) {
        return new AgentTool(AgentTool.def(name, desc,
                Map.of("task", Map.of("type", "string", "description",
                        "Self-contained task: goal, scope and boundaries, expected output format. The subagent sees nothing else.")),
                List.of("task")),
                input -> {
                    String task = ObjectMappers.jsonMapper().readTree(input).path("task").asText("");
                    if (task.isEmpty()) {
                        throw new IllegalArgumentException("pass a non-empty task");
                    }
                    // Лимит на этот вызов, расходы видит и родитель; историю run создаст с нуля.
                    Agent sub = withModel(new Budgeted(model, maxUsd));
                    try {
                        return truncate(sub.run(task).text, maxResult, "\n…[truncated]");
                    } catch (AgentException e) {
                        throw new IllegalStateException("subagent stopped after %d iterations: %s; narrow the task"
                                .formatted(e.result.iters, e.getMessage()), e);
                    }
                });
    }

    static MessageParam userText(String text) {
        return MessageParam.builder().role(MessageParam.Role.USER).content(text).build();
    }

    static String text(Message m) {
        var sb = new StringBuilder();
        m.content().stream().filter(ContentBlock::isText).forEach(b -> sb.append(b.asText().text()));
        return sb.toString();
    }

    static String json(Object v) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Режет по границе символа: обрезанная посреди суррогатной пары строка — мусор в контексте. */
    static String truncate(String s, int n, String tail) {
        if (s.length() <= n) {
            return s;
        }
        if (n > 0 && Character.isLowSurrogate(s.charAt(n))) {
            n--;
        }
        return s.substring(0, n) + tail;
    }
}
