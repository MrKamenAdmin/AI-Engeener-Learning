package course.m07security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shell: никакого sh -c. Разрешённая команда: фиксированный бинарь и флаги, аргументы модели —
 * только позиционные. path — абсолютный путь: не ищем в $PATH, который мог подменить кто-то ещё.
 * arg проверяется через matches(), то есть целиком, а не как подстрока.
 */
public record Command(String path, List<String> fixed, Pattern arg, int maxArgs) {

    /**
     * Проверяет аргументы и собирает ProcessBuilder без shell: ; | $() и кавычки остаются
     * обычными символами. «--» перед аргументами не даёт выдать их за флаги.
     */
    public ProcessBuilder build(List<String> args) throws NotAllowedException {
        if (args.isEmpty() || args.size() > maxArgs) {
            throw new NotAllowedException(args.size() + " args");
        }
        for (String a : args) {
            if (a.startsWith("-") || a.contains("..") || !arg.matcher(a).matches()) {
                throw new NotAllowedException("arg \"" + a + "\"");
            }
        }
        List<String> argv = new ArrayList<>();
        argv.add(path);
        argv.addAll(fixed);
        argv.add("--");
        argv.addAll(args);
        var pb = new ProcessBuilder(argv);
        pb.environment().clear(); // никаких секретов из окружения сервиса
        pb.environment().put("PATH", "/usr/bin:/bin");
        pb.environment().put("LANG", "C.UTF-8");
        return pb;
    }
}
