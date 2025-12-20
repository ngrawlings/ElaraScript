package com.elara.script.rpc;

import com.elara.protocol.ElaraScriptPreloader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent stdin REPL for Elara RPC.
 *
 * Transport is stateless (reconnect per request). "Session" is client-side:
 * - keeps lastPatch + fingerprint
 * - can optionally force a full stateJson for next call
 * - sends deterministic system.ready payload via ElaraScriptPreloader
 *
 * Default flow:
 *   > ready
 *   > send ui click ./value.json
 *   > show
 *   > follow 200
 *
 * Args:
 *   --host=127.0.0.1 --port=7777 --script=./scripts/events.es
 *   --scriptsRoot=./scripts        (optional; defaults to parent of --script)
 */
public final class ElaraRpcRepl {

    private static final ObjectMapper om = new ObjectMapper();
    private static final int MAX_FRAME = 32 * 1024 * 1024;

    // -----------------------------
    // Session state (client is master)
    // -----------------------------
    private final String host;
    private final int port;

    private final Path entryScriptPathAbs;
    private final Path scriptsRootAbs;
    private final String entryLogical;     // stable include key (relative to scriptsRoot)
    private final String appScriptText;    // events.es source

    private ArrayNode lastPatch;           // chain patch returned from protocol
    private String lastFingerprint;
    private long cursor;

    // Optional "force full sync next request"
    private String nextStateJson;
    private ArrayNode nextPatchOverride;

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

        this.appScriptText = Files.readString(this.entryScriptPathAbs, StandardCharsets.UTF_8);

