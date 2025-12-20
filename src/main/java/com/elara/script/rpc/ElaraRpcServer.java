package com.elara.script.rpc;

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

    // -------------------------------
    // Event queue (cursor-based)
    // -------------------------------
    private static final class EventEntry {
        final long seq;
        final ObjectNode event; // {"seq":..,"type":..,"payload":..} or your preferred shape
        EventEntry(long seq, ObjectNode event) { this.seq = seq; this.event = event; }
    }

    private final AtomicLong nextSeq = new AtomicLong(1);
    private final ReentrantLock eventsLock = new ReentrantLock();
    private final ArrayList<EventEntry> events = new ArrayList<>();
    private final int maxEventsKept = 10_000; // dev cap; tune as desired

    public ElaraRpcServer(int port, int threads) {
        this.port = port;
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads));
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

            // Support both "args" (what Linux bridge sends) and "params" (your earlier draft)
            JsonNode args = req.has("args") ? req.get("args") : req.get("params");

            switch (method) {
                case "dispatchEvent" -> {
                    // TODO: wire to your real ElaraScript engine call here
                    // e.g. Result r = engine.dispatchEvent(args...);

                    // DEV: emit an event so you can test polling end-to-end
                    // You can replace this with real engine events later.
                    ObjectNode payload = om.createObjectNode();
                    payload.set("args", args == null ? om.nullNode() : args);
                    emitEvent("dispatchEvent_called", payload);

                    ObjectNode result = om.createObjectNode();
                    result.putArray("patch");       // stub
                    result.putArray("commands");    // stub
                    result.put("fingerprint", "stub-fp");

                    resp.put("ok", true);
                    resp.set("result", result);
                }

                case "pollEvents" -> {
                    long cursor = 0;
                    if (args != null && args.has("cursor")) {
                        cursor = args.path("cursor").asLong(0);
                    }

                    ObjectNode result = pollEvents(cursor);
                    resp.put("ok", true);
                    resp.set("result", result);
                }

                case "ping" -> {
                    resp.put("ok", true);
                    resp.put("result", "pong");
                }

                default -> {
                    resp.put("ok", false);
                    resp.put("error", "Unknown method: " + method);
                }
            }

        } catch (Exception e) {
            resp.put("ok", false);
            resp.put("error", e.toString());
        }

        return resp;
    }

    // -------------------------------
    // Event API
    // -------------------------------

    /**
     * Enqueue a new event. Shape is yours to define, but keep it consistent across Android/Linux.
     * I recommend: {"seq":N, "type":"...", "payload":<json>}
     */
    public long emitEvent(String type, JsonNode payload) {
        long seq = nextSeq.getAndIncrement();

        ObjectNode ev = om.createObjectNode();
        ev.put("seq", seq);
        ev.put("type", type);
        ev.set("payload", payload == null ? om.nullNode() : payload);

        eventsLock.lock();
        try {
            events.add(new EventEntry(seq, ev));

            // Cap history to avoid unbounded memory during dev
            int overflow = events.size() - maxEventsKept;
            if (overflow > 0) {
                events.subList(0, overflow).clear();
            }
        } finally {
            eventsLock.unlock();
        }

        return seq;
    }

    /**
     * Return all events with seq > cursor.
     * Response: {"cursor":<latestSeqSeenOrCursor>, "events":[...]}
     */
    private ObjectNode pollEvents(long cursor) {
        ObjectNode result = om.createObjectNode();
        ArrayNode outEvents = om.createArrayNode();

        long latest = cursor;

        eventsLock.lock();
        try {
            // If we trimmed history and cursor is too old, you may want to signal a resync.
            // For dev, we'll just return from the earliest available.
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
        if (lenBuf.length == 0) return null; // clean EOF
        if (lenBuf.length < 4) throw new EOFException("partial length header");

        int len = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).getInt();
        if (len < 0 || len > (32 * 1024 * 1024)) { // 32MB sanity cap
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

        // Optional: produce a heartbeat event every 2s so you can see polling works immediately
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
