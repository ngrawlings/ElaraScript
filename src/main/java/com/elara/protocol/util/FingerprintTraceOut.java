package com.elara.protocol.util;

import com.elara.debug.Debug;

public final class FingerprintTraceOut implements FingerprintTrace {
	
    @Override
    public void step(String token) {
        Debug.get().i("elara.fingerprint", token);
    }

}