        this.lastPatch = null;
        this.lastFingerprint = null;
        this.cursor = 0;
    }

    // -----------------------------
    // Main
    // -----------------------------
    public static void main(String[] args) throws Exception {
        Map<String, String> flags = parseArgs(args);

        String host = flags.getOrDefault("host", "127.0.0.1");
        int port = Integer.parseInt(flags.getOrDefault("port", "7777"));

        String script = flags.get("script");
        if (script == null) {
            System.err.println("Missing required --script=/path/to/events.es");
            System.exit(2);
            return;
        }

        Path entry = Path.of(script);
        Path scriptsRoot = flags.containsKey("scriptsRoot")
                ? Path.of(flags.get("scriptsRoot"))
                : entry.toAbsolutePath().normalize().getParent();

        if (scriptsRoot == null) scriptsRoot = Path.of(".").toAbsolutePath().normalize();

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
                switch (cmd) {
                    case "help" -> printHelp();
                    case "quit", "exit" -> {
                        stopFollow();
                        return;
                    }

                    case "ready" -> {
                        Long ts = null;
                        if (toks.size() >= 2) {
                            ts = Long.parseLong(toks.get(1));
                        }
                        JsonNode resp = doReady(ts);
                        System.out.println(pretty(resp));
                    }

                    case "send" -> {
                        if (toks.size() < 3) {
                            System.out.println("Usage: send <type> <target> [value.json]");
                            break;
                        }
                        String type = toks.get(1);
                        String target = toks.get(2);
                        Object valueObj = null;

                        if (toks.size() >= 4) {
                            Path p = Path.of(toks.get(3));
                            JsonNode v = om.readTree(Files.readString(p, StandardCharsets.UTF_8));
                            valueObj = om.convertValue(v, Object.class);
                        }

                        JsonNode resp = doDispatch(type, target, valueObj);
                        System.out.println(pretty(resp));
                    }

                    case "poll" -> {
                        JsonNode resp = doPollOnce();
                        System.out.println(pretty(resp));
                    }

                    case "follow" -> {
                        int ms = 200;
                        if (toks.size() >= 2) ms = Integer.parseInt(toks.get(1));
                        startFollow(ms);
                        System.out.println("following events every " + ms + "ms (use 'nofollow' to stop)");
                    }

                    case "nofollow" -> {
                        stopFollow();
                        System.out.println("follow stopped");
                    }

                    case "show" -> {
                        System.out.println("fingerprint: " + (lastFingerprint == null ? "(none)" : lastFingerprint));
                        System.out.println("lastPatch:   " + (lastPatch == null ? "(none)" : ("entries=" + lastPatch.size())));
                        System.out.println("cursor:      " + cursor);
                        System.out.println("nextStateJson: " + (nextStateJson == null ? "(none)" : ("len=" + nextStateJson.length())));
                        System.out.println("nextPatchOverride: " + (nextPatchOverride == null ? "(none)" : ("entries=" + nextPatchOverride.size())));
                    }

                    case "state" -> {
                        if (toks.size() < 2) {
                            System.out.println("Usage: state load <file.json> | state clear");
                            break;
                        }
                        String sub = toks.get(1).toLowerCase(Locale.ROOT);
                        if ("load".equals(sub)) {
                            if (toks.size() < 3) {
                                System.out.println("Usage: state load <file.json>");
                                break;
                            }
                            Path p = Path.of(toks.get(2));
                            nextStateJson = Files.readString(p, StandardCharsets.UTF_8);
                            System.out.println("Loaded stateJson for next dispatch (full sync).");
                        } else if ("clear".equals(sub)) {
                            nextStateJson = null;
                            System.out.println("Cleared pending stateJson full sync.");
                        } else {
                            System.out.println("Usage: state load <file.json> | state clear");
                        }
                    }

                    case "patch" -> {
                        if (toks.size() < 2) {
                            System.out.println("Usage: patch <patch.json>");
                            break;
                        }
                        Path p = Path.of(toks.get(1));
                        JsonNode n = om.readTree(Files.readString(p, StandardCharsets.UTF_8));
                        if (!n.isArray()) {
                            System.out.println("patch.json must be a JSON array of [key,value] entries");
                            break;
                        }
                        nextPatchOverride = (ArrayNode) n;
                        System.out.println("Loaded patch override for next dispatch.");
                    }

                    case "clearpatch" -> {
                        nextPatchOverride = null;
                        System.out.println("Cleared pending patch override.");
                    }

                    case "reset" -> {
                        // client-side reset
                        lastPatch = null;
                        lastFingerprint = null;
                        cursor = 0;
                        nextStateJson = null;
                        nextPatchOverride = null;
                        System.out.println("Client session reset (does not affect server).");
                    }

                    default -> System.out.println("Unknown command: " + cmd + " (type 'help')");
                }
            } catch (Throwable t) {
                System.out.println("ERROR: " + (t.getMessage() == null ? t.toString() : t.getMessage()));
            }
        }
    }

    private void printHelp() {
        System.out.println("""
Commands:
  ready [tsMillis]
      Send system/ready with deterministic preloaded scripts payload.
      Resets client patch chain + fingerprint + cursor.

  send <type> <target> [value.json]
      Send an event. value.json is optional (defaults to null).

  poll
      Poll server events once (uses current cursor).

  follow [ms]
      Start polling events repeatedly (default 200ms).
  nofollow
      Stop event following.

  show
      Show client session state (fingerprint, patch size, cursor, pending overrides).

  state load <state.json>
      Force a full sync on NEXT dispatch (stateJson wins on server).
  state clear
      Clear pending full sync.

  patch <patch.json>
      Force a patch override on NEXT dispatch (JSON array of [key,value]).
  clearpatch
      Clear pending patch override.

  reset
      Reset client session tracking (does not affect server).

  quit / exit
""");
    }

    // -----------------------------
    // High-level operations
    // -----------------------------

    private JsonNode doReady(Long tsMillis) throws Exception {
        // Ready implies new boot; clear chain.
        lastPatch = null;
        lastFingerprint = null;
        cursor = 0;
        nextStateJson = null;
        nextPatchOverride = null;

        // Deterministic preload closure
        ElaraScriptPreloader preloader = new ElaraScriptPreloader(
                normalized -> scriptsRootAbs.resolve(normalized)
        );

        Map<String, Object> readyPayload = preloader.buildReadyPayload(
                entryLogical,
                tsMillis,
                null
        );

        // dispatchEvent args
        ObjectNode args = om.createObjectNode();
        args.put("appScript", appScriptText);

        ObjectNode ev = om.createObjectNode();
        ev.put("type", "system");
        ev.put("target", "ready");
        ev.set("value", om.valueToTree(readyPayload));
        args.set("event", ev);

        return rpcCall("dispatchEvent", args);
    }

    private JsonNode doDispatch(String type, String target, Object valueObj) throws Exception {
        ObjectNode args = om.createObjectNode();
        args.put("appScript", appScriptText);

        // Full sync wins if provided
        if (nextStateJson != null && !nextStateJson.trim().isEmpty()) {
            args.put("stateJson", nextStateJson);
            // consume it after use
            nextStateJson = null;
        } else {
            // Otherwise patch-chaining
            ArrayNode patchToSend = null;

            if (nextPatchOverride != null) {
                patchToSend = nextPatchOverride;
                nextPatchOverride = null; // consume after use
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
        args.set("event", ev);

        JsonNode resp = rpcCall("dispatchEvent", args);

        // Update chain from response (if ok)
        JsonNode ok = resp.get("ok");
        if (ok != null && ok.isBoolean() && ok.booleanValue()) {
            JsonNode result = resp.get("result");
            if (result != null && result.isObject()) {
                JsonNode p = result.get("patch");
                if (p != null && p.isArray()) lastPatch = (ArrayNode) p;

                JsonNode fp = result.get("fingerprint");
                if (fp != null && fp.isTextual()) lastFingerprint = fp.asText();
            }
        }

        return resp;
    }

    private JsonNode doPollOnce() throws Exception {
        ObjectNode args = om.createObjectNode();
        args.put("cursor", cursor);

        JsonNode resp = rpcCall("pollEvents", args);

        // Update cursor
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
    private void startFollow(int intervalMs) {
        stopFollow();
        followRunning.set(true);

        followThread = new Thread(() -> {
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

        try (Socket socket = new Socket(host, port)) {
            socket.setTcpNoDelay(true);

            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            writeFrame(out, om.writeValueAsBytes(req));
            out.flush();

            byte[] respBytes = readFrame(in);
            return om.readTree(respBytes);
        }
    }

    // -------------------------
    // Framing
    // -------------------------
    private static byte[] readFrame(InputStream in) throws IOException {
        byte[] lenBuf = in.readNBytes(4);
        if (lenBuf.length < 4) throw new EOFException("partial length header");
        int len = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).getInt();
        if (len < 0 || len > MAX_FRAME) throw new IOException("bad frame length: " + len);
        byte[] payload = in.readNBytes(len);
        if (payload.length < len) throw new EOFException("partial frame payload");
        return payload;
    }

    private static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        byte[] lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.length).array();
        out.write(lenBuf);
        out.write(payload);
    }

    // -------------------------
    // Helpers
    // -------------------------
    private static String pretty(JsonNode n) throws Exception {
        return om.writerWithDefaultPrettyPrinter().writeValueAsString(n);
    }

    /**
     * Args:
     *   --host=127.0.0.1 --port=7777 --script=./scripts/events.es --scriptsRoot=./scripts
     */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (String a : args) {
            if (a.startsWith("--") && a.contains("=")) {
                int i = a.indexOf('=');
                out.put(a.substring(2, i), a.substring(i + 1));
            } else if (a.startsWith("--")) {
                out.put(a.substring(2), "true");
            }
        }
        return out;
    }

    /**
     * Minimal shell-like tokenizer:
     * - splits on whitespace
     * - supports double quotes "..."
     * - supports backslash escapes inside quotes
     */
    private static List<String> shellSplit(String line) {
        ArrayList<String> out = new ArrayList<>();
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
}
