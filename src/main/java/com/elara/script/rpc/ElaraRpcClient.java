package com.elara.script.rpc;

import com.elara.protocol.ElaraScriptPreloader;
import com.elara.protocol.util.StateFingerprint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Elara RPC client (no CLI).
 *
 * - reconnect-per-request transport
 * - system.ready establishes server sessionId/sessionKey
 * - legacy patch chaining supported
 * - deterministic client state tracking + fingerprint verification
 * - poll/follow with callback
 *
 * Java 8 compatible.
 */
public final class ElaraRpcClient implements Closeable {

    private static final ObjectMapper om = new ObjectMapper();
    private static final int MAX_FRAME = 32 * 1024 * 1024;

    // -----------------------------
    // Configuration
    // -----------------------------
    private final String host;
    private final int port;

    private final Path entryScriptPathAbs;
    private final Path scriptsRootAbs;
    private final String entryLogical;     // relative include key
    private final String appScriptText;    // events.es source

    // -----------------------------
    // Server session routing (new protocol)
    // -----------------------------
    private String sessionId;
    private String sessionKey;

    // -----------------------------
    // Legacy client-side patch chain (older protocol)
    // -----------------------------
    private ArrayNode lastPatch;
    private String lastFingerprint;
    private long cursor;

    // Optional forced full sync (legacy)
    private String nextStateJson;
    private ArrayNode nextPatchOverride;

    // -----------------------------
    // Deterministic local state tracking
    // -----------------------------
    private final Map<String, Object> trackedStateRaw = new LinkedHashMap<String, Object>();
    private String trackedFingerprint;
    private boolean verifyFingerprints = true;

    // -----------------------------
    // Follow thread
    // -----------------------------
    private Thread followThread;
    private final AtomicBoolean followRunning = new AtomicBoolean(false);

    // -----------------------------
    // Hooks
    // -----------------------------

    /** Called after any dispatchEvent response (ready or send) that contains engine "commands". */
    public interface CommandSink {
        void onCommands(String origin, JsonNode commandsArray) throws Exception;
    }

    /** Called when pollEvents returns events (each event JsonNode). */
    public interface EventSink {
        void onEvent(JsonNode event) throws Exception;
    }

    /** Optional debug log sink. */
    public interface LogSink {
        void log(String line);
    }

    private CommandSink commandSink;
    private EventSink eventSink;
    private LogSink logSink;

    // -----------------------------
    // Construction
    // -----------------------------

    public ElaraRpcClient(String host, int port, Path entryScriptPath, Path scriptsRoot) throws IOException {
        this.host = host;
        this.port = port;

        this.entryScriptPathAbs = entryScriptPath.toAbsolutePath().normalize();
        this.scriptsRootAbs = scriptsRoot.toAbsolutePath().normalize();

        this.entryLogical = this.scriptsRootAbs.relativize(this.entryScriptPathAbs)
                .toString().replace('\\', '/');

        this.appScriptText = readUtf8File(this.entryScriptPathAbs);

        resetLocalTracking();

        this.trackedFingerprint = StateFingerprint.fingerprintRawState(trackedStateRaw);
    }

    public void setCommandSink(CommandSink sink) { this.commandSink = sink; }
    public void setEventSink(EventSink sink) { this.eventSink = sink; }
    public void setLogSink(LogSink sink) { this.logSink = sink; }

    public void setVerifyFingerprints(boolean on) { this.verifyFingerprints = on; }
    public boolean isVerifyFingerprints() { return verifyFingerprints; }

    public String getSessionId() { return sessionId; }
    public String getSessionKey() { return sessionKey; }

    public long getCursor() { return cursor; }

    /** Unmodifiable view of client-tracked state (JSON-safe raw map). */
    public Map<String, Object> getTrackedStateView() {
        return Collections.unmodifiableMap(trackedStateRaw);
    }

    public String getTrackedFingerprint() { return trackedFingerprint; }

    // -----------------------------
    // Public operations
    // -----------------------------

    /**
     * Send system.ready. Stores sessionId/sessionKey if returned.
     * Also applies patch->trackedState and emits commands to CommandSink.
     */
    public JsonNode ready(Long tsMillis) throws Exception {
        // Reset per-ready state
        lastPatch = null;
        lastFingerprint = null;
        cursor = 0;
        nextStateJson = null;
        nextPatchOverride = null;

        trackedStateRaw.clear();
        trackedFingerprint = StateFingerprint.fingerprintRawState(trackedStateRaw);

        ElaraScriptPreloader preloader = new ElaraScriptPreloader(
                new ElaraScriptPreloader.PathResolver() {
                    @Override public Path resolve(String normalized) {
                        return scriptsRootAbs.resolve(normalized);
                    }
                }
        );

        Map<String, Object> readyPayload = preloader.buildReadyPayload(
                entryLogical,
                tsMillis,
                null
        );

        ObjectNode args = om.createObjectNode();
        args.put("appScript", appScriptText);

        ObjectNode ev = om.createObjectNode();
        ev.put("type", "system");
        ev.put("target", "ready");
        ev.set("value", om.valueToTree(readyPayload));
        args.set("event", ev);

        JsonNode resp = rpcCall("dispatchEvent", args);

        storeSessionFromResponse(resp);
        applyTrackingFromDispatchResponse(resp);

        JsonNode result = resp.get("result");
        if (result != null && result.isObject()) {
            emitCommandsIfAny(result, "event_system_ready");
        }

        return resp;
    }

