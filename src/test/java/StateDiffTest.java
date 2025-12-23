import org.junit.jupiter.api.Test;

import com.elara.protocol.util.StateDiff;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class StateDiffTest {

    @Test
    public void diff_setAndRemove_basic() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("a", 1.0);
        before.put("b", "x");

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("a", 2.0);        // changed => set
        // b missing => remove
        after.put("c", true);       // new => set

        StateDiff.DiffResult r = StateDiff.diff(before, after);

        // deterministic ordering (TreeSet in diff)
        assertEquals(List.of("b"), r.remove);

        assertEquals(2, r.set.size());
        assertEquals(List.of("a", 2.0), r.set.get(0));
        assertEquals(List.of("c", true), r.set.get(1));
    }

    @Test
    public void diff_ignoresEphemeralKeys() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("__global_state", Map.of("x", 1));
        before.put("a", 1.0);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("__global_state", Map.of("x", 999)); // should be ignored completely
        after.put("a", 2.0);

        StateDiff.DiffResult r = StateDiff.diff(before, after);

        assertTrue(r.remove.isEmpty());
        assertEquals(1, r.set.size());
        assertEquals(List.of("a", 2.0), r.set.get(0));
    }

    @Test
    public void diff_afterNullMeansRemove() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("a", 1.0);
        before.put("b", "x");

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("a", null);   // explicit null => remove
        after.put("b", "x");    // unchanged

        StateDiff.DiffResult r = StateDiff.diff(before, after);

        assertEquals(List.of("a"), r.remove);
        assertTrue(r.set.isEmpty());
    }

    @Test
    public void diff_deepEquals_listAndMap() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("list", List.of(1.0, "x", true));
        before.put("map", new LinkedHashMap<>(Map.of("k1", 1.0, "k2", "v")));

        Map<String, Object> afterSame = new LinkedHashMap<>();
        afterSame.put("list", List.of(1.0, "x", true));
        afterSame.put("map", new LinkedHashMap<>(Map.of("k1", 1.0, "k2", "v")));

        StateDiff.DiffResult rSame = StateDiff.diff(before, afterSame);
        assertTrue(rSame.set.isEmpty());
        assertTrue(rSame.remove.isEmpty());

        // now change inside nested structure => should set that top-level key
        Map<String, Object> afterChanged = new LinkedHashMap<>(afterSame);
        afterChanged.put("map", new LinkedHashMap<>(Map.of("k1", 2.0, "k2", "v")));

        StateDiff.DiffResult rChanged = StateDiff.diff(before, afterChanged);
        assertTrue(rChanged.remove.isEmpty());
        assertEquals(1, rChanged.set.size());
        assertEquals("map", rChanged.set.get(0).get(0));
        assertEquals(2.0, ((Map<?, ?>) rChanged.set.get(0).get(1)).get("k1"));
    }

    @Test
    public void diff_isDeterministicOrdering() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("z", 1.0);
        before.put("a", 1.0);
        before.put("m", 1.0);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("z", 2.0); // set
        // a removed
        after.put("m", 1.0); // unchanged
        after.put("b", 9.0); // set

        StateDiff.DiffResult r = StateDiff.diff(before, after);

        // removes sorted
        assertEquals(List.of("a"), r.remove);

        // sets sorted by key: b then z
        assertEquals(2, r.set.size());
        assertEquals("b", r.set.get(0).get(0));
        assertEquals("z", r.set.get(1).get(0));
    }

    @Test
    public void diff_deepCopiesPatchValues() {
        // if after is mutated after diff, patch contents must NOT change
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("obj", new LinkedHashMap<>(Map.of("k", 1.0)));

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("k", 2.0);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("obj", nested);

        StateDiff.DiffResult r = StateDiff.diff(before, after);
        assertEquals(1, r.set.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> patchObj = (Map<String, Object>) r.set.get(0).get(1);
        assertEquals(2.0, patchObj.get("k"));

        // mutate 'after' nested map after diff
        nested.put("k", 999.0);

        // patch should remain unchanged if deepCopyJsonSafe worked
        assertEquals(2.0, patchObj.get("k"));
    }

    @Test
    public void diff_sameReferencePitfall_returnsEmpty() {
        // This reproduces the bug class you’re seeing:
        // if protocol uses the same map instance for before and after (mutated in-place),
        // diff can't detect changes because before already sees the new value.
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("ts", 1.0);

        Map<String, Object> before = state; // same reference
        Map<String, Object> after = state;  // same reference

        // mutate in place
        state.put("ts", 3.0);

        StateDiff.DiffResult r = StateDiff.diff(before, after);

        // will be empty because before.get("ts") == after.get("ts") (both are 3.0 now)
        assertTrue(r.set.isEmpty());
        assertTrue(r.remove.isEmpty());
    }

    @Test
    public void diff_requiresSnapshot_detectsInPlaceMutationIfBeforeIsCopied() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("ts", 1.0);

        // snapshot before (shallow snapshot is enough for primitives)
        Map<String, Object> before = new LinkedHashMap<>(state);

        // mutate live state (like script does)
        state.put("ts", 3.0);

        Map<String, Object> after = state;

        StateDiff.DiffResult r = StateDiff.diff(before, after);

        assertEquals(1, r.set.size());
        assertEquals(List.of("ts", 3.0), r.set.get(0));
        assertTrue(r.remove.isEmpty());
    }
}
