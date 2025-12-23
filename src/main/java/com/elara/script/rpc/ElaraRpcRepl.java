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
import java.nio.file.Paths;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent stdin REPL for Elara RPC.
 *
 * Java 8+ compatible (works on Java 15 and below).
 *
 * Transport is stateless (reconnect per request).
 *
 * Session routing (server-side):
 *  - system.ready creates a new server session and returns {sessionId, sessionKey}
 *  - client stores sessionId/sessionKey and includes them on subsequent dispatches
 *
 * Legacy client-side patch chaining:
 *  - kept for compatibility with older servers
 *
 * Client-side state tracking + fingerprint verification:
 *  - maintains a local JSON-safe state map and applies server patch
 *  - recomputes fingerprint using the same StateFingerprint class as the server
 *  - compares local vs server fingerprint after every dispatch
 */
public final class ElaraRpcRepl {

    private static final ObjectMapper om = new ObjectMapper();
    private static final int MAX_FRAME = 32 * 1024 * 1024;

    // -----------------------------
    // Session state (client tracking)
    // -----------------------------
    private final String host;
    private final int port;

    private final Path entryScriptPathAbs;
    private final Path scriptsRootAbs;
    private final String entryLogical;     // stable include key (relative to scriptsRoot)
    private final String appScriptText;    // events.es source

    // Server session routing (new protocol)
    private String sessionId;
    private String sessionKey;

    // Legacy client-side patch chain (older protocol)
    private ArrayNode lastPatch;           // chain patch returned from protocol
    private String lastFingerprint;
    private long cursor;

    // Optional "force full sync next request" (older protocol)
    private String nextStateJson;
    private ArrayNode nextPatchOverride;

    // -----------------------------
    // Deterministic local state tracking
    // -----------------------------
    private final Map<String, Object> trackedStateRaw = new LinkedHashMap<String, Object>();
    private String trackedFingerprint;
    private boolean verifyFingerprints = true;

    // Follow thread
    private Thread followThread;
    private final AtomicBoolean followRunning = new AtomicBoolean(false);

    private ElaraRpcRepl(String host, int port, Path entryScriptPath, Path scriptsRoot) throws IOException {
        this.host = host;
        this.port = port;

        this.entryScriptPathAbs = entryScriptPath.toAbsolutePath().normalize();
        this.scriptsRootAbs = scriptsRoot.toAbsolutePath().normalize();

        this.entryLogical = this.scriptsRootAbs.relativize(this.entryScriptPathAbs)
                .toString().replace('\\', '/');

        this.appScriptText = readUtf8File(this.entryScriptPathAbs);

        this.sessionId = null;
        this.sessionKey = null;

        this.lastPatch = null;
        this.lastFingerprint = null;
        this.cursor = 0;

        this.trackedFingerprint = StateFingerprint.fingerprintRawState(trackedStateRaw);
    }

    // -----------------------------
    // Main
    // -----------------------------
    public static void main(String[] args) throws Exception {
        Map<String, String> flags = parseArgs(args);

        String host = flags.containsKey("host") ? flags.get("host") : "127.0.0.1";
        int port = Integer.parseInt(flags.containsKey("port") ? flags.get("port") : "7777");

        String script = flags.get("script");
        if (script == null) {
            System.err.println("Missing required --script=/path/to/events.es");
            System.exit(2);
            return;
        }

        Path entry = Paths.get(script);
        Path scriptsRoot = flags.containsKey("scriptsRoot")
                ? Paths.get(flags.get("scriptsRoot"))
                : entry.toAbsolutePath().normalize().getParent();

        if (scriptsRoot == null) scriptsRoot = Paths.get(".").toAbsolutePath().normalize();

        ElaraRpcRepl repl = new ElaraRpcRepl(host, port, entry, scriptsRoot);

        System.out.println("Elara RPC REPL");
        System.out.println("  host=" + host + " port=" + port);
        System.out.println("  script=" + repl.entryScriptPathAbs);
        System.out.println("  scriptsRoot=" + repl.scriptsRootAbs);
        System.out.println("Type 'help' for commands.");
        repl.loop();
    }

