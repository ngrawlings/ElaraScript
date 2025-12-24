package com.elara.script.parser;

import java.util.Map;

public class EntryRunResult {
    private final Map<String, Value> env;
    private final Value value;

    public EntryRunResult(Map<String, Value> env, Value value) {
        this.env = env;
        this.value = value;
    }

    public Map<String, Value> env() { return env; }
    public Value value() { return value; }
}