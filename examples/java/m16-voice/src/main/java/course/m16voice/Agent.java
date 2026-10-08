package course.m16voice;

import course.m16voice.Voice.Chunk;
import course.m16voice.Voice.Llm;
import course.m16voice.Voice.Message;
import course.m16voice.Voice.NoSpeechException;
import course.m16voice.Voice.Player;
import course.m16voice.Voice.Stt;
import course.m16voice.Voice.Timings;
import course.m16voice.Voice.Transcript;
import course.m16voice.Voice.Tts;
import course.m16voice.Voice.VoiceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Голосовой ход каскадом STT → LLM → TTS на виртуальных потоках и очередях: стриминг на каждом шаге,
 * замер латентности по этапам и barge-in через прерывание потоков с обрезкой истории
 * до фактически произнесённого.
 */
public final class Agent {
    private final Stt stt;
    private final Llm llm;
    private final Tts tts;
    private final Player player;
    public final List<Message> history = new ArrayList<>();

    public Agent(Stt stt, Llm llm, Tts tts, Player player) {
        this.stt = stt;
        this.llm = llm;
        this.tts = tts;
        this.player = player;
    }

    /** Причина отмены «пользователь перебил» — не ошибка хода. */
    private static final Exception BARGE_IN = new Exception("voice: пользователь перебил", null, false, false) {};

    /**
     * Проводит один ход. audio закрывается, когда пользователь замолчал (VAD);
     * bargeIn (может быть null) срабатывает, когда пользователь заговорил во время ответа.
     */
    public Timings turn(Chan<byte[]> audio, CountDownLatch bargeIn) throws VoiceException, InterruptedException {
        var t = new Stamps();
        var spoken = new ArrayList<String>();
        boolean[] cut = {false}; // часть ответа не прозвучала
        Throwable cause;

        try (var scope = new Scope()) {
            // Пересылаем аудио в STT и запоминаем момент конца речи — точку отсчёта бюджета.
            var in = new Chan<byte[]>();
            scope.go(() -> {
                try {
                    for (byte[] f; (f = audio.receive()) != null; ) in.send(f);
                    t.ended = System.nanoTime();
                } finally {
                    in.close();
                }
            });
            var transcripts = new Chan<Transcript>();
            scope.go(() -> {
                try {
                    stt.stream(in, transcripts);
                } finally {
                    transcripts.close();
                }
            });
            String user = "";
            for (Transcript tr; (tr = transcripts.receive()) != null; ) {
                if (tr.isFinal()) {
                    user = tr.text();
                    break;
                }
            }
            t.stt = System.nanoTime();
            if (user.isEmpty()) {
                throw new NoSpeechException(scope.cause.get());
            }
            t.t0 = t.ended != 0 ? t.ended : t.stt; // финал мог прийти раньше конца аудио (eager endpointing)

            history.add(new Message("user", user));
            var snapshot = List.copyOf(history);

            // LLM → предложения: TTS стартует на первой фразе, а не на всём ответе.
            var sentences = new Chan<String>();
            scope.go(() -> {
                var buf = new StringBuilder();
                try {
                    llm.stream(snapshot, d -> {
                        if (t.llm == 0) t.llm = System.nanoTime();
                        buf.append(d);
                        int i = lastSentenceEnd(buf);
                        if (i >= 0) {
                            sentences.send(buf.substring(0, i + 1).strip());
                            buf.delete(0, i + 1);
                        }
                    });
                    if (!buf.isEmpty() && !buf.toString().isBlank() && !scope.cancelled()) {
                        sentences.send(buf.toString().strip());
                    }
                } finally {
                    sentences.close();
                }
            });

            // Barge-in: одна отмена прерывает LLM, TTS и воспроизведение сразу.
            if (bargeIn != null) {
                scope.go(() -> {
                    bargeIn.await();
                    scope.cancel(BARGE_IN);
                });
            }

            // Предложения → звук. Синтез идёт быстрее реального времени, очередь chunks
            // даёт TTS работать над следующей фразой, пока звучит текущая.
            var chunks = new Chan<Chunk>();
            scope.go(() -> {
                try {
                    for (String s; (s = sentences.receive()) != null; ) {
                        tts.stream(s, c -> {
                            if (t.tts == 0) t.tts = System.nanoTime();
                            chunks.send(c);
                        });
                    }
                } finally {
                    chunks.close();
                }
            });

            // Воспроизведение: недоигранный чанк в историю не попадает.
            Thread playback = scope.go(() -> {
                try {
                    for (Chunk c; (c = chunks.receive()) != null; ) {
                        if (t.audio == 0) t.audio = System.nanoTime();
                        player.play(c);
                        spoken.add(c.text().strip());
                    }
                } catch (InterruptedException e) {
                    cut[0] = true;
                }
            });
            playback.join();
            cause = scope.cause.get();
        } // close(): прерываем и дожидаемся всех потоков хода — утечек нет, поля t видны после join

        // В историю — только произнесённое: модель не должна «помнить», что сказала то,
        // чего пользователь не услышал.
        boolean interrupted = cause == BARGE_IN;
        String text = String.join(" ", spoken);
        if (!text.isEmpty()) {
            if (cut[0]) text += "…";
            history.add(new Message("assistant", text, cut[0]));
        }
        var tm = new Timings(t.since(t.stt), t.since(t.llm), t.since(t.tts), t.since(t.audio), interrupted);
        if (cause != null && !interrupted) {
            throw new VoiceException("voice: ход прерван ошибкой", cause);
        }
        return tm;
    }

    /** Индекс последнего конца предложения или -1. */
    // ponytail: наивно режет «т. е.» и «3.14»; настоящие агрегаторы знают сокращения и числа.
    static int lastSentenceEnd(CharSequence buf) {
        for (int i = buf.length() - 1; i >= 0; i--) {
            char ch = buf.charAt(i);
            if (ch == '.' || ch == '!' || ch == '?') return i;
        }
        return -1;
    }

    /** Отметки времени этапов (System.nanoTime, 0 — не наступило). */
    private static final class Stamps {
        volatile long ended, t0, stt, llm, tts, audio;

        Duration since(long at) {
            return at == 0 ? Duration.ZERO : Duration.ofNanos(at - t0);
        }
    }

    @FunctionalInterface
    interface Task {
        void run() throws Exception;
    }

    /**
     * Потоки одного хода с общей отменой — аналог context.WithCancelCause.
     * Первая причина запоминается, все потоки прерываются; close() прерывает оставшиеся и ждёт их.
     * (StructuredTaskScope в Java 21 ещё preview, поэтому вручную.)
     */
    private static final class Scope implements AutoCloseable {
        final AtomicReference<Throwable> cause = new AtomicReference<>();
        private final List<Thread> threads = new CopyOnWriteArrayList<>();

        Thread go(Task task) {
            Thread th = Thread.ofVirtual().unstarted(() -> {
                try {
                    task.run();
                } catch (InterruptedException e) {
                    // отмена: выходим молча, причина уже в cause
                } catch (Exception e) {
                    cancel(e);
                }
            });
            threads.add(th);
            th.start();
            if (cancelled()) th.interrupt(); // отмена могла случиться до добавления в список
            return th;
        }

        boolean cancelled() {
            return cause.get() != null;
        }

        void cancel(Throwable c) {
            if (cause.compareAndSet(null, c)) threads.forEach(Thread::interrupt);
        }

        @Override
        public void close() throws InterruptedException {
            threads.forEach(Thread::interrupt);
            for (Thread th : threads) th.join();
        }
    }
}
