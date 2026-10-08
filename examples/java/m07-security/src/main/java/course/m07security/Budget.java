package course.m07security;

/**
 * Считает расход одного запроса. Запрос обслуживает один поток;
 * для параллельных tool calls замените счётчики на AtomicInteger.
 */
public final class Budget {
    /** maxSteps — вызовов модели и инструментов на один запрос; maxTokens — input + output за весь запрос. */
    public record Limits(int maxSteps, int maxTokens) {}

    public static final class ExceededException extends Exception {
        ExceededException(String message) {
            super("request budget exceeded: " + message);
        }
    }

    private final Limits limits;
    private int steps, tokens;

    public Budget(Limits limits) {
        this.limits = limits;
    }

    public void step() throws ExceededException {
        if (++steps > limits.maxSteps()) {
            throw new ExceededException("steps %d > %d".formatted(steps, limits.maxSteps()));
        }
    }

    public void spend(int n) throws ExceededException {
        tokens += n;
        if (tokens > limits.maxTokens()) {
            throw new ExceededException("tokens %d > %d".formatted(tokens, limits.maxTokens()));
        }
    }
}