    /**
     * Dispatch an event. Applies patch->trackedState, verifies fingerprint, emits commands.
     */
    public JsonNode dispatch(String type, String target, Object valueObj) throws Exception {
        ObjectNode args = om.createObjectNode();
        args.put("appScript", appScriptText);

        // legacy full sync override
        if (nextStateJson != null && !nextStateJson.trim().isEmpty()) {
            args.put("stateJson", nextStateJson);
            nextStateJson = null;
        } else {
            ArrayNode patchToSend = null;

            if (nextPatchOverride != null) {
                patchToSend = nextPatchOverride;
                nextPatchOverride = null;
            } else if (lastPatch != null) {
                patchToSend = lastPatch;
            }

            if (patchToSend != null) args.set("patch", patchToSend);
        }

        ObjectNode ev = om.createObjectNode();
        ev.put("type", type);
        ev.put("target", target);
        ev.set("value", valueObj == null ? NullNode.instance : om.valueToTree(valueObj));

        if (sessionId != null) ev.put("sessionId", sessionId);
        if (sessionKey != null) ev.put("sessionKey", sessionKey);

        args.set("event", ev);

        JsonNode resp = rpcCall("dispatchEvent", args);

        // store legacy fields if present
        JsonNode ok = resp.get("ok");
        if (ok != null && ok.isBoolean() && ok.booleanValue()) {
            JsonNode result = resp.get("result");
            if (result != null && result.isObject()) {
                JsonNode p = result.get("patch");
                if (p != null && p.isArray()) lastPatch = (ArrayNode) p;

                JsonNode fp = result.get("fingerprint");
                if (fp != null && fp.isTextual()) lastFingerprint = fp.asText();

                JsonNode sid = result.get("sessionId");
                if (sid != null && sid.isTextual()) sessionId = sid.asText();

                JsonNode sk = result.get("sessionKey");
                if (sk != null && sk.isTextual() && (sessionKey == null || sessionKey.isEmpty())) sessionKey = sk.asText();
            }
        }

        applyTrackingFromDispatchResponse(resp);

        if (ok != null && ok.isBoolean() && ok.booleanValue()) {
            JsonNode result = resp.get("result");
            if (result != null && result.isObject()) {
                emitCommandsIfAny(result, "event_" + type + "_" + target);
            }
        }

        return resp;
    }

    /** Poll events once. Calls EventSink for each returned event node. */
    public JsonNode pollOnce() throws Exception {
        ObjectNode args = om.createObjectNode();
        args.put("cursor", cursor);

        JsonNode resp = rpcCall("pollEvents", args);

        JsonNode ok = resp.get("ok");
        if (ok != null && ok.isBoolean() && ok.booleanValue()) {
            JsonNode result = resp.get("result");
            if (result != null && result.isObject()) {
                cursor = result.path("cursor").asLong(cursor);

                JsonNode events = result.path("events");
                if (eventSink != null && events != null && events.isArray()) {
                    for (JsonNode ev : events) {
                        eventSink.onEvent(ev);
                    }
                }
            }
        }
        return resp;
    }

    /**
     * Start background follow thread.
     * It polls and forwards events to EventSink.
     */
    public void startFollow(final int intervalMs) {
        stopFollow();
        followRunning.set(true);

        followThread = new Thread(new Runnable() {
            @Override public void run() {
                while (followRunning.get()) {
                    try {
                        pollOnce();
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException ie) {
                        return;
                    } catch (Throwable t) {
                        dbg("FOLLOW_ERROR: " + (t.getMessage() == null ? t.toString() : t.getMessage()));
                        try { Thread.sleep(Math.max(250, intervalMs)); } catch (InterruptedException ignored) { return; }
                    }
                }
            }
        }, "elara-follow");

        followThread.setDaemon(true);
        followThread.start();
    }

    public void stopFollow() {
        followRunning.set(false);
        if (followThread != null) {
            followThread.interrupt();
            followThread = null;
        }
    }

    /** Force full sync on next dispatch (legacy servers only). */
    public void setNextStateJson(String stateJson) {
        this.nextStateJson = stateJson;
    }

    /** Set patch override for next dispatch (legacy servers only). */
    public void setNextPatchOverride(ArrayNode patch) {
        this.nextPatchOverride = patch;
    }

