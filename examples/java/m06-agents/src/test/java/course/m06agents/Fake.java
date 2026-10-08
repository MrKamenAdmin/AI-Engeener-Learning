package course.m06agents;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Модель без сети. reply получает номер вызова и запрос, возвращает кусок ответа в JSON формата
 * Messages API. input_tokens fake считает сам (≈ байты/4), как это сделал бы API, и проверяет
 * инварианты, на которых API ответил бы 400.
 */
final class Fake implements Model {
    final List<MessageCreateParams> reqs = new ArrayList<>();
    volatile BiFunction<Integer, MessageCreateParams, String> reply;
    private int n;

    Fake(BiFunction<Integer, MessageCreateParams, String> reply) {
        this.reply = reply;
    }

    @Override
    public Message create(MessageCreateParams p) {
        String err = validate(p.messages());
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        int k;
        synchronized (this) {
            k = n++;
            reqs.add(p);
        }
        String raw = Agent.json(p.messages());
        String body = "{\"id\":\"msg_%d\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"%s\",%s,\"usage\":{\"input_tokens\":%d,\"output_tokens\":50}}"
                .formatted(k, p.model().asString(), reply.apply(k, p), 1500 + raw.length() / 4);
        try {
            return ObjectMappers.jsonMapper().readValue(body, Message.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Первое сообщение от user, роли чередуются, каждый tool_result отвечает на tool_use
     * из предыдущего сообщения, и на каждый tool_use есть tool_result в следующем.
     */
    static String validate(List<MessageParam> msgs) {
        if (msgs.isEmpty() || !MessageParam.Role.USER.equals(msgs.get(0).role())) {
            return "400: first message must be user";
        }
        for (int i = 0; i < msgs.size(); i++) {
            if (i > 0 && msgs.get(i).role().equals(msgs.get(i - 1).role())) {
                return "400: roles must alternate at " + i;
            }
            Set<String> uses = new HashSet<>();
            if (i > 0) {
                for (ContentBlockParam b : Compactor.blocks(msgs.get(i - 1))) {
                    if (b.isToolUse()) {
                        uses.add(b.asToolUse().id());
                    }
                }
            }
            for (ContentBlockParam b : Compactor.blocks(msgs.get(i))) {
                if (b.isToolResult()) {
                    String id = b.asToolResult().toolUseId();
                    if (!uses.remove(id)) {
                        return "400: orphan tool_result " + id + " at " + i;
                    }
                }
            }
            if (i > 0 && !uses.isEmpty() && MessageParam.Role.USER.equals(msgs.get(i).role())) {
                return "400: tool_use without tool_result before " + i;
            }
        }
        return null;
    }

    static String toolUse(String id, String name, String input) {
        return "\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"text\",\"text\":\"step %s\"},{\"type\":\"tool_use\",\"id\":\"%s\",\"name\":\"%s\",\"input\":%s}]"
                .formatted(id, id, name, input);
    }

    static String endTurn(String text) {
        return "\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":%s}]".formatted(Agent.json(text));
    }

    static String firstText(MessageParam m, int block) {
        return Compactor.blocks(m).get(block).asText().text();
    }
}
