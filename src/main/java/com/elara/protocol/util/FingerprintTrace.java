package com.elara.protocol.util;

/**
 * Receives the canonical hashing tokens in the exact order they are fed into the digest.
 * Useful for debugging and unit tests.
 */
public interface FingerprintTrace {
    void step(String token);
}