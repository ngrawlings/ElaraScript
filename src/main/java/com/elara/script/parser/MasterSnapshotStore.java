package com.elara.script.parser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.elara.script.parser.utils.SnapshotUtils;

/**
 * Holds the current shared master snapshot for all workers.
 *
 * Intended pattern:
 *   - Master calls updateFromMaster(execState) once per master cycle boundary.
 *   - Workers read snapshot via current() with no locks.
 *
 * Uses a volatile reference swap for publication.
 */
public final class MasterSnapshotStore {

    private final AtomicLong gen = new AtomicLong(0);

    private volatile MasterEnvironmentSnapshot current =
            new MasterEnvironmentSnapshot(0L, new LinkedHashMap<>(), new LinkedHashMap<>());

    /** Get the current published snapshot (safe to share across threads). */
    public MasterEnvironmentSnapshot current() {
        return current;
    }

    /**
     * Publish a new snapshot from the master ExecutionState.
     * This should be called at a well-defined boundary (e.g., end of master cycle).
     */
    public MasterEnvironmentSnapshot updateFromMaster(ExecutionState masterExecState) {
        if (masterExecState == null) throw new IllegalArgumentException("masterExecState is null");

        long next = gen.incrementAndGet();

        // Snapshot master env vars and globals, then deep copy to eliminate sharing.
        Map<String, Value> rootVars = SnapshotUtils.snapshotRootVars(masterExecState.env);
        Map<String, Value> globals = masterExecState.global == null ? new LinkedHashMap<>() : new LinkedHashMap<>(masterExecState.global);

        Map<String, Value> envCopy = SnapshotUtils.deepCopyValueMap(rootVars);
        Map<String, Value> globalCopy = SnapshotUtils.deepCopyValueMap(globals);

        MasterEnvironmentSnapshot snap = new MasterEnvironmentSnapshot(next, envCopy, globalCopy);

        // Volatile pointer swap publishes the snapshot.
        this.current = snap;
        return snap;
    }
}