    /** Reset only local client tracking (does not affect server session). */
    public void resetClientSession() {
        sessionId = null;
        sessionKey = null;
        lastPatch = null;
        lastFingerprint = null;
        cursor = 0;
        nextStateJson = null;
        nextPatchOverride = null;

        resetLocalTracking();
    }

    @Override
    public void close() {
        stopFollow();
    }

    // -----------------------------
    // Internal: commands emission
    // -----------------------------

    private void emitCommandsIfAny(JsonNode result, String origin) throws Exception {
        if (result == null || !result.isObject()) return;
        JsonNode cmds = result.get("commands");
        if (cmds == null || !cmds.isArray()) return;
        if (commandSink != null) commandSink.onCommands(origin, cmds);
    }

    // -----------------------------
    // Tracking + fingerprint verification
    // -----------------------------

    private void applyTrackingFromDispatchResponse(JsonNode resp) {
        JsonNode ok = resp.get("ok");
        if (ok == null || !ok.isBoolean() || !ok.booleanValue()) return;

        JsonNode result = resp.get("result");
        if (result == null || !result.isObject()) return;

        JsonNode patchNode = result.get("patch");
        JsonNode serverFpNode = result.get("fingerprint");
        String serverFp = (serverFpNode != null && serverFpNode.isTextual()) ? serverFpNode.asText() : null;

        applyPatchToState(trackedStateRaw, patchNode);
        trackedFingerprint = StateFingerprint.fingerprintRawState(trackedStateRaw);

        if (verifyFingerprints && serverFp != null) {
            if (!serverFp.equals(trackedFingerprint)) {
                dbg("[TRACK] MISMATCH server=" + serverFp + " local=" + trackedFingerprint);
            } else {
                dbg("[TRACK] OK fingerprint=" + trackedFingerprint + " keys=" + trackedStateRaw.size());
            }
        }
    }

    private void storeSessionFromResponse(JsonNode resp) {
        JsonNode ok = resp.get("ok");
        if (ok == null || !ok.isBoolean() || !ok.booleanValue()) return;

        JsonNode result = resp.get("result");
        if (result == null || !result.isObject()) return;

        JsonNode sid = result.get("sessionId");
        JsonNode sk = result.get("sessionKey");

        if (sid != null && sid.isTextual()) sessionId = sid.asText();
        if (sk != null && sk.isTextual()) sessionKey = sk.asText();

        if (sessionId != null && sessionKey != null) {
            dbg("Server session established: sessionId=" + sessionId + " sessionKey=(set)");
        }
    }

    private void resetLocalTracking() {
        trackedStateRaw.clear();
        trackedFingerprint = StateFingerprint.fingerprintRawState(trackedStateRaw);
    }

    // -----------------------------
    // RPC
    // -----------------------------

    private JsonNode rpcCall(String method, JsonNode argsNode) throws Exception {
        ObjectNode req = om.createObjectNode();
        req.put("id", new Random().nextInt(Integer.MAX_VALUE));
        req.put("method", method);
        req.set("args", argsNode);

        Socket socket = new Socket(host, port);
        try {
            socket.setTcpNoDelay(true);

            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            writeFrame(out, om.writeValueAsBytes(req));
            out.flush();

            byte[] respBytes = readFrame(in);
            return om.readTree(respBytes);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        byte[] lenBuf = readFully(in, 4);
        int len = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).getInt();
        if (len < 0 || len > MAX_FRAME) throw new IOException("bad frame length: " + len);
        return readFully(in, len);
    }

    private static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        byte[] lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.length).array();
        out.write(lenBuf);
        out.write(payload);
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("EOF while reading " + n + " bytes");
            off += r;
        }
        return buf;
    }

    // -------------------------
    // Patch apply (new+legacy)
    // -------------------------

    private static void applyPatchToState(Map<String, Object> state, JsonNode patchNode) {
        if (patchNode == null || patchNode.isNull()) return;

        if (patchNode.isObject()) {
            JsonNode set = patchNode.get("set");
            if (set != null && set.isArray()) {
                for (JsonNode kv : set) {
                    if (!kv.isArray() || kv.size() < 2) continue;
                    String k = kv.get(0).asText();
                    Object v = om.convertValue(kv.get(1), Object.class);
                    state.put(k, v);
                }
            }
            JsonNode rem = patchNode.get("remove");
            if (rem != null && rem.isArray()) {
                for (JsonNode k : rem) {
                    if (k != null && k.isTextual()) state.remove(k.asText());
                }
            }
            return;
        }

        if (patchNode.isArray()) {
            for (JsonNode kv : patchNode) {
                if (!kv.isArray() || kv.size() < 2) continue;
                String k = kv.get(0).asText();
                JsonNode vNode = kv.get(1);
                if (vNode == null || vNode.isNull()) state.remove(k);
                else state.put(k, om.convertValue(vNode, Object.class));
            }
        }
    }

    private static String readUtf8File(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void dbg(String s) {
        if (logSink != null) logSink.log(s);
    }
}
