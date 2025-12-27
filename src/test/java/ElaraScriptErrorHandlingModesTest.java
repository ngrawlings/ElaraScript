import com.elara.script.ElaraScript;
import com.elara.script.parser.EntryRunResult;
import com.elara.script.parser.Value;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptErrorHandlingModesTest {

    @Test
    void noCallback_throwsToHost() {
        ElaraScript es = new ElaraScript();

        // Missing variable triggers var_not_found and then exception.
        String src = "let x = y;";

        // With no callback, onInterpreterError throws the original exception.
        assertThrows(RuntimeException.class, () ->
                es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>())
        );
    }

    @Test
    void callback_run_suppressesException_andRoutesToHandler() {
        ElaraScript es = new ElaraScript();
        es.setErrorCallback("event_system_error");

        String src = String.join("\n",
            "let kind = \"\";",
            "let name = \"\";",
            "let message = \"\";",
            "",
            "function event_system_error(k, n, fn, line, msg) {",
            "    // interpreter may report more than once; keep first",
            "    if (kind == \"\") {",
            "        kind = k;",
            "        if (n != null) name = n;",
            "        message = msg;",
            "    }",
            "}",
            "",
            "let x = y;",
            ""
        );

        Map<String, Value> snapshot = assertDoesNotThrow(() ->
                es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>())
        );

        Map<String, Value> vars = extractInnermostVars(snapshot);

        assertEquals("var_not_found", vars.get("kind").asString());
        assertEquals("y", vars.get("name").asString());
        assertTrue(vars.get("message").asString().contains("Undefined variable"));
    }

    @Test
    void callback_entryRun_suppressesException_andStillInvokesHandler() {
        ElaraScript es = new ElaraScript();
        es.setErrorCallback("event_system_error");

        String src = String.join("\n",
            "let seen = false;",
            "",
            "function event_system_error(k, n, fn, line, msg) {",
            "    seen = true;",
            "}",
            "",
            "function main() {",
            "    let x = y;",
            "    return 42;",
            "}",
            ""
        );

        EntryRunResult rr = assertDoesNotThrow(() ->
                es.runWithEntryResult(
                        src,
                        "main",
                        List.of(),                // no args
                        new LinkedHashMap<>(),     // liveInstances
                        new LinkedHashMap<>()      // env
                )
        );

        Map<String, Value> vars = extractInnermostVars(rr.env());
        assertTrue(vars.get("seen").asBool(), "expected callback to run");

        // If execution aborted on error, return value should typically be null (Value.nil()).
        assertEquals(Value.Type.NULL, rr.value().getType());
    }

    // ---------------- helpers ----------------

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
