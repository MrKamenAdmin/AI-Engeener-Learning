package course.m04tools;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** Stop-хук Claude Code: см. {@link AgentGate}. Базовая ветка — AGENTGATE_BASE, по умолчанию main. */
public final class Main {
    public static void main(String[] args) {
        String base = System.getenv("AGENTGATE_BASE");
        if (base == null || base.isEmpty()) {
            base = "main";
        }
        var stderr = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
        int code = AgentGate.gate(Main::exec, base, stderr);
        stderr.flush();
        System.exit(code);
    }

    static AgentGate.Out exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new AgentGate.Out(out, p.waitFor());
        } catch (IOException e) {
            return new AgentGate.Out(e.getMessage(), -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AgentGate.Out("interrupted", -1);
        }
    }
}
