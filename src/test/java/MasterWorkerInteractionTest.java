import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Environment;
import com.elara.script.parser.ExecutionState;
import com.elara.script.parser.MasterEnvironmentSnapshot;
import com.elara.script.parser.MasterSnapshotStore;
import com.elara.script.parser.Value;
import com.elara.script.parser.WorkerEnvironment;
import com.elara.script.parser.utils.SnapshotUtils;

/**
 * End-to-end simulation of master<->worker interaction:
 *  - master publishes shared snapshot
 *  - workers read snapshot without locks
 *  - workers run ElaraScript with injected __master_env/__master_global
 *  - workers publish only when outputReady==true
 *  - master reads published worker mirror
 *  - master merges something and publishes next generation snapshot
 *  - next generation is visible to subsequent worker cycle
 */
public class MasterWorkerInteractionTest {

    @Test
    void masterWorker_fullCycle_publishAndMerge_nextGenerationVisible() {

        // ---------------------------
        // 0) Set up master execution state
        // ---------------------------
        ExecutionState master = new ExecutionState();
        if (master.global == null) master.global = new LinkedHashMap<String, Value>();

        master.global.put("g0", Value.number(100));

        Map<String, Value> masterVars = new LinkedHashMap<String, Value>();
        masterVars.put("m0", Value.number(1));

        master.env = new Environment(master, masterVars);

        MasterSnapshotStore snapshotStore = new MasterSnapshotStore();

        // ---------------------------
        // 1) Master publishes snapshot generation 1
        // ---------------------------
        MasterEnvironmentSnapshot snap1 = snapshotStore.updateFromMaster(master);

        assertNotNull(snap1);
        assertEquals(1L, snap1.generation);
        assertEquals(1.0, snap1.env.get("m0").asNumber(), 0.0);
        assertEquals(100.0, snap1.global.get("g0").asNumber(), 0.0);

        // ---------------------------
        // 2) Create two workers sharing the same master snapshot
        // ---------------------------
        ExecutionState w1State = new ExecutionState();
        if (w1State.global == null) w1State.global = new LinkedHashMap<String, Value>();
        w1State.env = new Environment(w1State, new LinkedHashMap<String, Value>());

        ExecutionState w2State = new ExecutionState();
        if (w2State.global == null) w2State.global = new LinkedHashMap<String, Value>();
        w2State.env = new Environment(w2State, new LinkedHashMap<String, Value>());

        WorkerEnvironment w1 = new WorkerEnvironment(snap1, w1State);
        WorkerEnvironment w2 = new WorkerEnvironment(snap1, w2State);

        assertSame(snap1, w1.master);
        assertSame(snap1, w2.master);

        // ---------------------------
        // 3) Worker scripts:
        //    - read master snapshots via __master_env / __master_global
        //    - write worker-local vars (so they are publishable)
        // ---------------------------
        String workerScript1 =
                "function main() {\n" +
                "  let mm = __master_env[\"m0\"];      \n" +
                "  let gg = __master_global[\"g0\"];   \n" +
                "  let sum = mm + gg;                  \n" +
                "  let who = \"w1\";                   \n" +
                "  let out = sum;                      \n" +
                "  return out;                         \n" +
                "}\n" +
                "let who = \"w1\";\n" +
                "let out = main();\n";

        String workerScript2 =
                "function main() {\n" +
                "  let mm = __master_env[\"m0\"];      \n" +
                "  let gg = __master_global[\"g0\"];   \n" +
                "  let out = (mm * 10) + gg;           \n" +
                "  return out;                         \n" +
                "}\n" +
                "let who = \"w2\";\n" +
                "let out = main();\n";

        ElaraScript engine = new ElaraScript();

        // ---------------------------
        // 4) Run worker cycle 1
        // ---------------------------

        // w1 run
        Map<String, Value> w1Init = w1.buildInitialEnvForCycle();
        Map<String, Value> w1SnapAfter = engine.run(workerScript1, w1State.liveInstances, w1Init);

        // Flatten the snapshot to vars, then rebuild worker env from that.
        Map<String, Value> w1VarsAfter = SnapshotUtils.mergedVars(w1SnapAfter);
        w1State.env = new Environment(w1State, w1VarsAfter);

        // publish outputReady=true
        w1.endCycle(true);

        // w2 run
        Map<String, Value> w2Init = w2.buildInitialEnvForCycle();
        Map<String, Value> w2SnapAfter = engine.run(workerScript2, w2State.liveInstances, w2Init);

        Map<String, Value> w2VarsAfter = SnapshotUtils.mergedVars(w2SnapAfter);
        w2State.env = new Environment(w2State, w2VarsAfter);

        // outputReady=false => should NOT publish anything
        w2.endCycle(false);

        // ---------------------------
        // 5) Master reads published worker state
        // ---------------------------
        Map<String, Value> w1Published = w1.readPublishedWorkerState();
        assertNotNull(w1Published);

        assertTrue(w1Published.containsKey("out"), "w1 must publish 'out'");
        assertEquals(101.0, w1Published.get("out").asNumber(), 0.0);
        assertEquals("w1", w1Published.get("who").asString());

        Map<String, Value> w2Published = w2.readPublishedWorkerState();
        assertNotNull(w2Published);
        assertTrue(w2Published.isEmpty(), "w2 should not publish any state when outputReady=false");

        // ---------------------------
        // 6) Simulate master merge step
        // ---------------------------
        Map<String, Value> masterVarsAfterMerge = new LinkedHashMap<String, Value>(masterVars);
        masterVarsAfterMerge.put("last_out", w1Published.get("out"));
        master.env = new Environment(master, masterVarsAfterMerge);

        // ---------------------------
        // 7) Master publishes snapshot generation 2
        // ---------------------------
        MasterEnvironmentSnapshot snap2 = snapshotStore.updateFromMaster(master);

        assertEquals(2L, snap2.generation);
        assertEquals(1.0, snap2.env.get("m0").asNumber(), 0.0);
        assertEquals(101.0, snap2.env.get("last_out").asNumber(), 0.0);

        // ---------------------------
        // 8) New worker cycle reading latest master snapshot should see merged key
        // ---------------------------
        WorkerEnvironment w1_cycle2 = new WorkerEnvironment(snap2, w1State);

        String cycle2 =
                "let seen = __master_env[\"last_out\"]; \n";

        Map<String, Value> w1Init2 = w1_cycle2.buildInitialEnvForCycle();
        Map<String, Value> w1SnapAfter2 = engine.run(cycle2, w1State.liveInstances, w1Init2);

        Map<String, Value> w1VarsAfter2 = SnapshotUtils.mergedVars(w1SnapAfter2);
        w1State.env = new Environment(w1State, w1VarsAfter2);

        w1_cycle2.endCycle(true);

        Map<String, Value> pub2 = w1_cycle2.readPublishedWorkerState();
        assertTrue(pub2.containsKey("seen"));
        assertEquals(101.0, pub2.get("seen").asNumber(), 0.0);
        assertEquals(2.0, w1_cycle2.getPublishedMasterGeneration(), 0.0);
    }
}
