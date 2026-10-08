package course.m16voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import course.m16voice.Fakes.FakeLlm;
import course.m16voice.Fakes.FakeStt;
import course.m16voice.Fakes.FakeTts;
import course.m16voice.Fakes.SleepPlayer;
import course.m16voice.Voice.Chunk;
import course.m16voice.Voice.Message;
import course.m16voice.Voice.Sink;
import course.m16voice.Voice.Timings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

// В Java нет аналога testing/synctest: время настоящее, поэтому латентность проверяется с допуском,
// а зависший после отмены поток ловит @Timeout (turn() дожидается всех своих потоков).
@Timeout(5)
class VoiceTest {
    static final long TOLERANCE_MS = 25; // Thread.sleep и планировщик добавляют миллисекунды

    static Duration ms(long v) {
        return Duration.ofMillis(v);
    }

    /** Имитирует фразу пользователя: n кадров, затем закрытие канала (VAD: речь кончилась). */
    static Chan<byte[]> speak(int n) throws InterruptedException {
        var ch = new Chan<byte[]>();
        for (int i = 0; i < n; i++) ch.send(new byte[640]); // 20 мс PCM 16 кГц
        ch.close();
        return ch;
    }

    static void assertNear(long wantMs, Duration got, String what) {
        assertTrue(Math.abs(got.toMillis() - wantMs) <= TOLERANCE_MS, what + " = " + got.toMillis() + " мс, want ≈ " + wantMs);
    }

    @Test
    void turnStreamsBySentence() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>(); // фразы, отданные на синтез
        var fakeTts = new FakeTts(ms(15), ms(1));
        Voice.Tts tts = (String text, Sink<Chunk> out) -> {
            got.add(text);
            fakeTts.stream(text, out);
        };
        var a = new Agent(
                new FakeStt("где мой заказ", ms(30)),
                new FakeLlm("Заказ в пути. Привезём завтра до обеда.", ms(40), ms(20)),
                tts, new SleepPlayer());
        Timings tm = a.turn(speak(5), null);

        // endpoint 30 → TTFT +40 → первая фраза (3 слова) +2×20 → TTFA +15.
        // Без разбиения на фразы звук начался бы на 30+40+6×20+15 = 205 мс.
        assertNear(30, tm.stt(), "STT");
        assertNear(70, tm.llm(), "LLM");
        assertNear(125, tm.tts(), "TTS");
        assertNear(125, tm.audio(), "Audio");
        assertEquals(List.of("Заказ в пути.", "Привезём завтра до обеда."), got);
        assertEquals(List.of(new Message("user", "где мой заказ"),
                new Message("assistant", "Заказ в пути. Привезём завтра до обеда.")), a.history);
    }

    @Test
    void bargeInTruncatesHistory() throws Exception {
        var bargeIn = new CountDownLatch(1);
        // Доигрывает 4 чанка, а на следующем «слышит» пользователя и ждёт прерывания.
        Voice.Player player = new Voice.Player() {
            int n;

            @Override
            public void play(Chunk c) throws InterruptedException {
                if (n == 4) {
                    bargeIn.countDown();
                    Thread.sleep(Long.MAX_VALUE);
                }
                n++;
            }
        };
        var a = new Agent(
                new FakeStt("когда доставка", ms(1)),
                new FakeLlm("Доставка в четверг с десяти до двух. Могу перенести на пятницу.", ms(1), Duration.ZERO),
                new FakeTts(ms(1), ms(1)), player);
        Timings tm = a.turn(speak(3), bargeIn);

        assertTrue(tm.interrupted(), "interrupted = false");
        assertEquals(new Message("assistant", "Доставка в четверг с…", true), a.history.getLast());
    }

    @Test
    void bargeInBeforeAudio() throws Exception {
        var bargeIn = new CountDownLatch(0); // пользователь продолжил говорить, пока LLM думала
        var a = new Agent(
                new FakeStt("алло", ms(1)),
                new FakeLlm("Слушаю вас.", Duration.ofSeconds(1), Duration.ZERO),
                new FakeTts(Duration.ZERO, Duration.ZERO), new SleepPlayer());
        long start = System.nanoTime();
        Timings tm = a.turn(speak(1), bargeIn);

        assertTrue(tm.interrupted());
        long took = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(took < 500, "ход длился " + took + " мс: отмена не остановила LLM");
        assertEquals(1, a.history.size(), () -> "ответ не прозвучал, но попал в историю: " + a.history);
    }

    @Test
    void noSpeech() {
        var a = new Agent(new FakeStt("", Duration.ZERO), new FakeLlm("", Duration.ZERO, Duration.ZERO),
                new FakeTts(Duration.ZERO, Duration.ZERO), new SleepPlayer());
        assertThrows(Voice.NoSpeechException.class, () -> a.turn(speak(0), null));
    }

    @Test
    void percentile() {
        var ds = new ArrayList<Duration>();
        for (int i = 100; i >= 1; i--) ds.add(ms(i));
        assertEquals(ms(50), Voice.percentile(ds, 50));
        assertEquals(ms(95), Voice.percentile(ds, 95));
        assertEquals(Duration.ZERO, Voice.percentile(new ArrayList<>(), 95));
        assertEquals(ms(7), Voice.percentile(new ArrayList<>(List.of(ms(7))), 0));
    }
}
