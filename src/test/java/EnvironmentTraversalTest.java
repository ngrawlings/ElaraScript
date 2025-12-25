import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.elara.script.parser.Environment;
import com.elara.script.parser.Value;

public class EnvironmentTraversalTest {

    @BeforeEach
    void resetGlobals() {
        Environment.global.clear();
    }

    @Test
    void getTraversal_varsThenParentThenGlobal() {
        // global
        Environment.global.put("g", Value.number(100));

        // root env
        Environment root = new Environment();
        root.define("a", Value.number(1));
        root.define("shadow", Value.number(10));

        // child env (normal function frame: instance_owner == null)
        Environment child = root.childScope(null);
        child.define("b", Value.number(2));
        child.define("shadow", Value.number(20)); // local shadows parent

        // local
        assertEquals(2.0, child.get("b").asNumber());

        // parent
        assertEquals(1.0, child.get("a").asNumber());

        // shadowing
        assertEquals(20.0, child.get("shadow").asNumber());
        assertEquals(10.0, root.get("shadow").asNumber());

        // global (only if not found in vars/parent chain)
        assertEquals(100.0, child.get("g").asNumber());
        assertEquals(100.0, root.get("g").asNumber());
    }

    @Test
    void assignUpdatesNearestExistingScope_notLocalCopy() {
        Environment root = new Environment();
        root.define("i", Value.number(0));

        Environment child = root.childScope(null);

        // assigning existing var should update root (nearest existing in chain), not create child var
        child.assign("i", Value.number(5));

        assertEquals(5.0, root.get("i").asNumber());
        assertEquals(5.0, child.get("i").asNumber());
        assertFalse(child.existsInCurrentScope("i"), "child should not create its own 'i' entry on assign()");
    }

    @Test
    void assignUndefinedThrows_evenIfGlobalHasOtherKeys() {
        Environment.global.put("g", Value.number(1));

        Environment root = new Environment();
        Environment child = root.childScope(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> child.assign("missing", Value.number(9)));
        assertTrue(ex.getMessage().toLowerCase().contains("undefined"), "should throw undefined variable");
    }

    @Test
    void defineCreatesLocal_onlyAndDoesNotTouchParent() {
        Environment root = new Environment();
        root.define("x", Value.number(1));

        Environment child = root.childScope(null);
        child.define("x", Value.number(2)); // allowed: local var table is independent

        assertEquals(2.0, child.get("x").asNumber());
        assertEquals(1.0, root.get("x").asNumber());
    }

    @Test
    void removeRemovesFromNearestScopeInResolutionChain() {
        Environment root = new Environment();
        root.define("x", Value.number(1));

        Environment child = root.childScope(null);
        child.define("y", Value.number(2));

        // removing local removes local
        child.remove("y");
        assertFalse(child.existsInCurrentScope("y"));
        assertThrows(RuntimeException.class, () -> child.get("y"));

        // removing parent-owned var from child removes it from parent
        child.remove("x");
        assertThrows(RuntimeException.class, () -> root.get("x"));
    }

    @Test
    void snapshotMergesGlobalThenParentThenLocal_withLocalWinning() {
        Environment.global.put("k", Value.number(1));
        Environment.global.put("shadow", Value.number(10));

        Environment root = new Environment();
        root.define("a", Value.number(2));
        root.define("shadow", Value.number(20));

        Environment child = root.childScope(null);
        child.define("b", Value.number(3));
        child.define("shadow", Value.number(30));

        Map<String, Value> snap = child.snapshot();

        // presence
        assertEquals(1.0, snap.get("k").asNumber());
        assertEquals(2.0, snap.get("a").asNumber());
        assertEquals(3.0, snap.get("b").asNumber());

        // local shadows parent shadows global
        assertEquals(30.0, snap.get("shadow").asNumber());

        // sanity: snapshot should not mutate originals
        assertEquals(10.0, Environment.global.get("shadow").asNumber());
        assertEquals(20.0, root.get("shadow").asNumber());
        assertEquals(30.0, child.get("shadow").asNumber());
    }

    @Test
    void initialVarsAreEmptyInChildScope_soLoopCountersDontLoseState() {
        // This specifically catches the old "seed child vars with parent vars" bug.
        Environment root = new Environment();
        root.define("i", Value.number(0));

        Environment child = root.childScope(null);

        // Child must start empty (no seeded copy)
        assertFalse(child.existsInCurrentScope("i"), "child scope must not seed vars with parent vars");

        // Assign should walk up to root and mutate it
        child.assign("i", Value.number(1));
        assertEquals(1.0, root.get("i").asNumber());
    }

    @Test
    void functionLikeFrameSetup_withParamBinding_behavesCorrectly() {
        // Simulates a normal function call frame:
        // - create child scope (empty vars)
        // - bind params into child
        // - reads fall back to parent/global
        // - assigns update nearest existing binding

        Environment.global.put("g", Value.number(99));

        Environment closure = new Environment();
        closure.define("captured", Value.number(7)); // closure binding

        Environment callFrame = closure.childScope(null);

        // bind param
        callFrame.define("p", Value.number(1));

        // can see closure var
        assertEquals(7.0, callFrame.get("captured").asNumber());

        // can see global
        assertEquals(99.0, callFrame.get("g").asNumber());

        // mutate param stays local
        callFrame.assign("p", Value.number(2));
        assertEquals(2.0, callFrame.get("p").asNumber());
        assertFalse(closure.existsInCurrentScope("p"));

        // mutate captured updates closure (nearest existing)
        callFrame.assign("captured", Value.number(8));
        assertEquals(8.0, closure.get("captured").asNumber());
    }
}
