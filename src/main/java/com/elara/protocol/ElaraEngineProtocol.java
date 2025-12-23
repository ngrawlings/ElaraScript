package com.elara.protocol;

import com.elara.protocol.util.StateDiff;
import com.elara.protocol.util.StateFingerprint;
import com.elara.protocol.util.JsonDeepCopy;

import com.elara.script.ElaraScript;
import com.elara.script.ElaraStateStore;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Pure-Java protocol layer (no android.*, no flutter MethodCall/Result).
 *
 * Responsibilities:
 *  - Own state per session (multiple apps running simultaneously)
 *  - Create a new session on event_system_ready (type=system,target=ready)
 *  - Return {sessionId, sessionKey} on system.ready so callers can route future events
 *  - Enforce sessionKey for isolation (apps cannot write into other sessions)
 *  - Inject __event globals
 *  - Include preprocessing (#include "...") using scripts cached from system.ready payload (per-session)
 *  - Route to event_<type>_<target> or fallback event_router
 *  - Run ElaraScript and return {patch, commands, fingerprint} sanitized for channel transport
 *
 * Session contract:
 *  - system.ready MUST be called first for a new app instance.
 *  - system.ready creates a new session and caches scripts from payload["scripts"].
 *  - Subsequent events MUST include sessionId + sessionKey in the event map
 *      (top-level keys "sessionId" and "sessionKey"), and will be routed to that session state.
 *
 * Notes:
 *  - State is JSON-safe: Map<String,Object> where Object is null/bool/num/string/List/Map
 *  - Keys starting with "__" are ignored for diffing (StateDiff) and should not be relied on as persisted app state.
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
        final Map<String, String> scriptCache = new ConcurrentHashMap<>();

        // per-session retained state (JSON-safe)
        final Map<String, Object> stateRaw = new LinkedHashMap<>();
        String fingerprint = StateFingerprint.fingerprintRawState(stateRaw);

        Session(String sessionId, String sessionKey) {
            this.sessionId = sessionId;
            this.sessionKey = sessionKey;
        }
    }

    /** sessionId -> Session */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private final SecureRandom rng = new SecureRandom();

    public ElaraEngineProtocol(BuiltinsRegistrar builtins, Logger log) {
        if (builtins == null) throw new IllegalArgumentException("builtins is null");
        this.builtins = builtins;
        this.log = log;
    }

    // -------------------------- Public API --------------------------

    /**
     * Dispatch an event into the script engine.
     *
     * Inputs:
     *  - appScript: the app source (may contain #include directives)
     *  - event: a JSON-safe map with at least {type,target,value}
     *     - For non-system.ready events, MUST also include:
     *         - sessionId: String
     *         - sessionKey: String
     *
     * Output (channel-safe map):
     *   {
     *     "patch": { "set": [[k,v],...], "remove": [k,...] },
     *     "commands": [...],
     *     "fingerprint": "<md5>",
     *     "sessionId": "<id>",
     *     "sessionKey": "<key>"   // only returned on system.ready by default (see below)
     *   }
     *
     * Security:
     *  - sessionKey is checked for all non-system.ready events.
     *  - system.ready always creates a NEW session and returns its key.
     */
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

        // read event identity early
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

        // --- Baseline is session-owned state (snapshot for diff) ---
        Map<String, Object> rawInputs = JsonDeepCopy.deepCopyMap(session.stateRaw);

        // Build env for script from baseline
        Map<String, ElaraScript.Value> initialEnv = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rawInputs.entrySet()) {
            initialEnv.put(e.getKey(), coerceAnyToValue(e.getValue()));
        }

        // Inject latest event into env
        Object value = event.get("value");

        initialEnv.put("__event_type", ElaraScript.Value.string(typeStr));
        initialEnv.put("__event_target", ElaraScript.Value.string(targetStr));
        initialEnv.put("__event_value", coerceAnyToValue(value));

        List<ElaraScript.Value> tuple = new ArrayList<>();
        tuple.add(initialEnv.get("__event_type"));
        tuple.add(initialEnv.get("__event_target"));
        tuple.add(initialEnv.get("__event_value"));
        initialEnv.put("__event", ElaraScript.Value.array(tuple));

        // Run engine
        ElaraScript engine = new ElaraScript();
        builtins.register(engine);

        // We pass __event as args = [type,target,value]
        ElaraScript.Value ev = initialEnv.get("__event");
        if (ev == null || ev.getType() != ElaraScript.Value.Type.ARRAY) {
            throw new RuntimeException("__event missing or not ARRAY");
        }
        List<ElaraScript.Value> evA = ev.asArray();
        if (evA.size() != 3) {
            throw new RuntimeException("__event must be [type, target, payload]");
        }

        List<ElaraScript.Value> args = List.of(evA.get(0), evA.get(1), evA.get(2));
        ElaraScript.Value payload = evA.get(2);

        // cache includes on system.ready (payload may be MAP or legacy PAIRS array)
        if (isSystemReady) {
            if (log != null) log.i("ElaraProtocol", "cacheScriptsFromPayload(system.ready) session=" + session.sessionId);
            cacheScriptsFromPayload(session, payload);
        }

        // preprocess includes (after caching)
        String processed = preprocessIncludes(session, appScript);

        // Guard: engine must never see a raw include directive
        if (processed.contains("#include")) {
            throw new RuntimeException("Preprocess failed: #include still present");
        }

        // Routing
        String candidate = "event_" + sanitizeIdent(typeStr) + "_" + sanitizeIdent(targetStr);
        String fallback = "event_router";
        String entry = engine.hasUserFunction(processed, candidate) ? candidate : fallback;

        ElaraScript.EntryRunResult rr = engine.runWithEntryResult(processed, entry, args, initialEnv);

        ElaraStateStore outStore = new ElaraStateStore().captureEnv(rr.env());

        // Extract JSON-safe view of captured env
        Map<String, Object> raw = outStore.toRawInputs();

        // ✅ Diff/fingerprint only the actual persistent state map
        Map<String, Object> beforeGlobals = asStringObjectMap(rawInputs.get("__global_state"));
        Map<String, Object> afterGlobals  = asStringObjectMap(raw.get("__global_state"));

        Map<String, Object> diff = StateDiff.diff(beforeGlobals, afterGlobals).toPatchObject();
        String fp = StateFingerprint.fingerprintRawState(afterGlobals);

        // Update session-owned state to match the engine output
        synchronized (session) {
            session.stateRaw.clear();
            session.stateRaw.putAll(raw);
            session.fingerprint = fp;
        }

        Object cmdsObj = raw.get("__commands");
        List<Object> commandsList = (cmdsObj instanceof List) ? (List<Object>) cmdsObj : new ArrayList<>();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("patch", diff);
        resp.put("commands", commandsList);
        resp.put("fingerprint", fp);

        // Always return the sessionId so the caller can route subsequent events.
        resp.put("sessionId", session.sessionId);

        // Only return the sessionKey on system.ready (bootstrap); don't leak it on every call by default.
        if (createdSession) {
            resp.put("sessionKey", session.sessionKey);
        }

        // IMPORTANT: sanitize before crossing platform channel
        return (Map<String, Object>) sanitizeForChannel(resp);
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
        if (s == null) {
            throw new IllegalArgumentException("Unknown sessionId: " + sessionId);
        }
        if (!s.sessionKey.equals(sessionKey)) {
            throw new IllegalArgumentException("Invalid sessionKey for sessionId: " + sessionId);
        }
        return s;
    }

    private String randomSessionKey() {
        byte[] buf = new byte[32]; // 256-bit
        rng.nextBytes(buf);
        // URL-safe, no padding
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    // -------------------------- Includes cache --------------------------

    private static ElaraScript.Value pairsGet(ElaraScript.Value pairs, String key) {
        if (pairs == null || pairs.getType() != ElaraScript.Value.Type.ARRAY) return null;
        for (ElaraScript.Value kv : pairs.asArray()) {
            var p = kv.asArray();
            if (p.size() == 2 && key.equals(p.get(0).asString())) return p.get(1);
        }
        return null;
    }

    private void cacheScriptsFromPayload(Session session, ElaraScript.Value payload) {
        if (payload == null || session == null) return;

        ElaraScript.Value scriptsV = null;

        if (payload.getType() == ElaraScript.Value.Type.MAP) {
            Map<String, ElaraScript.Value> m = payload.asMap();
            scriptsV = (m == null) ? null : m.get("scripts");
        } else if (payload.getType() == ElaraScript.Value.Type.ARRAY) {
            scriptsV = pairsGet(payload, "scripts"); // legacy
        }

        if (scriptsV == null || scriptsV.getType() != ElaraScript.Value.Type.ARRAY) {
            if (log != null) log.w("ElaraProtocol", "No scripts to load from payload");
            return;
        }

        for (ElaraScript.Value kv : scriptsV.asArray()) {
            var p = kv.asArray();
            if (p.size() < 2) continue;
            String path = p.get(0).asString();
            String src  = p.get(1).asString();
            session.scriptCache.put(path, src);
        }
    }

    private String preprocessIncludes(Session session, String src) {
        return preprocessIncludes(session, src, new ArrayDeque<>());
    }

    private String preprocessIncludes(Session session, String src, Deque<String> stack) {
        StringBuilder out = new StringBuilder(src.length() + 256);
        String[] lines = src.split("\n", -1);

        for (String line : lines) {
            var m = INCLUDE.matcher(line);
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

    private static Object sanitizeForChannel(Object x) {
        if (x == null) return null;

        if (x instanceof Boolean || x instanceof Integer || x instanceof Long ||
                x instanceof Double || x instanceof Float || x instanceof String) {
            return x;
        }

        // IMPORTANT: do NOT return byte[] or ByteBuffer
        if (x instanceof byte[]) {
            byte[] bb = (byte[]) x;
            ArrayList<Integer> out = new ArrayList<>(bb.length);
            for (byte b : bb) out.add(((int) b) & 0xFF);
            return out;
        }

        if (x instanceof java.nio.ByteBuffer) {
            java.nio.ByteBuffer buf = (java.nio.ByteBuffer) x;
            byte[] bb = new byte[buf.remaining()];
            buf.slice().get(bb);
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
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
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
    private static ElaraScript.Value coerceAnyToValue(Object v) {
        if (v == null) return ElaraScript.Value.nil();
        if (v instanceof Boolean) return ElaraScript.Value.bool((Boolean) v);
        if (v instanceof Number) return ElaraScript.Value.number(((Number) v).doubleValue());
        if (v instanceof String) return ElaraScript.Value.string((String) v);

        if (v instanceof List) {
            List<?> src = (List<?>) v;

            // matrix only if truly numeric/bool
            if (!src.isEmpty() && src.get(0) instanceof List) {
                boolean allRows = true;
                boolean numericMatrix = true;

                for (Object row : src) {
                    if (!(row instanceof List)) { allRows = false; break; }
                    for (Object item : (List<?>) row) {
                        if (item == null) { numericMatrix = false; break; }
                        if (!(item instanceof Number) && !(item instanceof Boolean)) {
                            numericMatrix = false;
                            break;
                        }
                    }
                    if (!numericMatrix) break;
                }

                if (allRows && numericMatrix) {
                    List<List<ElaraScript.Value>> rows = new ArrayList<>();
                    for (Object row : src) {
                        List<?> r = (List<?>) row;
                        List<ElaraScript.Value> rv = new ArrayList<>(r.size());
                        for (Object item : r) rv.add(coerceAnyToValue(item));
                        rows.add(rv);
                    }
                    return ElaraScript.Value.matrix(rows);
                }
            }

            // array
            List<ElaraScript.Value> out = new ArrayList<>(src.size());
            for (Object item : src) out.add(coerceAnyToValue(item));
            return ElaraScript.Value.array(out);
        }

        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            java.util.HashMap<String, ElaraScript.Value> out = new java.util.HashMap<>();
            for (var e : m.entrySet()) {
                Object k = e.getKey();
                if (!(k instanceof String)) throw new RuntimeException("Map keys must be strings");
                out.put((String) k, coerceAnyToValue(e.getValue()));
            }
            return ElaraScript.Value.map(out);
        }

        throw new RuntimeException("Unsupported value type: " + v.getClass().getName());
    }
    
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringObjectMap(Object v) {
        if (v == null) return Collections.emptyMap();
        if (!(v instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("__global_state must be a Map, got: " + v.getClass().getName());
        }
        Map<?, ?> m = (Map<?, ?>) v;
        // validate string keys + cast
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            Object k = e.getKey();
            if (!(k instanceof String)) {
                throw new IllegalArgumentException("__global_state key must be String, got: " + (k == null ? "null" : k.getClass().getName()));
            }
            out.put((String) k, (Object) e.getValue());
        }
        return out;
    }

}