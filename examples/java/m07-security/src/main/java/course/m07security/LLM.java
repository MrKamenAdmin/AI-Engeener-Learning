// Примеры к модулю 7 «Безопасность LLM-приложений и guardrails»: guardrails-middleware,
// allowlist инструментов со scopes пользователя, лимиты на запрос, безопасная обработка
// вывода (SQL, HTML, shell, markdown) и раннер red-team с метрикой ASR.
package course.m07security;

import java.io.IOException;
import java.util.List;

/**
 * Минимальный интерфейс модели. В проде за ним anthropic-java (модуль 2),
 * в тестах — fake, поэтому всё проверяется без сети и ключей.
 */
public interface LLM {
    Response complete(Request req) throws IOException;

    /** docs — недоверенный контент: чанки RAG, веб-страницы, вывод инструментов. */
    record Request(String system, String prompt, List<String> docs, int maxTokens) {
        public Request {
            docs = docs == null ? List.of() : List.copyOf(docs);
        }
    }

    /** args — сырой JSON аргументов, как его выдала модель. */
    record ToolCall(String name, String args) {}

    record Response(String text, List<ToolCall> toolCalls, int inputTokens, int outputTokens) {
        public Response {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }
}
