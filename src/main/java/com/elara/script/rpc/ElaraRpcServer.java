package com.elara.script.rpc;

import com.elara.debug.Debug;
import com.elara.protocol.ElaraEngineProtocol;
import com.elara.script.ElaraScript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Minimal framed-JSON RPC server:
 * Frame = uint32_be length + UTF-8 JSON payload
 *
 * Supports:
 *  - MethodChannel forwarding: {"id":..,"method":"dispatchEvent","args":...}
 *  - Poll events: {"id":..,"method":"pollEvents","args":{"cursor":N}}
 *    Response: {"ok":true,"result":{"cursor":<latest>,"events":[...]}}
 */
public final class ElaraRpcServer implements Closeable {

    private final ObjectMapper om = new ObjectMapper();
    private final int port;
    private final ExecutorService pool;
    private volatile boolean running = true;
    private ServerSocket serverSocket;

    // ✅ Protocol (pure Java): same semantics as Android host
    private final ElaraEngineProtocol protocol;

    // -------------------------------
    // Event queue (cursor-based)
    // -------------------------------
    private static final class EventEntry {
        final long seq;
        final ObjectNode event;
        EventEntry(long seq, ObjectNode event) { this.seq = seq; this.event = event; }
    }

    private final AtomicLong nextSeq = new AtomicLong(1);
    private final ReentrantLock eventsLock = new ReentrantLock();
    private final ArrayList<EventEntry> events = new ArrayList<>();
    private final int maxEventsKept = 10_000;

