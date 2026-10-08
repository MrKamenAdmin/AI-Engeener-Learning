package course.m09prod.llmmw;

import static org.junit.jupiter.api.Assertions.*;

import course.m09prod.llmmw.Flags.Variant;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenFeatureFlagsTest {
    @Test
    void variantOF() {
        Flag<Value> answer = Flag.<Value>builder()
                .variant("v12", Value.objectToValue(Map.of("name", "v12", "prompt", "answer@v12", "model", "claude-opus-5")))
                .variant("v13", Value.objectToValue(Map.of("name", "v13", "prompt", "answer@v13",
                        "model", "claude-sonnet-5", "max_tokens", 800)))
                .defaultVariant("v13")
                .build();
        var api = OpenFeatureAPI.getInstance();
        api.setProviderAndWait(new InMemoryProvider(Map.of("answer", answer)));
        var c = api.getClient("llm");
        var def = new Variant("default", "claude-opus-5");

        Variant v = OpenFeatureFlags.variant(c, "answer", "acme", "u1", def);
        assertEquals("v13", v.name());
        assertEquals(800, v.maxTokens());
        assertEquals(def, OpenFeatureFlags.variant(c, "missing", "acme", "u1", def), "неизвестный флаг → default");
    }
}
