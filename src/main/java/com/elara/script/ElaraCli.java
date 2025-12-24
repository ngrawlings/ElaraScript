package com.elara.script;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.elara.script.parser.Value;

public final class ElaraCli {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: ElaraCli <script-file>");
            System.exit(2);
        }

        final Path scriptPath = Path.of(args[0]);
        final String script;
        try {
            script = Files.readString(scriptPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to read script file: " + scriptPath);
            e.printStackTrace(System.err);
            System.exit(3);
            return;
        }

        final ElaraScript engine = new ElaraScript();

        final BufferedReader stdin =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        // Register a blocking stdin line reader: readLine()
        // NOTE: adjust the method name below to whatever your BuiltinFunction interface requires
        // (e.g., call(...), invoke(...), apply(...)). See note after code.
        engine.registerFunction("readLine", new ElaraScript.BuiltinFunction() {
            @Override
            public Value call(List<Value> args) {
                if (args != null && !args.isEmpty()) {
                    throw new IllegalArgumentException("readLine() takes no arguments");
                }
                try {
                    String line = stdin.readLine(); // blocks
                    // If your Value class has no NULL constructor/factory, return "" on EOF:
                    if (line == null) line = "";
                    return Value.string(line);
                } catch (IOException ioe) {
                    throw new RuntimeException("readLine() failed: " + ioe.getMessage(), ioe);
                }
            }
        });

        try {
            // Your run(...) returns Map<String, Value>
            Map<String, Value> outputs = engine.run(script);

            // Print captured outputs (stable + minimal assumptions)
            if (outputs == null || outputs.isEmpty()) {
                System.out.println("(no outputs)");
            } else {
                for (Map.Entry<String, Value> e : outputs.entrySet()) {
                    System.out.println(e.getKey() + " = " + String.valueOf(e.getValue()));
                }
            }
        } catch (Exception e) {
            System.err.println("Script error:");
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private ElaraCli() {}
}
