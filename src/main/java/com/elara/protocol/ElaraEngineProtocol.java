package com.elara.protocol;

import com.elara.protocol.util.StateDiff;
import com.elara.protocol.util.StateFingerprint;
import com.elara.protocol.util.JsonDeepCopy;

import com.elara.script.ElaraScript;
import com.elara.script.ElaraStateStore;
import com.elara.script.parser.EntryRunResult;
import com.elara.script.parser.ExecutionState;
import com.elara.script.parser.Environment;
import com.elara.script.parser.Value;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java protocol layer (no android.*, no flutter MethodCall/Result).
 *
 * IMPORTANT:
 *  - Only __global_state is persisted across calls.
 *  - Diff/fingerprint are computed ONLY over __global_state.
 *  - __commands is injected empty per-run and never persisted.
 *
 * NEW:
 *  - Protocol owns per-session ExecutionState:
 *      - liveInstances persist per session
 *      - env is updated per run (optional, useful for debugging)
 */
public final class ElaraEngineProtocol {

    /** Host must register native builtins into the engine (Android implementation can use Context). */
    public interface BuiltinsRegistrar {
        void register(ElaraScript engine);
    }

    /** Optional logger (bridge to Logcat in Android). */
    public interface Logger {
        void i(String tag, String msg);
        void w(String tag, String msg);
    }

    private final BuiltinsRegistrar builtins;
    private final Logger log;

    private static final Pattern INCLUDE =
            Pattern.compile("^\\s*#include\\s+\"([^\"]+)\"\\s*$");

    // -------------------------- Sessions --------------------------

    private static final class Session {
        final String sessionId;
        final String sessionKey;

        // per-session include cache (populated on system.ready)
        final Map<String, String> scriptCache = new ConcurrentHashMap<String, String>();

        // per-session execution state (instances + env)
        final ExecutionState execState = new ExecutionState();

        // per-session retained state (JSON-safe) == ONLY __global_state contents
        final Map<String, Object> stateRaw = new LinkedHashMap<String, Object>();
        String fingerprint = StateFingerprint.fingerprintRawState(stateRaw);

        Session(String sessionId, String sessionKey) {
            this.sessionId = sessionId;
            this.sessionKey = sessionKey;
        }
    }

    /** sessionId -> Session */
    private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();

    private final SecureRandom rng = new SecureRandom();

    public ElaraEngineProtocol(BuiltinsRegistrar builtins, Logger log) {
        if (builtins == null) throw new IllegalArgumentException("builtins is null");
        this.builtins = builtins;
        this.log = log;
    }

    // -------------------------- Public API --------------------------

