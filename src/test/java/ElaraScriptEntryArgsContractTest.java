import com.elara.script.ElaraScript;
import com.elara.script.parser.EntryRunResult;
import com.elara.script.parser.Value;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptEntryArgsContractTest {

    @Test
    void runWithEntryResult_passesAllEntryArgs_inOrder() {
        ElaraScript es = new ElaraScript();

        String src =
                "function event_system_ready(type, target, payload) {\n" +
                "    // payload should be envPairs: [[\"ts\", 123]]\n" +
                "    if (type == \"system\" && target == \"ready\" && payload[0][0] == \"ts\" && payload[0][1] == 123) {\n" +
                "        return 1;\n" +
                "    }\n" +
                "    return 0;\n" +
                "}\n";

        // payload = [["ts", 123]]
        Value payload = Value.array(List.of(
                Value.array(List.of(Value.string("ts"), Value.number(123)))
        ));

        EntryRunResult rr = es.runWithEntryResult(
                src,
                "event_system_ready",
                List.of(Value.string("system"), Value.string("ready"), payload),
                new LinkedHashMap<>(),   // liveInstances
                new LinkedHashMap<>()    // env
        );

        assertEquals(Value.Type.NUMBER, rr.value().getType());
        assertEquals(1.0, rr.value().asNumber(), 0.0);
    }

    @Test
    void runWithEntryResult_throws_ifArgCountMismatch() {
        ElaraScript es = new ElaraScript();

        String src =
                "function event_system_ready(type, target, payload) {\n" +
                "    return 0;\n" +
                "}\n";

        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.runWithEntryResult(
                src,
                "event_system_ready",
                List.of(Value.string("only_one_arg")),
                new LinkedHashMap<>(),   // liveInstances
                new LinkedHashMap<>()    // env
        ));

        String msg = (ex.getMessage() == null) ? "" : ex.getMessage();
        assertTrue(
                msg.contains("event_system_ready") && msg.contains("expects") && msg.contains("3") && msg.contains("got") && msg.contains("1"),
                "Got: " + msg
        );
    }
}
