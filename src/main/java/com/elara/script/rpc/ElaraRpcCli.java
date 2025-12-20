package com.elara.script.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class ElaraRpcCli {

    private static final ObjectMapper om = new ObjectMapper();
    private static final int MAX_FRAME = 32 * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        Map<String, String> flags = parseArgs(args);

        String host = flags.getOrDefault("host", "127.0.0.1");
        int port = Integer.parseInt(flags.getOrDefault("port", "7777"));
        String method = flags.getOrDefault("method", "dispatchEvent");

        String scriptPath = flags.get("script");   // optional
        String argsJsonPath = flags.get("args");   // optional (raw JSON for args)
        String eventType = flags.getOrDefault("eventType", "system_ready"); // your own convention
        boolean follow = flags.containsKey("follow"); // poll events after call
        int followMs = Integer.parseInt(flags.getOrDefault("followMs", "200"));

        // Build RPC request
        ObjectNode req = om.createObjectNode();
        req.put("id", new Random().nextInt(Integer.MAX_VALUE));
        req.put("method", method);

        // args: either explicit JSON from file, OR build minimal dispatchEvent args from script
        JsonNode argsNode;
        if (argsJsonPath != null) {
            argsNode = om.readTree(Files.readString(Path.of(argsJsonPath), StandardCharsets.UTF_8));
        } else {
            ObjectNode a = om.createObjectNode();
            a.put("type", eventType);

            // Load script from disk if provided
            if (scriptPath != null) {
                String scriptText = Files.readString(Path.of(scriptPath), StandardCharsets.UTF_8);
                a.put("appScript", scriptText);
            }

            // You can extend this with target/payload exactly like your Dart call expects
            // e.g. a.put("target", "main"); a.set("payload", om.createObjectNode());

            argsNode = a;
        }

        req.set("args", argsNode);

        // Connect and send one request
        try (Socket socket = new Socket(host, port)) {
            socket.setTcpNoDelay(true);

            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            writeFrame(out, om.writeValueAsBytes(req));
            out.flush();

            JsonNode resp = om.readTree(readFrame(in));
            System.out.println(pretty(resp));

            if (follow) {
                followEvents(host, port, followMs);
            }
        }
    }

    // -------------------------
    // Follow/poll events
    // -------------------------
    private static void followEvents(String host, int port, int intervalMs) throws Exception {
        long cursor = 0;
        System.out.println("---- following events (Ctrl+C to stop) ----");

        while (true) {
            ObjectNode pollReq = om.createObjectNode();
            pollReq.put("id", new Random().nextInt(Integer.MAX_VALUE));
            pollReq.put("method", "pollEvents");

            ObjectNode pollArgs = om.createObjectNode();
            pollArgs.put("cursor", cursor);
            pollReq.set("args", pollArgs);

            try (Socket socket = new Socket(host, port)) {
                socket.setTcpNoDelay(true);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());

                writeFrame(out, om.writeValueAsBytes(pollReq));
                out.flush();

                JsonNode resp = om.readTree(readFrame(in));
                JsonNode result = resp.path("result");
                cursor = result.path("cursor").asLong(cursor);

                JsonNode events = result.path("events");
                if (events.isArray() && events.size() > 0) {
                    for (JsonNode ev : events) {
                        System.out.println(pretty(ev));
                    }
                }
            }

            Thread.sleep(intervalMs);
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
     * Minimal arg parser:
     *   --host=127.0.0.1 --port=7777 --method=dispatchEvent
     *   --script=/path/app.es --eventType=system_ready
     *   --args=/path/args.json
     *   --follow --followMs=200
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
}