    @SuppressWarnings("unchecked")
    public Map<String, Object> dispatchEvent(
            String appScript,
            Map<String, Object> event
    ) {
        if (appScript == null || appScript.trim().isEmpty()) {
            throw new IllegalArgumentException("appScript required");
        }
        if (event == null) {
            throw new IllegalArgumentException("event required");
        }

        Object t0 = event.get("type");
        Object target0 = event.get("target");
        String typeStr = (t0 == null) ? "" : String.valueOf(t0);
        String targetStr = (target0 == null) ? "" : String.valueOf(target0);

        boolean isSystemReady = "system".equals(typeStr) && "ready".equals(targetStr);

        // Resolve or create session
        Session session;
        boolean createdSession = false;

        if (isSystemReady) {
            session = createSession();
            createdSession = true;
            if (log != null) log.i("ElaraProtocol", "Created new session on system.ready id=" + session.sessionId);
        } else {
            Object sid = event.get("sessionId");
            Object sk  = event.get("sessionKey");
            if (!(sid instanceof String) || ((String) sid).trim().isEmpty()) {
                throw new IllegalArgumentException("Missing event.sessionId (required for non-system.ready)");
            }
            if (!(sk instanceof String) || ((String) sk).trim().isEmpty()) {
                throw new IllegalArgumentException("Missing event.sessionKey (required for non-system.ready)");
            }
            session = getSessionOrThrow((String) sid, (String) sk);
        }

        // ------------------------------------------------------------
        // IMPORTANT: per-session execution must be serialized because:
        //  - session.execState.liveInstances is mutable and persists
        //  - session.stateRaw is mutable and is the persisted slice
        // ------------------------------------------------------------
        synchronized (session) {

            // ---------------------------
            // Baseline is ONLY session-owned globals
            // ---------------------------
            Map<String, Object> beforeGlobals = JsonDeepCopy.deepCopyMap(session.stateRaw);

            // ---------------------------
            // Build env for the script
            // ---------------------------
            Map<String, Value> initialEnv = new LinkedHashMap<String, Value>();

            // Always expose globals as a map
            initialEnv.put("__global_state", Value.map(coerceMapToValueMap(beforeGlobals)));

            // Also expose each key as a top-level var (optional but handy)
            for (Map.Entry<String, Object> e : beforeGlobals.entrySet()) {
                initialEnv.put(e.getKey(), coerceAnyToValue(e.getValue()));
            }

            // Always inject fresh commands per-run (no persistence)
            initialEnv.put("__commands", Value.array(new ArrayList<Value>()));

            // Inject latest event into env
            Object value = event.get("value");

            initialEnv.put("__event_type", Value.string(typeStr));
            initialEnv.put("__event_target", Value.string(targetStr));
            initialEnv.put("__event_value", coerceAnyToValue(value));

            List<Value> tuple = new ArrayList<Value>(3);
            tuple.add(initialEnv.get("__event_type"));
            tuple.add(initialEnv.get("__event_target"));
            tuple.add(initialEnv.get("__event_value"));
            initialEnv.put("__event", Value.array(tuple));

            // Run engine
            ElaraScript engine = new ElaraScript();
            builtins.register(engine);

            Value ev = initialEnv.get("__event");
            if (ev == null || ev.getType() != Value.Type.ARRAY) {
                throw new RuntimeException("__event missing or not ARRAY");
            }
            List<Value> evA = ev.asArray();
            if (evA.size() != 3) {
                throw new RuntimeException("__event must be [type, target, payload]");
            }

            List<Value> args = Arrays.asList(evA.get(0), evA.get(1), evA.get(2));
            Value payload = evA.get(2);

            // cache includes on system.ready (payload may be MAP or legacy PAIRS array)
            if (isSystemReady) {
                if (log != null) log.i("ElaraProtocol", "cacheScriptsFromPayload(system.ready) session=" + session.sessionId);
                cacheScriptsFromPayload(session, payload);
            }

            // preprocess includes (after caching)
            String processed = preprocessIncludes(session, appScript);

            if (processed.indexOf("#include") >= 0) {
                throw new RuntimeException("Preprocess failed: #include still present");
            }

            // Routing
            String candidate = "event_" + sanitizeIdent(typeStr) + "_" + sanitizeIdent(targetStr);
            String fallback = "event_router";
            String entry = engine.hasUserFunction(processed, candidate) ? candidate : fallback;

            // ✅ UPDATED CALL SIGNATURE: pass liveInstances + env
            EntryRunResult rr = engine.runWithEntryResult(
                    processed,
                    entry,
                    args,
                    session.execState.liveInstances,
                    initialEnv
            );

	         // Keep env in ExecutionState for debug/tooling parity (not persisted by protocol).
	         // rr.env() is a snapshot; rebuild a root frame for the session execState.
	         session.execState.env = new Environment(session.execState, extractRootEnvVars(rr.env()));

            // Capture env -> JSON-safe (existing tool; protocol doesn’t need engine changes)
            ElaraStateStore outStore = new ElaraStateStore().captureEnv(rr.env());
            Map<String, Object> raw = outStore.toRawInputs();

            // ---------------------------
            // Extract AFTER globals ONLY
            // ---------------------------
            Map<String, Object> afterGlobals = asStringObjectMap(raw.get("__global_state"));

            // diff + fingerprint ONLY over globals
            Map<String, Object> diff = StateDiff.diff(beforeGlobals, afterGlobals).toPatchObject();
            String fp = StateFingerprint.fingerprintRawState(afterGlobals);

            // Persist ONLY globals back to session
            session.stateRaw.clear();
            session.stateRaw.putAll(afterGlobals);
            session.fingerprint = fp;

            // commands are ephemeral; return them but never persist
            List<Object> commandsList = extractCommandsList(raw.get("__commands"));

            Map<String, Object> resp = new LinkedHashMap<String, Object>();
            resp.put("patch", diff);
            resp.put("commands", commandsList);
            resp.put("fingerprint", fp);

            resp.put("sessionId", session.sessionId);
            if (createdSession) {
                resp.put("sessionKey", session.sessionKey);
            }

            return (Map<String, Object>) sanitizeForChannel(resp);
        }
    }