    // -----------------------------
    // REPL Loop
    // -----------------------------
    private void loop() throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        while (true) {
            System.out.print("> ");
            String line = br.readLine();
            if (line == null) break; // EOF
            line = line.trim();
            if (line.isEmpty()) continue;

            List<String> toks = shellSplit(line);
            if (toks.isEmpty()) continue;

            String cmd = toks.get(0).toLowerCase(Locale.ROOT);

            try {
                if ("help".equals(cmd)) {
                    printHelp();

                } else if ("quit".equals(cmd) || "exit".equals(cmd)) {
                    stopFollow();
                    return;

                } else if ("ready".equals(cmd)) {
                    Long ts = null;
                    if (toks.size() >= 2) ts = Long.parseLong(toks.get(1));
                    JsonNode resp = doReady(ts);
                    System.out.println(pretty(resp));

                } else if ("send".equals(cmd)) {
                    if (toks.size() < 3) {
                        System.out.println("Usage: send <type> <target> [value.json]");
                        continue;
                    }
                    String type = toks.get(1);
                    String target = toks.get(2);
                    Object valueObj = null;

                    if (toks.size() >= 4) {
                        Path p = Paths.get(toks.get(3));
                        JsonNode v = om.readTree(readUtf8File(p));
                        valueObj = om.convertValue(v, Object.class);
                    }

                    JsonNode resp = doDispatch(type, target, valueObj);
                    System.out.println(pretty(resp));

                } else if ("poll".equals(cmd)) {
                    JsonNode resp = doPollOnce();
                    System.out.println(pretty(resp));

                } else if ("follow".equals(cmd)) {
                    int ms = 200;
                    if (toks.size() >= 2) ms = Integer.parseInt(toks.get(1));
                    startFollow(ms);
                    System.out.println("following events every " + ms + "ms (use 'nofollow' to stop)");

                } else if ("nofollow".equals(cmd)) {
                    stopFollow();
                    System.out.println("follow stopped");

                } else if ("show".equals(cmd)) {
                    System.out.println("sessionId:   " + (sessionId == null ? "(none)" : sessionId));
                    System.out.println("sessionKey:  " + (sessionKey == null ? "(none)" : "(set)"));
                    System.out.println("fingerprint: " + (lastFingerprint == null ? "(none)" : lastFingerprint));
                    System.out.println("lastPatch:   " + (lastPatch == null ? "(none)" : ("entries=" + lastPatch.size())));
                    System.out.println("cursor:      " + cursor);
                    System.out.println("nextStateJson: " + (nextStateJson == null ? "(none)" : ("len=" + nextStateJson.length())));
                    System.out.println("nextPatchOverride: " + (nextPatchOverride == null ? "(none)" : ("entries=" + nextPatchOverride.size())));
                    System.out.println("trackedFingerprint: " + (trackedFingerprint == null ? "(none)" : trackedFingerprint));
                    System.out.println("trackedStateKeys:   " + trackedStateRaw.size());
                    System.out.println("verifyFingerprints: " + verifyFingerprints);
                    
                    for (Entry<String, Object> e : trackedStateRaw.entrySet()) {
                    	System.out.println(e.getKey() + ": "+ e.getValue());
                    }

                } else if ("dumpstate".equals(cmd)) {
                    System.out.println(pretty(om.valueToTree(trackedStateRaw)));

                } else if ("verify".equals(cmd)) {
                    if (toks.size() < 2) {
                        System.out.println("Usage: verify on|off");
                        continue;
                    }
                    verifyFingerprints = "on".equalsIgnoreCase(toks.get(1))
                            || "true".equalsIgnoreCase(toks.get(1))
                            || "1".equals(toks.get(1));
                    System.out.println("verifyFingerprints=" + verifyFingerprints);

                } else if ("clearstate".equals(cmd)) {
                    trackedStateRaw.clear();
                    trackedFingerprint = StateFingerprint.fingerprintRawState(trackedStateRaw);
                    System.out.println("tracked state cleared");
                
                } else if ("state".equals(cmd)) {
                    if (toks.size() < 2) {
                        System.out.println("Usage: state load <file.json> | state clear");
                        continue;
                    }
                    String sub = toks.get(1).toLowerCase(Locale.ROOT);
                    if ("load".equals(sub)) {
                        if (toks.size() < 3) {
                            System.out.println("Usage: state load <file.json>");
                            continue;
                        }
                        Path p = Paths.get(toks.get(2));
                        nextStateJson = readUtf8File(p);
                        System.out.println("Loaded stateJson for next dispatch (full sync)." );
                    } else if ("clear".equals(sub)) {
                        nextStateJson = null;
                        System.out.println("Cleared pending stateJson full sync.");
                    } else {
                        System.out.println("Usage: state load <file.json> | state clear");
                    }

                } else if ("patch".equals(cmd)) {
                    if (toks.size() < 2) {
                        System.out.println("Usage: patch <patch.json>");
                        continue;
                    }
                    Path p = Paths.get(toks.get(1));
                    JsonNode n = om.readTree(readUtf8File(p));
                    if (!n.isArray()) {
                        System.out.println("patch.json must be a JSON array of [key,value] entries");
                        continue;
                    }
                    nextPatchOverride = (ArrayNode) n;
                    System.out.println("Loaded patch override for next dispatch.");

                } else if ("clearpatch".equals(cmd)) {
                    nextPatchOverride = null;
                    System.out.println("Cleared pending patch override.");

                } else if ("reset".equals(cmd)) {
                    sessionId = null;
                    sessionKey = null;
                    lastPatch = null;
                    lastFingerprint = null;
                    cursor = 0;
                    nextStateJson = null;
                    nextPatchOverride = null;

                    trackedStateRaw.clear();
                    trackedFingerprint = StateFingerprint.fingerprintRawState(trackedStateRaw);

                    System.out.println("Client session reset (does not affect server)." );

                } else {
                    System.out.println("Unknown command: " + cmd + " (type 'help')");
                }

            } catch (Throwable t) {
                System.out.println("ERROR: " + (t.getMessage() == null ? t.toString() : t.getMessage()));
            }
        }
    }

    private void printHelp() {
        String nl = "\n";
        System.out.println(
                "Commands:" + nl +
                "  ready [tsMillis]" + nl +
                "      Send system/ready with deterministic preloaded scripts payload." + nl +
                "      Stores server sessionId/sessionKey and resets client patch chain + cursor." + nl +
                nl +
                "  send <type> <target> [value.json]" + nl +
                "      Send an event. value.json is optional (defaults to null)." + nl +
                "      Applies returned patch to tracked state and verifies fingerprint." + nl +
                nl +
                "  poll" + nl +
                "      Poll server events once (uses current cursor)." + nl +
                nl +
                "  follow [ms] / nofollow" + nl +
                "      Start/stop polling events." + nl +
                nl +
                "  show" + nl +
                "      Show client session state + tracked fingerprint." + nl +
                nl +
                "  dumpstate" + nl +
                "      Pretty-print client tracked state." + nl +
                "  verify on|off" + nl +
                "      Enable/disable fingerprint verification." + nl +
                "  clearstate" + nl +
                "      Clear tracked state and recompute fingerprint." + nl +
                nl +
                "  state load <state.json> | state clear" + nl +
                "      Force full sync on NEXT dispatch (legacy servers only)." + nl +
                nl +
                "  patch <patch.json> | clearpatch" + nl +
                "      Patch override for NEXT dispatch (legacy servers only)." + nl +
                nl +
                "  reset" + nl +
                "      Reset client session tracking." + nl +
                nl +
                "  quit / exit"
        );
    }

    // -----------------------------
    // High-level operations
    // -----------------------------

    private JsonNode doReady(Long tsMillis) throws Exception {
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
        	executeCommandsFromResult(result, "event_system_ready");
        }

        return resp;
    }

    private JsonNode doDispatch(String type, String target, Object valueObj) throws Exception {
        if (sessionId == null || sessionKey == null) {
            System.out.println("WARN: no sessionId/sessionKey set. Run 'ready' first.");
        }

        ObjectNode args = om.createObjectNode();
        args.put("appScript", appScriptText);

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

            if (patchToSend != null) {
                args.set("patch", patchToSend);
            }
        }

        ObjectNode ev = om.createObjectNode();
        ev.put("type", type);
        ev.put("target", target);
        ev.set("value", valueObj == null ? NullNode.instance : om.valueToTree(valueObj));

        if (sessionId != null) ev.put("sessionId", sessionId);
        if (sessionKey != null) ev.put("sessionKey", sessionKey);

        args.set("event", ev);

        JsonNode resp = rpcCall("dispatchEvent", args);

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
        	executeCommandsFromResult(result, "event_"+type+"_"+target);
        }

        return resp;
    }

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
            if (serverFp.equals(trackedFingerprint)) {
                System.out.println("[TRACK] OK  fingerprint=" + trackedFingerprint + " keys=" + trackedStateRaw.size());
            } else {
                System.out.println("[TRACK] MISMATCH!");
                System.out.println("  server=" + serverFp);
                System.out.println("  local =" + trackedFingerprint);
                List<String> ks = new ArrayList<String>(trackedStateRaw.keySet());
                Collections.sort(ks);
                System.out.println("  localKeysSorted=" + ks);
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
            System.out.println("Server session established: sessionId=" + sessionId + " sessionKey=(set)");
        } else if (sessionId != null) {
            System.out.println("Server sessionId returned without sessionKey: sessionId=" + sessionId);
        }
    }
    
    /**
     * Execute protocol "commands" returned by the engine.
     * Supports chained dispatch: ["dispatch", type, target, value]
     */
    private void executeCommandsFromResult(JsonNode result, String origin) throws Exception {
        if (result == null || !result.isObject()) return;

        JsonNode cmds = result.get("commands");
        if (cmds == null || !cmds.isArray()) return;

        final int max = 64; // hard cap to avoid runaway loops
        int count = 0;

        for (JsonNode cmd : cmds) {
            if (++count > max) {
                System.out.println("[CMD] abort: too many commands (" + max + ")");
                break;
            }
            if (cmd == null || !cmd.isArray() || cmd.size() == 0) continue;

            String op = cmd.get(0).asText("");

            if ("dispatch".equals(op)) {
                String type = cmd.size() > 1 ? cmd.get(1).asText("") : "";
                String target = cmd.size() > 2 ? cmd.get(2).asText("") : "";

                Object valueObj = null;
                if (cmd.size() > 3 && cmd.get(3) != null && !cmd.get(3).isNull()) {
                    valueObj = om.convertValue(cmd.get(3), Object.class);
                }

                System.out.println("[CMD] dispatch " + type + " " + target + " (origin=" + origin + ")");
                JsonNode resp = doDispatch(type, target, valueObj);

                // recurse if that dispatch produced more commands
                JsonNode ok = resp.get("ok");
                if (ok != null && ok.isBoolean() && ok.booleanValue()) {
                    executeCommandsFromResult(resp.get("result"), "dispatch:" + type + "/" + target);
                }
                continue;
            }

            if ("log".equals(op)) {
                String msg = cmd.size() > 1 ? cmd.get(1).asText("") : "";
                System.out.println("[CMD] log: " + msg);
                continue;
            }

            System.out.println("[CMD] unknown: " + cmd.toString());
        }
    }

    private JsonNode doPollOnce() throws Exception {
        ObjectNode args = om.createObjectNode();
        args.put("cursor", cursor);

        JsonNode resp = rpcCall("pollEvents", args);

        JsonNode ok = resp.get("ok");
        if (ok != null && ok.isBoolean() && ok.booleanValue()) {
            JsonNode result = resp.get("result");
            if (result != null && result.isObject()) {
                cursor = result.path("cursor").asLong(cursor);
            }
        }
        return resp;
    }

    // -----------------------------
    // Follow thread
    // -----------------------------
    private void startFollow(final int intervalMs) {
        stopFollow();
        followRunning.set(true);

        followThread = new Thread(new Runnable() {
            @Override public void run() {
                while (followRunning.get()) {
                    try {
                        JsonNode resp = doPollOnce();
                        JsonNode result = resp.path("result");
                        JsonNode events = result.path("events");
                        if (events.isArray() && events.size() > 0) {
                            for (JsonNode ev : events) {
                                try {
                                    System.out.println(pretty(ev));
                                } catch (Exception ignored) {
                                    System.out.println(ev.toString());
                                }
                            }
                        }
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException ie) {
                        return;
                    } catch (Throwable t) {
                        System.out.println("FOLLOW_ERROR: " + (t.getMessage() == null ? t.toString() : t.getMessage()));
                        try { Thread.sleep(Math.max(250, intervalMs)); } catch (InterruptedException ignored) { return; }
                    }
                }
            }
        }, "elara-follow");

        followThread.setDaemon(true);
        followThread.start();
    }

    private void stopFollow() {
        followRunning.set(false);
        if (followThread != null) {
            followThread.interrupt();
            followThread = null;
        }
    }

    // -----------------------------
    // RPC (reconnect-per-request)
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

    // -------------------------
    // Framing (Java 8 safe)
    // -------------------------
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

    // -------------------------
    // Helpers
    // -------------------------
    private static String pretty(JsonNode n) throws Exception {
        return om.writerWithDefaultPrettyPrinter().writeValueAsString(n);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new HashMap<String, String>();
        for (String a : args) {
            if (a.startsWith("--") && a.indexOf('=') >= 0) {
                int i = a.indexOf('=');
                out.put(a.substring(2, i), a.substring(i + 1));
            } else if (a.startsWith("--")) {
                out.put(a.substring(2), "true");
            }
        }
        return out;
    }

    private static List<String> shellSplit(String line) {
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean escape = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (escape) {
                cur.append(c);
                escape = false;
                continue;
            }

            if (inQuotes && c == '\\') {
                escape = true;
                continue;
            }

            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }

            if (!inQuotes && Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }

            cur.append(c);
        }

        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String readUtf8File(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
