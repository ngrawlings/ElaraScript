import com.elara.script.ElaraScript;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.elara.script.ElaraScript.Value;
import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptEntryArgsContractTest {

    @Test
    void runWithEntryResult_passesAllEntryArgs_inOrder() {
        ElaraScript es = new ElaraScript();

        String src = """
            function event_system_ready(type, target, payload) {
                // payload should be envPairs: [["ts", 123]]
                if (type == "system" && target == "ready" && payload[0][0] == "ts" && payload[0][1] == 123) {
                    return 1;
                }
                return 0;
            }
            """;

        // payload = [["ts", 123]]
        Value payload = Value.array(List.of(
                Value.array(List.of(Value.string("ts"), Value.number(123)))
        ));

        ElaraScript.EntryRunResult rr = es.runWithEntryResult(
                src,
                "event_system_ready",
                List.of(Value.string("system"), Value.string("ready"), payload),
                Map.of()
        );

        assertEquals(Value.Type.NUMBER, rr.value().getType());
        assertEquals(1.0, rr.value().asNumber(), 0.0);
    }

    @Test
    void runWithEntryResult_throws_ifArgCountMismatch() {
        ElaraScript es = new ElaraScript();

        String src = """
            function event_system_ready(type, target, payload) {
                return 0;
            }
            """;

        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.runWithEntryResult(
                src,
                "event_system_ready",
                List.of(Value.string("only_one_arg")),
                Map.of()
        ));

        assertTrue(ex.getMessage().contains("event_system_ready() expects 3 arguments, got 1"),
                "Got: " + ex.getMessage());
    }
}