    // -------------------------- Session management --------------------------

    private Session createSession() {
        String id = UUID.randomUUID().toString();
        String key = randomSessionKey();
        Session s = new Session(id, key);
        sessions.put(id, s);
        return s;
    }

    private Session getSessionOrThrow(String sessionId, String sessionKey) {
        Session s = sessions.get(sessionId);
        if (s == null) throw new IllegalArgumentException("Unknown sessionId: " + sessionId);
        if (!s.sessionKey.equals(sessionKey)) throw new IllegalArgumentException("Invalid sessionKey for sessionId: " + sessionId);
        return s;
    }

    private String randomSessionKey() {
        byte[] buf = new byte[32];
        rng.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    // -------------------------- Includes cache --------------------------

    private static Value pairsGet(Value pairs, String key) {
        if (pairs == null || pairs.getType() != Value.Type.ARRAY) return null;
        for (Value kv : pairs.asArray()) {
            List<Value> p = kv.asArray();
            if (p.size() == 2 && key.equals(p.get(0).asString())) return p.get(1);
        }
        return null;
    }

    private void cacheScriptsFromPayload(Session session, Value payload) {
        if (payload == null || session == null) return;

        Value scriptsV = null;

        if (payload.getType() == Value.Type.MAP) {
            Map<String, Value> m = payload.asMap();
            scriptsV = (m == null) ? null : m.get("scripts");
        } else if (payload.getType() == Value.Type.ARRAY) {
            scriptsV = pairsGet(payload, "scripts");
        }

        if (scriptsV == null || scriptsV.getType() != Value.Type.ARRAY) {
            if (log != null) log.w("ElaraProtocol", "No scripts to load from payload");
            return;
        }

        for (Value kv : scriptsV.asArray()) {
            List<Value> p = kv.asArray();
            if (p.size() < 2) continue;
            String path = p.get(0).asString();
            String src  = p.get(1).asString();
            session.scriptCache.put(path, src);
        }
    }

    private String preprocessIncludes(Session session, String src) {
        return preprocessIncludes(session, src, new ArrayDeque<String>());
    }

    private String preprocessIncludes(Session session, String src, Deque<String> stack) {
        StringBuilder out = new StringBuilder(src.length() + 256);
        String[] lines = src.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = INCLUDE.matcher(line);
            if (!m.matches()) {
                out.append(line).append('\n');
                continue;
            }

            String path = m.group(1);

            if (stack.contains(path)) {
                throw new RuntimeException("Include cycle: " + stack + " -> " + path);
            }

            String included = (session == null) ? null : session.scriptCache.get(path);
            if (included == null) {
                throw new RuntimeException("Include not found in script cache (session " +
                        (session == null ? "null" : session.sessionId) + "): " + path);
            }

            stack.addLast(path);
            out.append(preprocessIncludes(session, included, stack));
            stack.removeLast();
        }

        return out.toString();
    }

    // -------------------------- Helpers --------------------------

    private static String sanitizeIdent(String s) {
        if (s == null) return "";
        String out = s.replaceAll("[^A-Za-z0-9_]", "_");
        if (!out.isEmpty() && Character.isDigit(out.charAt(0))) out = "_" + out;
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> extractCommandsList(Object v) {
        if (!(v instanceof List)) return new ArrayList<Object>();
        return (List<Object>) v;
    }

    private static Map<String, Value> coerceMapToValueMap(Map<String, Object> m) {
        Map<String, Value> out = new LinkedHashMap<String, Value>();
        if (m == null) return out;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            out.put(e.getKey(), coerceAnyToValue(e.getValue()));
        }
        return out;
    }

