package com.elara.protocol.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FingerprintTraceCollector implements FingerprintTrace {
    private final List<String> steps = new ArrayList<>();

    @Override
    public void step(String token) {
        steps.add(token);
    }

    public List<String> steps() {
        return Collections.unmodifiableList(steps);
    }
}
