package course.m16voice;

import course.m16voice.Voice.Chunk;
import course.m16voice.Voice.Llm;
import course.m16voice.Voice.Message;
import course.m16voice.Voice.Player;
import course.m16voice.Voice.Sink;
import course.m16voice.Voice.Stt;
import course.m16voice.Voice.Transcript;
import course.m16voice.Voice.Tts;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Фейковые провайдеры с настраиваемыми задержками: тесты и симуляция без сети и ключей. */
public final class Fakes {
    private Fakes() {}

    static String[] fields(String s) {
        return s.isBlank() ? new String[0] : s.strip().split("\\s+");
    }

    /** Отдаёт частичную гипотезу на каждый кадр и финал через endpoint после конца аудио. */
    public record FakeStt(String text, Duration endpoint) implements Stt {
        @Override
        public void stream(Chan<byte[]> audio, Sink<Transcript> out) throws InterruptedException {
            String[] words = fields(text);
            int n = 0;
            while (audio.receive() != null) {
                if (n < words.length) {
                    n++;
                    out.send(new Transcript(String.join(" ", Arrays.copyOf(words, n)), false));
                }
            }
            Thread.sleep(endpoint); // ожидание тишины + финализация гипотезы
            out.send(new Transcript(text, true));
        }
    }

    /** Печатает reply по словам: первое через ttft, остальные через perWord. */
    public record FakeLlm(String reply, Duration ttft, Duration perWord) implements Llm {
        @Override
        public void stream(List<Message> history, Sink<String> out) throws InterruptedException {
            Thread.sleep(ttft);
            String[] words = reply.split("(?<= )"); // как strings.SplitAfter: пробел остаётся у слова
            for (int i = 0; i < words.length; i++) {
                if (i > 0) Thread.sleep(perWord);
                out.send(words[i]);
            }
        }
    }

    /** Синтезирует фразу по словам: первый чанк через ttfa, каждый звучит perWord. */
    public record FakeTts(Duration ttfa, Duration perWord) implements Tts {
        @Override
        public void stream(String text, Sink<Chunk> out) throws InterruptedException {
            Thread.sleep(ttfa);
            for (String w : fields(text)) {
                out.send(new Chunk(new byte[0], w, perWord));
            }
        }
    }

    /** «Играет» чанк, выжидая его длительность, — динамик для тестов и симуляции. */
    public record SleepPlayer() implements Player {
        @Override
        public void play(Chunk c) throws InterruptedException {
            Thread.sleep(c.dur());
        }
    }
}