    private static Object sanitizeForChannel(Object x) {
        if (x == null) return null;

        if (x instanceof Boolean || x instanceof Integer || x instanceof Long ||
                x instanceof Double || x instanceof Float || x instanceof String) {
            return x;
        }

        if (x instanceof byte[]) {
            byte[] bb = (byte[]) x;
            ArrayList<Integer> out = new ArrayList<Integer>(bb.length);
            for (int i = 0; i < bb.length; i++) out.add(((int) bb[i]) & 0xFF);
            return out;
        }

        if (x instanceof List) {
            List<?> src = (List<?>) x;
            ArrayList<Object> out = new ArrayList<Object>(src.size());
            for (Object it : src) out.add(sanitizeForChannel(it));
            return out;
        }

        if (x instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) x;
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object k = e.getKey();
                if (!(k instanceof String)) {
                    throw new RuntimeException("Channel Map key must be String, got: " + (k == null ? "null" : k.getClass()));
                }
                out.put((String) k, sanitizeForChannel(e.getValue()));
            }
            return out;
        }

        throw new RuntimeException("Unsupported channel type: " + x.getClass().getName());
    }

    /**
     * Supported: null, boolean, number, string, List (array), numeric List<List> (matrix), Map (map)
     */
    @SuppressWarnings("unchecked")
    private static Value coerceAnyToValue(Object v) {
        if (v == null) return Value.nil();
        if (v instanceof Boolean) return Value.bool(((Boolean) v).booleanValue());
        if (v instanceof Number) return Value.number(((Number) v).doubleValue());
        if (v instanceof String) return Value.string((String) v);

        if (v instanceof List) {
            List<?> src = (List<?>) v;

            // matrix only if truly numeric/bool
            if (!src.isEmpty() && src.get(0) instanceof List) {
                boolean allRows = true;
                boolean numericMatrix = true;

                for (Object row : src) {
                    if (!(row instanceof List)) { allRows = false; break; }
                    List<?> r = (List<?>) row;
                    for (Object item : r) {
                        if (item == null) { numericMatrix = false; break; }
                        if (!(item instanceof Number) && !(item instanceof Boolean)) {
                            numericMatrix = false;
                            break;
                        }
                    }
                    if (!numericMatrix) break;
                }

                if (allRows && numericMatrix) {
                    List<List<Value>> rows = new ArrayList<List<Value>>();
                    for (Object row : src) {
                        List<?> r = (List<?>) row;
                        List<Value> rv = new ArrayList<Value>(r.size());
                        for (Object item : r) rv.add(coerceAnyToValue(item));
                        rows.add(rv);
                    }
                    return Value.matrix(rows);
                }
            }

            List<Value> out = new ArrayList<Value>(src.size());
            for (Object item : src) out.add(coerceAnyToValue(item));
            return Value.array(out);
        }

        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            LinkedHashMap<String, Value> out = new LinkedHashMap<String, Value>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object k = e.getKey();
                if (!(k instanceof String)) throw new RuntimeException("Map keys must be strings");
                out.put((String) k, coerceAnyToValue(e.getValue()));
            }
            return Value.map(out);
        }

        throw new RuntimeException("Unsupported value type: " + v.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringObjectMap(Object v) {
        if (v == null) return new LinkedHashMap<String, Object>();
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("__global_state must be a Map, got: " + v.getClass().getName());
        }
        Map<?, ?> m = (Map<?, ?>) v;

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            Object k = e.getKey();
            if (!(k instanceof String)) {
                throw new IllegalArgumentException("__global_state key must be String, got: " +
                        (k == null ? "null" : k.getClass().getName()));
            }
            out.put((String) k, e.getValue());
        }
        return out;
    }
    
    /**
     * Extract the inner-most frame "vars" map from a snapshot returned by the engine.
     * Snapshot format conventionally contains: environments: [ { vars: {...}, this: {...}? }, ... ]
     */
    private static Map<String, Value> extractRootEnvVars(Map<String, Value> snapshot) {
        Map<String, Value> out = new LinkedHashMap<String, Value>();
        if (snapshot == null) return out;

        Value envsV = snapshot.get("environments");
        if (envsV == null || envsV.getType() != Value.Type.ARRAY || envsV.asArray() == null) return out;

        List<Value> frames = envsV.asArray();
        if (frames.isEmpty()) return out;

        Value innerV = frames.get(frames.size() - 1);
        if (innerV == null || innerV.getType() != Value.Type.MAP || innerV.asMap() == null) return out;

        Value varsV = innerV.asMap().get("vars");
        if (varsV == null || varsV.getType() != Value.Type.MAP || varsV.asMap() == null) return out;

        out.putAll(varsV.asMap());
        return out;
    }

}
