package course.m16voice;

import course.m16voice.Fakes.FakeLlm;
import course.m16voice.Fakes.FakeStt;
import course.m16voice.Fakes.FakeTts;
import course.m16voice.Fakes.SleepPlayer;
import course.m16voice.Voice.Timings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Прогоняет N голосовых ходов параллельно на фейковых провайдерах со случайными задержками
 * и печатает p50/p95 по этапам. Задержки условные: подставьте свои замеры, чтобы получить свой бюджет.
 *
 * <pre>
 * mvn -q -pl m16-voice compile exec:java -Dexec.mainClass=course.m16voice.Simulate -Dexec.args="-n 200"
 * </pre>
 */
public final class Simulate {
    static Duration ms(long v) {
        return Duration.ofMillis(v);
    }

    public static void main(String[] args) throws InterruptedException {
        int n = args.length == 2 && args[0].equals("-n") ? Integer.parseInt(args[1]) : 200;

        List<Timings> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    var rnd = ThreadLocalRandom.current();
                    long ttft = 300 + rnd.nextLong(400);
                    if (rnd.nextDouble() < 0.05) {
                        ttft += 1500; // хвост: перегрузка провайдера, длинный промпт без кэша
                    }
                    var a = new Agent(
                            new FakeStt("когда приедет курьер", ms(300 + rnd.nextLong(300))),
                            new FakeLlm("Курьер будет с двух до четырёх. Позвонит за полчаса.", ms(ttft), ms(15)),
                            new FakeTts(ms(80 + rnd.nextLong(120)), ms(300)),
                            new SleepPlayer());
                    var audio = new Chan<byte[]>();
                    for (int f = 0; f < 50; f++) audio.send(new byte[0]); // 1 с речи кадрами по 20 мс
                    audio.close();
                    try {
                        Timings tm = a.turn(audio, null);
                        synchronized (results) {
                            results.add(tm);
                        }
                    } catch (Voice.VoiceException e) {
                        System.out.println("ход: " + e.getMessage());
                    }
                    return null;
                });
            }
        } // close() ждёт все задачи

        System.out.printf("%d ходов, мс от конца речи пользователя%n%-26s %6s %6s%n", results.size(), "этап", "p50", "p95");
        Map<String, Function<Timings, Duration>> stages = new LinkedHashMap<>();
        stages.put("STT: финальный транскрипт", Timings::stt);
        stages.put("LLM: первый токен", Timings::llm);
        stages.put("TTS: первый чанк", Timings::tts);
        stages.put("начало воспроизведения", Timings::audio);
        stages.forEach((name, f) -> {
            List<Duration> ds = new ArrayList<>(results.stream().map(f).toList());
            System.out.printf("%-26s %6d %6d%n", name, Voice.percentile(ds, 50).toMillis(), Voice.percentile(ds, 95).toMillis());
        });
        System.out.println("К этому добавьте сеть и аудиостек клиента (микрофон, кодек, jitter buffer, динамик).");
    }
}
