package course.m06agents;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Сжимает историю, когда контекст перерос порог: старые ходы заменяет резюме от дешёвой
 * модели, последние keepTurns ходов оставляет дословно.
 *
 * @param model     тот же Budgeted, что у агента: суммаризация тоже стоит денег
 * @param modelId   "claude-haiku-4-5"
 * @param threshold токенов контекста; 50–70% рабочего бюджета, а не 95% окна
 * @param keepTurns ход = ответ модели с tool_use + сообщение с tool_result
 */
public record Compactor(Model model, String modelId, long threshold, int keepTurns) {

    static final String SUMMARY_PROMPT = """
            You compact the working history of an AI agent so that it can continue the task.
            Preserve: the task and its acceptance criteria; decisions made and why; what is done and verified;
            exact file paths, IDs, names and numbers; errors met and how they were resolved; open questions;
            the immediate next step. Drop raw tool outputs that can be fetched again. At most 500 words.""";

    /**
     * Вызывается перед каждым вызовом модели. ctxTokens — размер контекста по usage прошлого вызова.
     * Возвращает новую историю или empty, если компакции не было. Бросает исключение, если
     * суммаризация не удалась: история при этом не тронута.
     */
    public Optional<List<MessageParam>> maybe(List<MessageParam> msgs, long ctxTokens) {
        if (ctxTokens < threshold) {
            return Optional.empty();
        }
        // Хвост должен начинаться с ответа модели: тогда каждая пара tool_use → tool_result
        // целиком либо в резюме, либо в хвосте, и история чередует user/assistant.
        int cut = Math.min(msgs.size() - 1, msgs.size() - 2 * keepTurns);
        while (cut > 1 && !MessageParam.Role.ASSISTANT.equals(msgs.get(cut).role())) {
            cut--;
        }
        if (cut <= 1) {
            return Optional.empty(); // сжимать нечего: всё — «последние ходы»
        }
        Message resp = model.create(MessageCreateParams.builder()
                .model(modelId == null ? "claude-haiku-4-5" : modelId).maxTokens(4000)
                .system(SUMMARY_PROMPT)
                .addUserMessage(render(msgs.subList(0, cut)))
                .build());
        String summary = Agent.text(resp);
        StopReason stop = resp.stopReason().orElse(null);
        if (!StopReason.END_TURN.equals(stop) || summary.isEmpty()) {
            // Обрезанное или пустое резюме хуже никакого: история заменилась бы дырой.
            throw new IllegalStateException("compact: no summary (stop_reason " + stop + ")");
        }
        // Первый блок первого сообщения — исходная задача: её не пересказываем, а держим дословно.
        var head = MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(List.of(
                blocks(msgs.get(0)).get(0),
                ContentBlockParam.ofText("<summary_of_earlier_work>\n" + summary + "\n</summary_of_earlier_work>\n"
                        + "Earlier turns were compacted. Re-read your notes before acting; "
                        + "re-fetch files you need instead of relying on memory.")))
                .build();
        List<MessageParam> out = new ArrayList<>(List.of(head));
        msgs.subList(cut, msgs.size()).forEach(m -> out.add(withoutThinking(m)));
        return Optional.of(out);
    }

    static List<ContentBlockParam> blocks(MessageParam m) {
        var c = m.content();
        return c.isString() ? List.of(ContentBlockParam.ofText(c.asString())) : c.asBlockParams();
    }

    /**
     * Убирает thinking-блоки из сохранённых ходов: их подпись привязана к прежнему префиксу,
     * а мы его заменили резюме. Убрать все — допустимо, модель лишь теряет те рассуждения;
     * оставить — ошибка на моделях с проверкой префикса.
     */
    static MessageParam withoutThinking(MessageParam m) {
        var kept = blocks(m).stream().filter(b -> !b.isThinking() && !b.isRedactedThinking()).toList();
        return m.toBuilder().contentOfBlockParams(kept).build();
    }

    /**
     * Превращает историю в текст для суммаризатора: ему не нужны блоки tool_use
     * и определения инструментов, а длинные выводы заодно обрезаются.
     */
    static String render(List<MessageParam> msgs) {
        var b = new StringBuilder();
        for (var m : msgs) {
            for (var blk : blocks(m)) {
                if (blk.isText()) {
                    b.append("[%s] %s%n".formatted(m.role().asString(), blk.asText().text()));
                } else if (blk.isToolUse()) {
                    var tu = blk.asToolUse();
                    b.append("[tool_use %s] %s%n".formatted(tu.name(), Agent.json(tu._input())));
                } else if (blk.isToolResult()) {
                    var tr = blk.asToolResult();
                    String tag = tr.isError().orElse(false) ? "tool_error" : "tool_result";
                    tr.content().ifPresent(c -> {
                        List<String> texts = c.isString() ? List.of(c.asString())
                                : c.asBlocks().stream().filter(x -> x.isText()).map(x -> x.asText().text()).toList();
                        texts.forEach(t -> b.append("[%s] %s%n".formatted(tag, Agent.truncate(t, 2000, " …"))));
                    });
                }
            }
        }
        return b.toString();
    }
}
