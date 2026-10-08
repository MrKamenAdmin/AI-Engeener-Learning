package course.m06agents;

import static course.m06agents.Fake.endTurn;
import static course.m06agents.Fake.firstText;
import static course.m06agents.Fake.toolUse;
import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentTest {

    static AgentTool echoTool(String name, AtomicInteger calls) {
        return new AgentTool(AgentTool.def(name), in -> {
            calls.incrementAndGet();
            return "log line ".repeat(400); // ≈1K токенов на вызов
        });
    }

    @Test
    void budgetStopsRun() {
        var m = new Fake((n, p) -> toolUse(String.valueOf(n), "grep", "{}"));
        var root = new Budgeted(m, 0.05);
        var a = new Agent(root, "claude-opus-5", "", Map.of("grep", echoTool("grep", new AtomicInteger())), 100, null, null);
        var e = assertThrows(Agent.AgentException.class, () -> a.run("find the bug"));
        assertInstanceOf(Budgeted.BudgetExceededException.class, e.getCause());
        double spent = root.spent();
        assertTrue(spent >= 0.05 && spent <= 0.08 && e.result.iters < 100,
                "spent $%.4f in %d iters: budget must stop the run within one call".formatted(spent, e.result.iters));
    }

    static List<MessageParam> history() {
        List<MessageParam> msgs = new ArrayList<>(List.of(Agent.userText("TASK: audit docs")));
        for (int i = 0; i < 10; i++) {
            String id = "t" + i;
            msgs.add(MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(List.of(
                    ContentBlockParam.ofThinking(ThinkingBlockParam.builder().thinking("hmm").signature("sig").build()),
                    ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(id).name("read")
                            .input(ToolUseBlockParam.Input.builder().putAdditionalProperty("doc", JsonValue.from(i)).build())
                            .build()))).build());
            msgs.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(List.of(
                    ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(id)
                            .content("content of doc " + i).isError(false).build()))).build());
        }
        return msgs;
    }

    @Test
    void compactorKeepsPairsAndTail() {
        var msgs = history();
        var sum = new Fake((n, p) -> endTurn("docs 0-6 read, no issues"));
        var c = new Compactor(sum, "claude-haiku-4-5", 1000, 3);

        assertTrue(c.maybe(msgs, 999).isEmpty(), "below threshold nothing must change");

        var cut = new Fake((n, p) -> "\"stop_reason\":\"max_tokens\",\"content\":[]");
        assertThrows(IllegalStateException.class, () -> new Compactor(cut, null, 1000, 3).maybe(msgs, 5000),
                "a truncated summary must not replace the history");

        var out = c.maybe(msgs, 5000).orElseThrow();
        assertEquals(1 + 2 * 3, out.size(), "want head + 3 turns");
        assertNull(Fake.validate(out), "valid pairs");
        assertEquals("TASK: audit docs", firstText(out.get(0), 0), "head must keep the task verbatim");
        assertTrue(firstText(out.get(0), 1).contains("docs 0-6 read"), "head must carry the summary");
        for (var m : out) {
            assertTrue(Compactor.blocks(m).stream().noneMatch(ContentBlockParam::isThinking),
                    "thinking blocks of kept turns must be dropped");
        }
        // История, оборванная на ответе модели (чётная длина): граница сдвигается к assistant.
        var odd = c.maybe(msgs.subList(0, msgs.size() - 1), 5000).orElseThrow();
        assertEquals(MessageParam.Role.ASSISTANT, odd.get(1).role(), "kept tail must start with an assistant turn");

        String transcript = firstText(sum.reqs.get(0).messages().get(0), 0);
        assertTrue(transcript.contains("content of doc 0") && !transcript.contains("content of doc 9"),
                "summarizer must see old turns and not the kept tail");
    }

    /** 60+ шагов: компакция держит контекст ограниченным, а все запросы остаются валидными для API. */
    @Test
    void longHorizonWithNotesAndCompaction(@TempDir Path dir) throws Exception {
        var notes = new Notes(dir.resolve("NOTES.md"), 2000);
        final int docs = 60;
        var m = new Fake((n, p) -> {
            if (n == docs + docs / 10) {
                return endTurn("audit done");
            } else if (n % 11 == 10) { // каждые 10 документов — заметки
                return toolUse(String.valueOf(n), "notes",
                        "{\"op\":\"write\",\"content\":\"done: docs 0-%d\"}".formatted(n - n / 11 - 1));
            }
            return toolUse(String.valueOf(n), "read_doc", "{\"id\":%d}".formatted(n));
        });
        var sum = new Fake((n, p) -> endTurn("summary: see notes"));
        var reads = new AtomicInteger();
        var a = new Agent(new Budgeted(m, 10), "claude-opus-5", "",
                Map.of("read_doc", echoTool("read_doc", reads), "notes", notes.tool()), 100,
                new Compactor(sum, "claude-haiku-4-5", 12_000, 4), null);

        var res = a.run("audit all 60 docs");
        assertEquals("audit done", res.text);
        assertTrue(reads.get() == docs && res.steps.size() >= 60 && res.compactions >= 3,
                "reads=%d steps=%d compactions=%d".formatted(reads.get(), res.steps.size(), res.compactions));
        assertTrue(res.peakCtx <= 16_000, "context not bounded: peak " + res.peakCtx); // без компакции пик ≈ 60K
        assertEquals("done: docs 0-59", Files.readString(notes.path()));
        var last = m.reqs.get(m.reqs.size() - 1);
        assertTrue(firstText(last.messages().get(0), 1).contains("summary: see notes"),
                "after compaction the head must carry the summary");
    }

    @Test
    void notesLimit(@TempDir Path dir) throws Exception {
        var n = new Notes(dir.resolve("NOTES.md"), 10);
        assertEquals("(no notes yet)", n.run("{\"op\":\"read\"}"));
        assertThrows(IllegalArgumentException.class, () -> n.run("{\"op\":\"write\",\"content\":\"way too long notes\"}"),
                "oversized write must fail with a hint to condense");
        n.run("{\"op\":\"write\",\"content\":\"plan: x\"}");
        assertEquals("plan: x", n.run("{\"op\":\"read\"}"));
    }

    @Test
    void idempotentDedup() throws Exception {
        List<String> keys = new ArrayList<>();
        var send = Reliability.idempotent("session-1", new AgentTool(AgentTool.def("email_send"), in -> {
            keys.add(Reliability.idempotencyKey());
            return "sent";
        }));
        send.run().run("{\"to\":\"a@x.io\",\"body\":\"hi\"}");
        String out = send.run().run("{ \"body\": \"hi\", \"to\": \"a@x.io\" }"); // тот же вызов, другой порядок ключей
        send.run().run("{\"to\":\"b@x.io\",\"body\":\"hi\"}");
        assertEquals(2, keys.size(), "keys=" + keys);
        assertFalse(keys.get(0).isEmpty());
        assertNotEquals(keys.get(0), keys.get(1));
        assertTrue(out.startsWith("Already done"), out);
    }

    @Test
    void retryOnlyTransient() throws Exception {
        var calls = new AtomicInteger();
        String out = Reliability.retry(3, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new Reliability.TransientException("503 from search backend");
            }
            return "ok";
        });
        assertEquals("ok", out);
        assertEquals(3, calls.get());

        calls.set(0);
        assertThrows(IllegalStateException.class, () -> Reliability.retry(3, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("column not found");
        }));
        assertEquals(1, calls.get(), "permanent errors must not be retried");
    }

    @Test
    void subagentIsolatedContextAndBudget() throws Exception {
        var m = new Fake((n, p) -> {
            var msgs = p.messages();
            if (msgs.size() == 1 && firstText(msgs.get(0), 0).contains("find usages")) {
                return endTurn("usage found; ".repeat(100)); // субагент: длинный итог
            }
            if (msgs.size() == 1) {
                return toolUse("r1", "research", "{\"task\":\"find usages of Reserve()\"}");
            }
            return endTurn("fixed");
        });
        var root = new Budgeted(m, 1);
        var researcher = new Agent(root, "claude-haiku-4-5", "", Map.of(), 10, null, null);
        var lead = new Agent(root, "claude-opus-5", "",
                Map.of("research", researcher.asTool("research", "Delegate a read-only search.", 0.2, 200)), 10, null, null);
        var res = lead.run("fix the deadlock");
        assertEquals("fixed", res.text);
        // lead, субагент с чистой историей, lead
        assertEquals(3, m.reqs.size());
        assertEquals(1, m.reqs.get(1).messages().size(), "subagent must start with a fresh history");
        String result = Compactor.blocks(m.reqs.get(2).messages().get(2)).get(0).asToolResult().content().orElseThrow().asString();
        assertTrue(result.length() <= 200 + "\n…[truncated]".length(), "parent got " + result.length() + " chars");
        assertTrue(root.spent() > 0, "subagent spending must be charged to the root budget");
    }

    @Test
    void runSessionsStopsWhenStuck() throws Exception {
        var progress = new AtomicInteger(); // в жизни — passes=true в features.json; здесь каждая сессия закрывает один пункт
        var m = new Fake((n, p) -> {
            progress.incrementAndGet();
            return endTurn("one feature done");
        });
        var a = new Agent(m, "claude-opus-5", "", Map.of(), 5, null, null);
        Sessions.Check check = () -> new Sessions.Progress(progress.get() >= 3, String.valueOf(progress.get()));
        assertEquals(3, Sessions.runSessions(a, "build", check, 10, 2), "want done after 3 sessions");

        progress.set(0);
        m.reply = (n, p) -> endTurn("nothing changed");
        assertThrows(Sessions.StuckException.class, () -> Sessions.runSessions(a, "build", check, 10, 2));
    }
}
