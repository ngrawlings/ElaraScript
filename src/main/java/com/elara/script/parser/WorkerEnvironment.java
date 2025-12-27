package com.elara.script.parser;

import java.util.LinkedHashMap;
import java.util.Map;
import com.elara.script.parser.utils.SnapshotUtils;

/**
 * WorkerEnvironment
 *
 * - Reads master state via a shared, immutable MasterEnvironmentSnapshot (no locks).
 * - Maintains its own ExecutionState/Environment for mutations.
 * - Publishes a master-readable mirror at cycle boundaries (copy + volatile swap).
 *
 * Publication contract:
 *   - Every worker cycle must call endCycle(outputReady).
 *   - If outputReady == true, worker state is deep-copied and published for the master to read.
 */
public final class WorkerEnvironment {

    /** Shared master snapshot for reads (immutable). */
    public final MasterEnvironmentSnapshot master;

    /** Worker-owned mutable execution state (instances + globals + env). */
    public final ExecutionState workerExecState;

    /** Master-readable mirror (always safe to read without mutex). */
    private volatile Map<String, Value> publishedWorkerState = new LinkedHashMap<>();

    /** Optional tracking: sequence number of last publish. */
    private volatile long publishedSeq = 0L;

    /** Optional tracking: last master generation used when publishing. */
    private volatile long publishedMasterGeneration = 0L;

    public WorkerEnvironment(MasterEnvironmentSnapshot master, ExecutionState workerExecState) {
        if (master == null) throw new IllegalArgumentException("master snapshot is null");
        if (workerExecState == null) throw new IllegalArgumentException("workerExecState is null");
        this.master = master;
        this.workerExecState = workerExecState;

        if (this.workerExecState.env == null) {
            this.workerExecState.env = new Environment(this.workerExecState);
        }
    }

    /**
     * Build initialEnv for a worker cycle run.
     *
     * Exposes:
     *  - __master_env: MAP of master root vars (read-only semantics; it's a snapshot)
     *  - __master_global: MAP of master globals (snapshot)
     *  - __worker_state: MAP of last published worker state (snapshot)
     *  - __master_generation: NUMBER generation tag
     */
    public Map<String, Value> buildInitialEnvForCycle() {
        Map<String, Value> initial = new LinkedHashMap<>();

        initial.put("__master_env", Value.map(new LinkedHashMap<>(master.env)));
        initial.put("__master_global", Value.map(new LinkedHashMap<>(master.global)));
        initial.put("__worker_state", Value.map(new LinkedHashMap<>(publishedWorkerState)));
        initial.put("__master_generation", Value.number((double) master.generation));

        return initial;
    }

    /**
     * Must be called once when a worker cycle completes.
     *
     * If outputReady is true, publish worker state to the master-readable mirror.
     */
    public void endCycle(boolean outputReady) {
        if (outputReady) publishWorkerState();
    }

    /** Master reads worker state without locks. */
    public Map<String, Value> readPublishedWorkerState() {
        return publishedWorkerState;
    }

    public long getPublishedSeq() {
        return publishedSeq;
    }

    public long getPublishedMasterGeneration() {
        return publishedMasterGeneration;
    }

    // ---------------- internal ----------------

    private void publishWorkerState() {
        Map<String, Value> root = SnapshotUtils.snapshotRootVars(workerExecState.env);

        // Deep copy so master never shares mutable graphs with the worker.
        Map<String, Value> deep = SnapshotUtils.deepCopyValueMap(root);

        // Publish with a single volatile pointer swap.
        this.publishedWorkerState = deep;
        this.publishedSeq = this.publishedSeq + 1L;
        this.publishedMasterGeneration = master.generation;
    }
}