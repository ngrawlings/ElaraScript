import org.junit.jupiter.api.Test;

import com.elara.protocol.util.FingerprintTraceCollector;
import com.elara.protocol.util.StateFingerprint;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StateFingerprintTraceTest {

    @Test
    void traceSteps_areDeterministicAndSorted() {
        // Build map intentionally out-of-order to test sorting.
        Map<String, Object> state = new HashMap<>();
        state.put("b", 1);                         // number -> D: 1.0
        state.put("a", Arrays.asList("x", "y"));    // list
        Map<String, Object> inner = new HashMap<>();
        inner.put("d", true);
        inner.put("c", null);
        state.put("m", inner);                      // nested map (keys c,d sorted)

        FingerprintTraceCollector trace = new FingerprintTraceCollector();

        StateFingerprint.fingerprintRawState(state, trace);

        List<String> expected = Arrays.asList(
                "{",

                // a first (sorted)
                "k:", "a", "=", "L:", "[",
                "i:", "0", "=", "S:", "x", ";",
                "i:", "1", "=", "S:", "y", ";",
                "]", ";",

                // b next
                "k:", "b", "=", "D:", "1.0", ";",

                // m last
                "k:", "m", "=", "M:", "{",
                "k:", "c", "=", "N", ";",
                "k:", "d", "=", "T", ";",
                "}", ";",

                "}"
        );

        assertEquals(expected, trace.steps());
    }
}
