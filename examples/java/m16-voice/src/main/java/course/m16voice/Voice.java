package course.m16voice;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Типы голосового каскада STT → LLM → TTS (модуль 16).
 *
 * <p>Контракт провайдеров: метод stream блокирует поток, пока не отдаст всё в sink, и
 * завершается по {@link InterruptedException} при отмене. Выходной канал закрывает агент,
 * не провайдер. Провайдер вызывается на отдельном виртуальном потоке.
 */
public final class Voice {
    private Voice() {}

    /** interrupted — ответ оборван пользователем: в text только произнесённое. */
    public record Message(String role, String text, boolean interrupted) {
        public Message(String role, String text) {
            this(role, text, false);
        }
    }

    /** isFinal == false — частичная гипотеза, ещё может измениться. */
    public record Transcript(String text, boolean isFinal) {}

    /**
     * Кусок синтезированного звука и текст, который в нём звучит
     * (выравнивание: многие TTS-API отдают таймкоды слов или символов).
     */
    public record Chunk(byte[] audio, String text, Duration dur) {}

    /** Латентность, отсчитанная от конца речи пользователя. */
    public record Timings(
            Duration stt,   // финальный транскрипт (endpointing + финализация)
            Duration llm,   // первый токен ответа
            Duration tts,   // первый чанк синтезированного звука
            Duration audio, // начало воспроизведения: voice-to-voice без сети и аудиостека
            boolean interrupted) {}

    @FunctionalInterface
    public interface Sink<T> {
        void send(T v) throws InterruptedException;
    }

    public interface Stt {
        void stream(Chan<byte[]> audio, Sink<Transcript> out) throws Exception;
    }

    public interface Llm {
        void stream(List<Message> history, Sink<String> out) throws Exception;
    }

    public interface Tts {
        void stream(String text, Sink<Chunk> out) throws Exception;
    }

    /** Проигрывает чанк и возвращает управление, только если чанк прозвучал целиком. */
    public interface Player {
        void play(Chunk c) throws InterruptedException;
    }

    public static class VoiceException extends Exception {
        public VoiceException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    public static final class NoSpeechException extends VoiceException {
        public NoSpeechException(Throwable cause) {
            super("voice: нет финального транскрипта", cause);
        }
    }

    /** Перцентиль методом ближайшего ранга (p в процентах); сортирует ds на месте. */
    public static Duration percentile(List<Duration> ds, double p) {
        if (ds.isEmpty()) {
            return Duration.ZERO;
        }
        Collections.sort(ds);
        int i = (int) Math.ceil(p / 100 * ds.size()) - 1;
        return ds.get(Math.max(i, 0));
    }
}
