package course.m07security;

import course.m07security.Guard.Blocked;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Middleware вокруг агента, как servlet Filter вокруг сервлета.
 * input — ввод пользователя: блок → вежливый отказ;
 * docs — недоверенный контент: блок → документ выбрасывается, ответ строится без него;
 * output — ответ: блок → отказ, иначе — переписанный текст.
 */
public record Guarded(Agent next, List<Guard> input, List<Guard> docs, List<Guard> output) implements Agent {
    static final String REFUSAL = "Не могу помочь с этим запросом.";
    private static final System.Logger LOG = System.getLogger("guard");

    @Override
    public Trace run(Input in) throws IOException {
        String prompt;
        try {
            prompt = Guard.runChain(input, in.prompt());
        } catch (Blocked b) {
            return refuse(new Trace("", List.of(), false), b);
        }
        List<String> clean = new ArrayList<>(in.docs().size());
        for (String d : in.docs()) {
            try {
                clean.add(Guard.runChain(docs, d));
            } catch (Blocked b) {
                LOG.log(System.Logger.Level.WARNING, "doc dropped: {0}", b.getMessage());
            }
            // UnavailableException летит выше: fail-closed, инфраструктурная ошибка, не «атака отбита»
        }
        Trace tr = next.run(new Input(prompt, clean));
        try {
            return new Trace(Guard.runChain(output, tr.output()), tr.tools(), false);
        } catch (Blocked b) {
            // Инструменты уже отработали: output guard не отменяет побочных эффектов,
            // поэтому контроль действий стоит в Registry, до исполнения.
            return refuse(tr, b);
        }
    }

    private static Trace refuse(Trace tr, Blocked b) {
        LOG.log(System.Logger.Level.INFO, "guardrail refusal: {0}", b.getMessage());
        return new Trace(REFUSAL, tr.tools(), true);
    }
}
