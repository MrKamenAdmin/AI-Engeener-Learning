package course.m07security;

import java.io.IOException;
import java.util.List;

/** То, что видит пользователь (и red-team раннер): вход → ответ и сделанные действия. */
public interface Agent {
    /** IOException — инфраструктурная ошибка (сеть, таймаут, сломанный guard), а не отказ по политике. */
    Trace run(Input in) throws IOException;

    /** docs — недоверенный контент, найденный retrieval или пришедший из инструментов. */
    record Input(String prompt, List<String> docs) {
        public Input {
            docs = docs == null ? List.of() : List.copyOf(docs);
        }
    }

    /** tools — успешно исполненные инструменты. */
    record Trace(String output, List<String> tools, boolean refused) {
        public Trace {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }
}
