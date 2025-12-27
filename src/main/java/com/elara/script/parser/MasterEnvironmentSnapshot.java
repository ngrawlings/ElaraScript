package com.elara.script.parser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable, read-only snapshot of the master environment for a given generation.
 *
 * This is designed to be shared across ALL workers without mutexes:
 * - created once by the master at a cycle boundary
 * - deep-copied (so workers never observe concurrent mutation)
 * - never mutated after construction
 */
public final class MasterEnvironmentSnapshot {

    /** Monotonic version for debugging/correlation. */
    public final long generation;

    /** Snapshot of master root env vars (NOT including synthetic global frame). */
    public final Map<String, Value> env;

    /** Snapshot of master globals (ExecutionState.global). */
    public final Map<String, Value> global;

    public MasterEnvironmentSnapshot(long generation, Map<String, Value> env, Map<String, Value> global) {
        this.generation = generation;

        // Defensively wrap as unmodifiable to prevent accidental mutation.
        this.env = Collections.unmodifiableMap(env == null ? new LinkedHashMap<>() : env);
        this.global = Collections.unmodifiableMap(global == null ? new LinkedHashMap<>() : global);
    }
}