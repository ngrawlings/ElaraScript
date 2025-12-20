import com.elara.script.ElaraScript;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.elara.script.ElaraScript.Value;
import static org.junit.jupiter.api.Assertions.*;

public class ElaraEventPayloadContractTest {

    @Test
    void payload_envPairs_allows_indexing_and_ts_extraction() {
        ElaraScript es = new ElaraScript();

        // payload == [["ts", 123]]
        Value payload = Value.array(List.of(
                Value.array(List.of(Value.string("ts"), Value.number(123)))
        ));

        String src = """
            function main(payload) {
                let ts = -1;
                let i = 0;

                while (i < len(payload)) {
                    let p = payload[i];
                    if (p[0] == "ts") {
                        ts = p[1];
                    }
                    i = i + 1;
                }

                return ts;
            }
            """;

        Value out = es.run(src, "main", List.of(payload));
        assertEquals(Value.Type.NUMBER, out.getType());
        assertEquals(123.0, out.asNumber(), 0.0);
    }

    @Test
    void payload_jsonString_throws_indexingNotSupportedOnString() {
        ElaraScript es = new ElaraScript();

        // This simulates the bug: payload arrives as a STRING instead of envPairs.
        Value payload = Value.string("{\"ts\":123}");

        String src = """
            function main(payload) {
                // This should explode if payload is a STRING
                let k = payload[0][0];
                return 0;
            }
            """;

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> es.run(src, "main", List.of(payload)));

        assertTrue(ex.getMessage().contains("Indexing not supported on type: STRING"),
                "Expected indexing-on-string error, got: " + ex.getMessage());
    }

    @Test
    void unterminated_string_is_reported_by_lexer() {
        ElaraScript es = new ElaraScript();

        // Intentionally broken: string is never closed
        String src = """
            function main() {
                let x = "unterminated
                return 0;
            }
            """;

        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run(src));
        assertTrue(ex.getMessage().toLowerCase().contains("unterminated string"),
                "Expected unterminated string error, got: " + ex.getMessage());
    }
}
