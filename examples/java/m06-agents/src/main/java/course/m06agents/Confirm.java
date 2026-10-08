package course.m06agents;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/** Спрашивает человека. Вызовы из параллельных инструментов сериализуются. */
@FunctionalInterface
public interface Confirm {
    boolean ask(String action);

    static Confirm stdin() {
        var in = new BufferedReader(new InputStreamReader(System.in));
        return action -> {
            synchronized (in) {
                System.err.printf("Агент хочет выполнить:%n  %s%nРазрешить? [y/N] ", action);
                try {
                    String line = in.readLine();
                    return line != null && line.strip().equalsIgnoreCase("y");
                } catch (IOException e) {
                    return false;
                }
            }
        };
    }
}
