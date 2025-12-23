import com.elara.script.ElaraScript;
import com.elara.script.ElaraScript.Value;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptErrorHandlingModesTest {

    @Test
    void noCallback_throwsToHost() {
        ElaraScript es = new ElaraScript();

        // Missing variable triggers var_not_found and then exception.
        String src = "let x = y;";

        // With no callback, onInterpreterError throws the original exception.
        assertThrows(RuntimeException.class, () -> es.run(src));
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

        Map<String, Value> env = assertDoesNotThrow(() -> es.run(src));

        assertEquals("var_not_found", env.get("kind").asString());
        assertEquals("y", env.get("name").asString());
        assertTrue(env.get("message").asString().contains("Undefined variable"));
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

        ElaraScript.EntryRunResult rr =
                assertDoesNotThrow(() -> es.runWithEntryResult(src, "main", null, null));

        assertTrue(rr.env().get("seen").asBool(), "expected callback to run");
        // If execution aborted on error, return value should typically be null (Value.nil()).
        assertEquals(Value.Type.NULL, rr.value().getType());
    }
}
