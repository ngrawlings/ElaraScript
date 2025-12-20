package com.elara.script;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ElaraScriptPreloader {

    /** [path, source] */
    public static final class ScriptPair {
        public final String path;
        public final String source;

        public ScriptPair(String path, String source) {
            if (path == null) throw new IllegalArgumentException("path is null");
            if (source == null) throw new IllegalArgumentException("source is null");
            this.path = path;
            this.source = source;
        }

        /** JSON/codec-friendly view: ["path", "source"] */
        public List<Object> toList() {
            return List.of(path, source);
        }
    }

    @FunctionalInterface
    public interface PathNormalizer {
        String normalize(String p);
    }

    @FunctionalInterface
    public interface PathResolver {
        /** Resolve a logical include path to an on-disk path. */
        Path resolve(String normalizedPath);
    }

    private final Pattern includeLine;
    private final PathNormalizer normalizePath;
    private final PathResolver resolveOnDisk;

    /**
     * Default include regex matches single-line directives:
     *   #include "foo/bar.es"
     *
     * Mirrors Dart: RegExp(r'^\s*#include\s+"([^"]+)"\s*$', multiLine: true)
     */
    public ElaraScriptPreloader(PathResolver resolveOnDisk) {
        this(
                Pattern.compile("^\\s*#include\\s+\"([^\"]+)\"\\s*$", Pattern.MULTILINE),
                ElaraScriptPreloader::defaultNormalizePath,
                resolveOnDisk
        );
    }

    public ElaraScriptPreloader(Pattern includeLine, PathNormalizer normalizePath, PathResolver resolveOnDisk) {
        if (includeLine == null) throw new IllegalArgumentException("includeLine is null");
        if (normalizePath == null) throw new IllegalArgumentException("normalizePath is null");
        if (resolveOnDisk == null) throw new IllegalArgumentException("resolveOnDisk is null");

        this.includeLine = includeLine;
        this.normalizePath = normalizePath;
        this.resolveOnDisk = resolveOnDisk;
    }

    // ----------------------------
    // Defaults (match Dart intent)
    // ----------------------------

    /** Dart default: strip leading "assets/" if present. */
    public static String defaultNormalizePath(String p) {
        if (p == null) return "";
        if (p.startsWith("assets/")) return p.substring("assets/".length());
        return p;
    }

    // ----------------------------
    // Stage 0: parsing + loading
    // ----------------------------

    /** Extract direct includes from a script source (normalized paths). */
    public Set<String> extractIncludes(String src) {
        if (src == null) return Set.of();
        Set<String> out = new HashSet<>();
        Matcher m = includeLine.matcher(src);
        while (m.find()) {
            String raw = m.group(1);
            if (raw != null) out.add(normalizePath.normalize(raw));
        }
        return out;
    }

    /** Loads a script from disk as UTF-8 text. */
    public String loadText(String normalizedPath) throws IOException {
        Path p = resolveOnDisk.resolve(normalizedPath);
        byte[] bytes = Files.readAllBytes(p);
        // Mirrors Dart utf8.decode(..., allowMalformed: true)
        // Java doesn't have "allowMalformed" flag; this replacement is typical:
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** If you have raw bytes/ByteBuffer and want the same helper as Dart. */
    public static String bytesToUtf8(byte[] bytes) {
        if (bytes == null) return "";
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static String byteBufferToUtf8(ByteBuffer buf) {
        if (buf == null) return "";
        ByteBuffer slice = buf.slice();
        byte[] bytes = new byte[slice.remaining()];
        slice.get(bytes);
        return bytesToUtf8(bytes);
    }

    // ----------------------------
    // Stage 1: discover closure
    // ----------------------------

    /**
     * Discover transitive closure of includes from entry script.
     * Deterministic: returns sorted list of normalized paths.
     */
    public List<String> gatherAllScriptPaths(String entryScriptPath) throws IOException {
        String entry = normalizePath.normalize(entryScriptPath);

        Set<String> visited = new HashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        q.add(entry);

        while (!q.isEmpty()) {
            String path = q.removeFirst();
            if (!visited.add(path)) continue;

            String src = loadText(path);
            for (String dep : extractIncludes(src)) {
                if (!visited.contains(dep)) q.add(dep);
            }
        }

        ArrayList<String> out = new ArrayList<>(visited);
        Collections.sort(out); // deterministic
        return out;
    }

    // ----------------------------
    // Stage 2: load pairs
    // ----------------------------

    /**
     * Load every discovered script and return pairs: [[path, source], ...]
     * Guarantees every source is a String.
     */
    public List<ScriptPair> loadScriptsAsPairs(List<String> paths) throws IOException {
        ArrayList<String> sorted = new ArrayList<>(paths);
        Collections.sort(sorted);

        ArrayList<ScriptPair> pairs = new ArrayList<>(sorted.size());
        for (String p : sorted) {
            String src = loadText(p);
            pairs.add(new ScriptPair(p, src));
        }
        return pairs;
    }

    // ----------------------------
    // Ready payload builders
    // ----------------------------

    /**
     * Required baseline globals/state seeded into system.ready payload.
     * JSON/channel-safe only.
     */
    public static Map<String, Object> defaultReadyStructures() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("__global_state", new LinkedHashMap<String, Object>());
        m.put("__commands", new ArrayList<Object>());
        m.put("__patch", new ArrayList<Object>());
        return m;
    }

    /**
     * Build the standard system.ready payload:
     *   {
     *     "ts": <millis>,
     *     "scripts": [[path, source], ...],
     *     "__global_state": {},
     *     "__commands": [],
     *     "__patch": [],
     *     ...extra
     *   }
     *
     * Returned object is safe for JSON or Flutter StandardMessageCodec.
     */
    public Map<String, Object> buildReadyPayload(
            String entryScriptPath,
            Long tsMillis,
            Map<String, Object> extra
    ) throws IOException {

        List<String> allPaths = gatherAllScriptPaths(entryScriptPath);
        List<ScriptPair> scriptsPairs = loadScriptsAsPairs(allPaths);

        // Convert to JSON/codec shape: List<List<Object>> where each entry is [String, String]
        ArrayList<Object> scripts = new ArrayList<>(scriptsPairs.size());
        for (ScriptPair sp : scriptsPairs) {
            // defensive checks like Dart
            if (sp.path == null || sp.source == null) {
                throw new IllegalStateException("Script pair must be [String, String], got null");
            }
            scripts.add(sp.toList());
        }

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("ts", tsMillis != null ? tsMillis : System.currentTimeMillis());
        payload.put("scripts", scripts);
        payload.putAll(defaultReadyStructures());
        if (extra != null) payload.putAll(extra);

        Object sanitized = sanitizeForChannel(payload);
        if (!(sanitized instanceof Map)) {
            throw new IllegalStateException("Sanitized payload is not a Map");
        }

        // enforce Map<String,Object> keys
        Map<?, ?> sm = (Map<?, ?>) sanitized;
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : sm.entrySet()) {
            if (!(e.getKey() instanceof String)) {
                throw new IllegalStateException("Payload key must be String, got " +
                        (e.getKey() == null ? "null" : e.getKey().getClass().getName()));
            }
            out.put((String) e.getKey(), e.getValue());
        }
        return out;
    }

    // ----------------------------
    // Deep sanitize (codec safe)
    // ----------------------------

    /**
     * Deep-sanitize any payload for MethodChannel/EventChannel usage.
     * In Dart, ByteData must be converted to Uint8List because nested ByteData views can break.
     *
     * In Java, the equivalent danger types are byte[] and ByteBuffer crossing boundaries.
     * Here we convert:
     *  - byte[] -> List<Integer> (0..255)
     *  - ByteBuffer -> List<Integer>
     *
     * This keeps it JSON-safe and StandardMessageCodec-safe.
     */
    public static Object sanitizeForChannel(Object x) {
        if (x == null) return null;

        if (x instanceof Boolean || x instanceof Integer || x instanceof Long ||
                x instanceof Double || x instanceof Float || x instanceof String) {
            return x;
        }

        // byte[] / ByteBuffer are not safe nested for some transports; convert to List<Integer>
        if (x instanceof byte[]) {
            byte[] bb = (byte[]) x;
            ArrayList<Integer> out = new ArrayList<>(bb.length);
            for (byte b : bb) out.add(((int) b) & 0xFF);
            return out;
        }

        if (x instanceof ByteBuffer) {
            ByteBuffer buf = ((ByteBuffer) x).slice();
            byte[] bb = new byte[buf.remaining()];
            buf.get(bb);
            return sanitizeForChannel(bb);
        }

        if (x instanceof List) {
            List<?> src = (List<?>) x;
            ArrayList<Object> out = new ArrayList<>(src.size());
            for (Object it : src) out.add(sanitizeForChannel(it));
            return out;
        }

        if (x instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) x;
            LinkedHashMap<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object kk = sanitizeForChannel(e.getKey());
                Object vv = sanitizeForChannel(e.getValue());
                out.put(kk, vv);
            }
            return out;
        }

        throw new IllegalArgumentException("Unsupported channel type: " + x.getClass().getName());
    }
}
