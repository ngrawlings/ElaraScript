import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.EntryRunResult;
import com.elara.script.parser.Value;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptEntryPointsTest {

    @Test
    public void globalProgramMode_runReturnsFinalEnvironmentSnapshot() {
        ElaraScript es = new ElaraScript();

        Map<String, Value> snapshot = es.run(
                "let x = 1 + 2;\n" +
                "let y = x * 10;\n",
                new LinkedHashMap<>(),   // liveInstances
                new LinkedHashMap<>()    // initialEnv
        );

        assertNotNull(snapshot);

        Map<String, Value> vars = extractInnermostVars(snapshot);

        assertTrue(vars.containsKey("x"));
        assertTrue(vars.containsKey("y"));
        assertEquals(3.0, vars.get("x").asNumber());
        assertEquals(30.0, vars.get("y").asNumber());
    }

    @Test
    public void entryFunctionMode_runReturnsEntryReturnValue() {
        ElaraScript es = new ElaraScript();

        String src =
                "function add(a, b) {\n" +
                "  return a + b;\n" +
                "}\n";

        // Updated: run(entry) should be performed via runWithEntryResult
        // (it’s the stable API now, since env/instances are part of the contract).
        EntryRunResult rr = es.runWithEntryResult(
                src,
                "add",
                Arrays.asList(Value.number(2), Value.number(3)),
                new LinkedHashMap<>(),   // liveInstances
                new LinkedHashMap<>()    // env
        );

        Value out = rr.value();
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
                new LinkedHashMap<>(),   // liveInstances
                new LinkedHashMap<>()    // env
        );

        assertNotNull(rr);
        assertNotNull(rr.value());
        assertEquals(15.0, rr.value().asNumber());

        Map<String, Value> snapshot = rr.env();
        assertNotNull(snapshot);

        Map<String, Value> vars = extractInnermostVars(snapshot);

        assertTrue(vars.containsKey("y"), "y should exist in vars");
        assertEquals(10.0, vars.get("y").asNumber());
        // Note: user-defined functions are not required to appear in the env snapshot; only variables are asserted here.
    }

    private static Map<String, Value> extractInnermostVars(Map<String, Value> snapshot) {
        Value envsV = snapshot.get("environments");
        assertNotNull(envsV, "snapshot must contain environments");
        assertEquals(Value.Type.ARRAY, envsV.getType(), "environments must be ARRAY");

        List<Value> frames = envsV.asArray();
        assertNotNull(frames, "environments array must not be null");
        assertFalse(frames.isEmpty(), "environments must not be empty");

        Value lastFrameV = frames.get(frames.size() - 1);
        assertNotNull(lastFrameV, "last frame must not be null");
        assertEquals(Value.Type.MAP, lastFrameV.getType(), "frame must be MAP");

        Map<String, Value> frame = lastFrameV.asMap();
        Value varsV = frame.get("vars");
        assertNotNull(varsV, "frame must contain vars");
        assertEquals(Value.Type.MAP, varsV.getType(), "vars must be MAP");

        return varsV.asMap();
    }

}
