package course.m06agents;

/** Короткие сессии со свежим контекстом и проверкой прогресса кодом. */
public final class Sessions {
    private Sessions() {}

    /**
     * Состояние длинной задачи по мнению кода, а не модели: прогон тестов, число пунктов
     * с passes=true в features.json, валидность артефактов. progress — отпечаток прогресса
     * (например, «37/52 + хэш HEAD»), по нему видно, что сессия что-то сдвинула.
     */
    public record Progress(boolean done, String progress) {}

    @FunctionalInterface
    public interface Check {
        Progress check() throws Exception;
    }

    public static final class StuckException extends Exception {
        StuckException(int sessions) {
            super("agent: no progress in consecutive sessions (after " + sessions + ")");
        }
    }

    /**
     * Крутит короткие сессии агента со свежим контекстом, пока check не скажет «готово».
     * Между сессиями состояние живёт только в файлах (progress, notes) и git, поэтому обрыв
     * процесса стоит максимум одной сессии: перезапуск продолжает с того же места.
     * Возвращает число проведённых сессий.
     */
    public static int runSessions(Agent a, String task, Check check, int maxSessions, int patience) throws Exception {
        String last = "";
        int idle = 0;
        for (int s = 0; s < maxSessions; s++) {
            Progress p = check.check();
            if (p.done()) {
                return s;
            }
            if (s > 0 && p.progress().equals(last)) {
                if (++idle >= patience) {
                    throw new StuckException(s); // сессии жгут бюджет, не двигая задачу: зовём человека
                }
            } else {
                idle = 0;
            }
            last = p.progress();
            try {
                a.run(task);
            } catch (Agent.MaxItersException e) {
                // Лимит итераций — нормальный конец сессии: следующая продолжит по файлам.
            } // остальные AgentException (бюджет, недоступный API) — стоп, летят наверх
        }
        if (!check.check().done()) {
            throw new IllegalStateException("agent: session limit reached, task not done");
        }
        return maxSessions;
    }
}
