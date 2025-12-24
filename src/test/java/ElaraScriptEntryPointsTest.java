import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.EntryRunResult;
import com.elara.script.parser.Value;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptEntryPointsTest {

    @Test
    public void globalProgramMode_runReturnsFinalEnvironmentSnapshot() {
        ElaraScript es = new ElaraScript();

        Map<String, Value> env = es.run(
                "let x = 1 + 2;\n" +
                "let y = x * 10;\n"
        );

        assertNotNull(env);
        assertTrue(env.containsKey("x"));
        assertTrue(env.containsKey("y"));
        assertEquals(3.0, env.get("x").asNumber());
        assertEquals(30.0, env.get("y").asNumber());
    }

    @Test
    public void entryFunctionMode_runReturnsEntryReturnValue() {
        ElaraScript es = new ElaraScript();

        String src =
                "function add(a, b) {\n" +
                "  return a + b;\n" +
                "}\n";

        Value out = es.run(
                src,
                "add",
                Arrays.asList(Value.number(2), Value.number(3))
        );

        assertNotNull(out);
        assertEquals(Value.Type.NUMBER, out.getType());
        assertEquals(5.0, out.asNumber());
    }

    @Test
    public void entryFunctionMode_runWithEntryResultReturnsValueAndEnvSnapshot() {
        ElaraScript es = new ElaraScript();

        String src =
                "let y = 10;\n" +
                "function main(a) {\n" +
                "  let z = a + y;\n" +
                "  return z;\n" +
                "}\n";

        EntryRunResult rr = es.runWithEntryResult(
                src,
                "main",
                Collections.singletonList(Value.number(5)),
                Collections.emptyMap()
        );

        assertNotNull(rr);
        assertNotNull(rr.value());
        assertEquals(15.0, rr.value().asNumber());

        Map<String, Value> env = rr.env();
        assertNotNull(env);
        assertTrue(env.containsKey("y"));
        assertEquals(10.0, env.get("y").asNumber());
        // Note: user-defined functions are not required to appear in the env snapshot; only variables are asserted here.
    }
}