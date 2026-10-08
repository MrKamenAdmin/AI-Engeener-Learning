package course.m16voice;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextDelta;
import course.m16voice.Voice.Message;
import course.m16voice.Voice.Sink;
import java.util.Iterator;
import java.util.List;

/**
 * LLM-звено каскада поверх стримингового Messages API.
 * model: в голосе важнее TTFT — маленькая модель, например "claude-haiku-4-5".
 */
public record ClaudeLlm(AnthropicClient client, String model) implements Voice.Llm {

    /** Ответ озвучит TTS: формат и длина — часть голосового UX. */
    public static final String VOICE_SYSTEM = """
            Ты голосовой ассистент. Ответ будет озвучен синтезатором речи, поэтому:
            без markdown, списков и ссылок; одна-три короткие фразы; первая фраза — сразу по делу;
            числа и даты пиши так, как их произносят. Если нужен уточняющий вопрос — задай один.""";

    @Override
    public void stream(List<Message> history, Sink<String> out) throws InterruptedException {
        var params = MessageCreateParams.builder().model(model).maxTokens(300).system(VOICE_SYSTEM);
        for (Message m : history) {
            if (m.role().equals("assistant")) {
                params.addAssistantMessage(m.text());
            } else {
                params.addUserMessage(m.text()); // подряд идущие user-сообщения API склеит
            }
        }
        // try-with-resources закрывает HTTP-стрим и при barge-in: генерация останавливается.
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params.build())) {
            Iterator<RawMessageStreamEvent> it = stream.stream().iterator();
            while (it.hasNext()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                var text = it.next().contentBlockDelta().flatMap(d -> d.delta().text()).map(TextDelta::text);
                if (text.isPresent()) out.send(text.get());
            }
        }
    }
}
