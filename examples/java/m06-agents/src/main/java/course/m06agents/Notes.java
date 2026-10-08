package course.m06agents;

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * Заметки агента в одном файле (NOTES.md, progress): чтение и полная перезапись.
 * Дописывания нет намеренно: с лимитом размера агент вынужден сжимать заметки, а не копить лог.
 */
public record Notes(Path path, int maxBytes) {

    public AgentTool tool() {
        return new AgentTool(AgentTool.def("notes",
                "Your persistent notes file; it survives context compaction and restarts. "
                        + "op=read returns the whole file. op=write REPLACES the whole file with content. "
                        + "Limit %d bytes. Keep: goal, plan, done/todo lists, decisions with reasons, open questions. ".formatted(maxBytes)
                        + "Do not paste tool outputs: store paths and IDs to re-fetch them. Read notes after a compaction.",
                Map.of("op", Map.of("type", "string", "enum", List.of("read", "write")),
                        "content", Map.of("type", "string", "description", "full new content, required for write")),
                List.of("op")),
                this::run);
    }

    String run(String input) throws IOException {
        JsonNode in = ObjectMappers.jsonMapper().readTree(input);
        String op = in.path("op").asText();
        String content = in.path("content").asText("");
        switch (op) {
            case "read" -> {
                try {
                    return Files.readString(path);
                } catch (NoSuchFileException e) {
                    return "(no notes yet)";
                }
            }
            case "write" -> {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > maxBytes) {
                    throw new IllegalArgumentException(("notes are %d bytes, limit is %d: condense them — merge done items, "
                            + "drop details you can re-fetch, keep decisions and next steps").formatted(bytes.length, maxBytes));
                }
                // Запись через временный файл и atomic move: обрыв посреди записи не оставит полфайла.
                Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return "saved %d/%d bytes".formatted(bytes.length, maxBytes);
            }
            default -> throw new IllegalArgumentException("unknown op \"" + op + "\": use read or write");
        }
    }
}