    public ElaraRpcServer(int port, int threads) {
        this.port = port;
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads));
        
        Debug.useSysOut();

        // Wire protocol with non-Android builtins + console logger
        this.protocol = new ElaraEngineProtocol(
                ElaraRpcServer::registerRpcBuiltins,
                new ElaraEngineProtocol.Logger() {
                    @Override public void i(String tag, String msg) { log("[" + tag + "] " + msg); }
                    @Override public void w(String tag, String msg) { log("[" + tag + "][WARN] " + msg); }
                }
        );
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        log("RPC listening on 127.0.0.1:" + port);

        while (running) {
            Socket s = serverSocket.accept();
            s.setTcpNoDelay(true);
            pool.submit(() -> handleClient(s));
        }
    }

    private void handleClient(Socket s) {
        String peer = s.getRemoteSocketAddress().toString();
        log("client connected: " + peer);

        try (Socket socket = s;
             InputStream in = new BufferedInputStream(socket.getInputStream());
             OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {

            while (running) {
                byte[] payload = readFrame(in);
                if (payload == null) break; // EOF

                JsonNode req = om.readTree(payload);
                ObjectNode resp = process(req);

                byte[] respBytes = om.writeValueAsBytes(resp);
                writeFrame(out, respBytes);
                out.flush();
            }

        } catch (Exception e) {
            log("client error " + peer + " : " + e.getMessage());
        } finally {
            log("client disconnected: " + peer);
        }
    }

    private ObjectNode process(JsonNode req) {
        ObjectNode resp = om.createObjectNode();
        JsonNode id = req.get("id");
        if (id != null) resp.set("id", id);

        try {
            String method = req.path("method").asText("");

            // Support both "args" and "params"
            JsonNode args = req.has("args") ? req.get("args") : req.get("params");

            switch (method) {
                case "dispatchEvent": {
                    if (args == null || !args.isObject()) {
                        resp.put("ok", false);
                        resp.put("error", "dispatchEvent requires object args");
                        return resp;
                    }

                    // Extract arguments (match Flutter bridge shape)
                    String stateJson = args.hasNonNull("stateJson") ? args.get("stateJson").asText() : null;
                    String appScript = args.hasNonNull("appScript") ? args.get("appScript").asText() : null;

                    // event is required: Map<String,Object>
                    JsonNode eventNode = args.get("event");
                    if (eventNode == null || !eventNode.isObject()) {
                        resp.put("ok", false);
                        resp.put("error", "dispatchEvent requires args.event object");
                        return resp;
                    }

                    // patch is optional: List<[key,value]>
                    JsonNode patchNode = args.get("patch");

                    // Convert JSON -> Java types
                    @SuppressWarnings("unchecked")
                    Map<String, Object> event = om.convertValue(eventNode, Map.class);

                    Object patchObj = null;
                    if (patchNode != null && !patchNode.isNull()) {
                        // List of [key,value]
                        patchObj = om.convertValue(patchNode, Object.class);
                    }

                    // ✅ Call the protocol (not the engine directly)
                    Map<String, Object> resultMap = protocol.dispatchEvent(
                            appScript,
                            event
                    );

                    // DEV: emit an event so you can test polling end-to-end
                    ObjectNode payload = om.createObjectNode();
                    payload.set("args", args);
                    emitEvent("dispatchEvent_called", payload);

                    // Return protocol result
                    JsonNode resultNode = om.valueToTree(resultMap);

                    resp.put("ok", true);
                    resp.set("result", resultNode);
                    break;
                }

                case "pollEvents": {
                    long cursor = 0;
                    if (args != null && args.has("cursor")) {
                        cursor = args.path("cursor").asLong(0);
                    }

                    ObjectNode result = pollEvents(cursor);
                    resp.put("ok", true);
                    resp.set("result", result);
                    break;
                }

                case "ping": {
                    resp.put("ok", true);
                    resp.put("result", "pong");
                    break;
                }

                default: {
                    resp.put("ok", false);
                    resp.put("error", "Unknown method: " + method);
                    break;
                }
            }

        } catch (Exception e) {
            resp.put("ok", false);
            resp.put("error", e.toString());
        }

        return resp;
    }

    // -------------------------------
    // Builtins for RPC host (NO android.*)
    // -------------------------------

    private static void registerRpcBuiltins(ElaraScript engine) {
        // Minimal, portable builtins that won't touch hardware/UI.

        // native_log(tag, msg)
        engine.registerFunction("native_log", args -> {
            if (args.size() != 2) throw new RuntimeException("native_log expects 2 args");
            String tag = args.get(0).asString();
            String msg = args.get(1).asString();
            System.out.println("[" + tag + "] " + msg);
            return ElaraScript.Value.nil();
        });

        // nowMillis() -> number
        engine.registerFunction("nowMillis", args -> {
            if (args.size() != 0) throw new RuntimeException("nowMillis expects 0 args");
            return ElaraScript.Value.number((double) System.currentTimeMillis());
        });

        // uuid() -> string
        engine.registerFunction("uuid", args -> {
            if (args.size() != 0) throw new RuntimeException("uuid expects 0 args");
            return ElaraScript.Value.string(java.util.UUID.randomUUID().toString());
        });

        // randInt(minInclusive, maxInclusive) -> number
        engine.registerFunction("randInt", args -> {
            if (args.size() != 2) throw new RuntimeException("randInt expects 2 args");
            int a = (int) args.get(0).asNumber();
            int b = (int) args.get(1).asNumber();
            int lo = Math.min(a, b);
            int hi = Math.max(a, b);
            int r = java.util.concurrent.ThreadLocalRandom.current().nextInt(lo, hi + 1);
            return ElaraScript.Value.number((double) r);
        });

        // setview(parentId, viewName) -> string (placeholder, host-agnostic)
        engine.registerFunction("setview", args -> {
            if (args.size() != 2) throw new RuntimeException("setview expects 2 args");
            String view = args.get(1).asString();
            return ElaraScript.Value.string(view);
        });

        // toast(...) is Android-only; fail fast to keep scripts honest
        engine.registerFunction("toast", args -> {
            throw new RuntimeException("toast() is not supported in RPC host");
        });

        // readline(...) optional: either implement using RPC or fail fast
        engine.registerFunction("readline", args -> {
            throw new RuntimeException("readline() is not supported in RPC host");
        });
    }

    // -------------------------------
    // Event API
    // -------------------------------

    public long emitEvent(String type, JsonNode payload) {
        long seq = nextSeq.getAndIncrement();

        ObjectNode ev = om.createObjectNode();
        ev.put("seq", seq);
        ev.put("type", type);
        ev.set("payload", payload == null ? om.nullNode() : payload);

        eventsLock.lock();
        try {
            events.add(new EventEntry(seq, ev));
            int overflow = events.size() - maxEventsKept;
            if (overflow > 0) {
                events.subList(0, overflow).clear();
            }
        } finally {
            eventsLock.unlock();
        }

        return seq;
    }

    private ObjectNode pollEvents(long cursor) {
        ObjectNode result = om.createObjectNode();
        ArrayNode outEvents = om.createArrayNode();

        long latest = cursor;

        eventsLock.lock();
        try {
            for (EventEntry e : events) {
                if (e.seq > cursor) {
                    outEvents.add(e.event);
                    latest = Math.max(latest, e.seq);
                }
            }
        } finally {
            eventsLock.unlock();
        }

        result.put("cursor", latest);
        result.set("events", outEvents);
        return result;
    }

    // -------------------------------
    // Framing helpers
    // -------------------------------

    private static byte[] readFrame(InputStream in) throws IOException {
        byte[] lenBuf = in.readNBytes(4);
        if (lenBuf.length == 0) return null;
        if (lenBuf.length < 4) throw new EOFException("partial length header");

        int len = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).getInt();
        if (len < 0 || len > (32 * 1024 * 1024)) {
            throw new IOException("bad frame length: " + len);
        }
        byte[] payload = in.readNBytes(len);
        if (payload.length < len) throw new EOFException("partial frame payload");
        return payload;
    }

    private static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        byte[] lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.length).array();
        out.write(lenBuf);
        out.write(payload);
    }

    private static void log(String s) {
        System.out.println("[ElaraRpcServer] " + s);
    }

    @Override
    public void close() throws IOException {
        running = false;
        if (serverSocket != null) serverSocket.close();
        pool.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        int port = 7777;
        int threads = 4;
        ElaraRpcServer server = new ElaraRpcServer(port, threads);

        // Optional: heartbeat event every 2s
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
        ses.scheduleAtFixedRate(() -> {
            try {
                ObjectNode payload = server.om.createObjectNode();
                payload.put("msg", "heartbeat");
                payload.put("ts", System.currentTimeMillis());
                server.emitEvent("heartbeat", payload);
            } catch (Exception ignored) {}
        }, 0, 2, TimeUnit.SECONDS);

        server.start();
    }
}
