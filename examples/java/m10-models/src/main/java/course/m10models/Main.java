// mvn -q -pl m10-models compile exec:java -Dexec.mainClass=course.m10models.Main \
//   -Dexec.args='--upstream http://localhost:8000 --models clf=ticket-clf,chat=Qwen/Qwen3-8B --max-inflight 128'
// Локально без GPU: --upstream http://localhost:11434 --models chat=qwen3:8b (Ollama).
package course.m10models;

import com.sun.net.httpserver.HttpServer;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

public final class Main {
    public static void main(String[] args) throws Exception {
        Map<String, String> flags = new HashMap<>(Map.of(
                "--listen", ":8080",                       // адрес шлюза
                "--upstream", "http://localhost:8000",     // vLLM или Ollama
                "--models", "chat=Qwen/Qwen3-8B",          // публичное=имя_в_vLLM через запятую
                "--max-inflight", "128"));                 // ≈ --max-num-seqs × реплики
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!flags.containsKey(args[i])) {
                throw new IllegalArgumentException("unknown flag " + args[i]);
            }
            flags.put(args[i], args[i + 1]);
        }

        Map<String, String> models = new HashMap<>();
        for (String kv : flags.get("--models").split(",")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2) {
                models.put(p[0], p[1]);
            }
        }
        var log = System.getLogger("gateway");
        var gw = new Gateway(new Gateway.Config(
                flags.get("--upstream"), Objects.requireNonNullElse(System.getenv("VLLM_API_KEY"), ""), models,
                Integer.parseInt(flags.get("--max-inflight")), Duration.ofSeconds(2),
                Duration.ofSeconds(10), Duration.ofMinutes(5), 2, Duration.ofMillis(200),
                s -> log.log(Level.INFO, "llm model={0} status={1} attempts={2} ttft_ms={3} tpot_ms={4} out_tokens={5}",
                        s.model, s.status, s.attempts, s.ttft.toMillis(), s.tpot.toMillis(), s.outTokens)));

        String listen = flags.get("--listen");
        int colon = listen.lastIndexOf(':');
        String host = listen.substring(0, colon);
        int port = Integer.parseInt(listen.substring(colon + 1));
        var srv = HttpServer.create(host.isEmpty() ? new InetSocketAddress(port) : new InetSocketAddress(host, port), 0);
        srv.createContext("/", gw);
        srv.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); // поток на запрос: стримы держат соединение минутами
        srv.start();
        log.log(Level.INFO, "gateway listen={0} upstream={1} models={2}", listen, flags.get("--upstream"), models);
    }
}
