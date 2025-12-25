package com.elara.script;

import com.elara.script.parser.EntryRunResult;
import com.elara.script.parser.Environment;
import com.elara.script.parser.Interpreter;
import com.elara.script.parser.Lexer;
import com.elara.script.parser.Parser;
import com.elara.script.parser.Statement.Block;
import com.elara.script.parser.Statement.FunctionStmt;
import com.elara.script.parser.Statement.If;
import com.elara.script.parser.Statement.Stmt;
import com.elara.script.parser.Statement.While;
import com.elara.script.parser.Token;
import com.elara.script.parser.Value;
import com.elara.script.shaping.DataShapingRegistry;
import com.elara.script.shaping.ElaraDataShaper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core ElaraScript engine.
 *
 * - JavaScript-like syntax (let / if / else / while / for / && / || / == / != / + - * / %)
 * - Types: number (double), bool, string, bytes, array, matrix, null
 * - Sandboxed: only data processing
 * - Function calls:
 *     - Built-ins (registered via registerFunction)
 *     - User-defined functions (function name(a,b){ ...; return ...; })
 * - Control flow:
 *     - return, break
 * - Mode:
 *     - STRICT (default): only direct function calls
 *     - INFERENCE: allows dynamic dispatch via call("fnName", ...args)
 *                  and var-held function names: let f="add"; f(1,2)
 */
public class ElaraScript {

    /** Parser/execution mode. Default STRICT. */
    public enum Mode {
        STRICT,
        INFERENCE
    }

    /** Public value type used by host code. */


    /** Functional interface for built-in functions. */
    public interface BuiltinFunction {
        Value call(List<Value> args);
    }

    // ===================== INTERPRETER =====================

    /** Error reporter hook used by the interpreter to surface system errors to the host. */
    public interface SystemErrorReporter {
        void report(RuntimeException e, Interpreter interpreter, String kind, String name, Token token, String message);
    }

    // ===================== ENGINE PUBLIC API =====================

    private final Map<String, BuiltinFunction> functions = new HashMap<String, BuiltinFunction>();
    private int maxCallDepth = 64;
    private Mode mode = Mode.STRICT;

    /** Registry for DataShaper-based shaping/validation (replacement for legacy DataShape). */
    private final DataShapingRegistry dataShaping = new DataShapingRegistry();


    // ===================== SYSTEM ERROR CALLBACK (HOST-REGISTERED) =====================
    // The interpreter core must not hardcode any ES function names.
    // Native code registers the callback function name (e.g. "event_system_error") after initialization.
    private String errorCallbackFn = null;
    private boolean reportingSystemError = false;

    /** Register the ElaraScript function name that will receive system/interpreter errors. */
    /*
     * ERROR HANDLING CONTRACT (DO NOT BREAK):
     *
     * - If NO error callback is registered:
     *     -> runtime errors THROW to the host.
     *
     * - If an error callback IS registered:
     *     -> runtime errors are routed to the callback
     *     -> runtime errors are SUPPRESSED
     *     -> NO exceptions escape to the host
     *
     * This applies to:
     *   - run(...)
     *   - runWithEntryResult(...)
     *
     * Any change to this behavior MUST update unit tests.
     */
    public void setErrorCallback(String fnName) {
        this.errorCallbackFn = (fnName == null) ? null : fnName.trim();
        if (this.errorCallbackFn != null && this.errorCallbackFn.isEmpty()) this.errorCallbackFn = null;
    }

    public ElaraScript() {
        registerCoreBuiltins();
    }

    public void setMaxCallDepth(int depth) { this.maxCallDepth = depth; }

    public void setMode(Mode mode) { this.mode = (mode == null) ? Mode.STRICT : mode; }

    public Mode getMode() { return mode; }

    public void registerFunction(String name, BuiltinFunction fn) { functions.put(name, fn); }

    /** Access the named-shape registry used by the New DataShaper pipeline. */
    public DataShapingRegistry dataShaping() { return dataShaping; }

    /**
     * Run a shaped pipeline using a named shape registered in {@link #dataShaping()}.
     *
     * Pipeline:
     *  1) rawInputs -> initial env (coerce/validate)
     *  2) execute the script (globals + entry invocation)
     *  3) validate/extract declared outputs from the resulting env snapshot
     */
    public ElaraDataShaper.RunResult<Value> runShaped(
            String source,
            String shapeName,
            Map<String, Object> rawInputs,
            String entryFunctionName,
            List<Value> entryArgs,
            boolean includeDebugEnv
    ) {
        ElaraDataShaper.Shape<Value> shape = dataShaping.get(shapeName);
        ElaraDataShaper<Value> shaper = dataShaping.shaper();

        return shaper.run(
                shape,
                rawInputs,
                (initialEnv) -> {
                    EntryRunResult rr = runWithEntryResult(source, entryFunctionName, entryArgs, initialEnv);
                    return rr.env();
                },
                includeDebugEnv
        );
    }

    /**
     * Same as {@link #runShaped(String, String, Map, String, List, boolean)} but runs globals only (no entry call).
     * Useful when the script itself sets output variables at top-level.
     */
    public ElaraDataShaper.RunResult<Value> runShaped(
            String source,
            String shapeName,
            Map<String, Object> rawInputs,
            boolean includeDebugEnv
    ) {
        ElaraDataShaper.Shape<Value> shape = dataShaping.get(shapeName);
        ElaraDataShaper<Value> shaper = dataShaping.shaper();

        return shaper.run(
                shape,
                rawInputs,
                (initialEnv) -> run(source, initialEnv),
                includeDebugEnv
        );
    }

    public Map<String, Value> run(String source) {
        Environment env = new Environment();
        return runInternal(source, env);
    }

    public Value run(String source, String entryFunctionName, List<Value> entryArgs) {
        EntryRunResult rr = runWithEntryResult(
                source,
                entryFunctionName,
                entryArgs,
                Collections.emptyMap()
        );
        return rr.value();
    }

    /** Global-program mode: run script with an initial environment. Returns final env snapshot. */
    public Map<String, Value> run(String source, Map<String, Value> initialEnv) {
        Environment env = new Environment(initialEnv);
        return runInternal(source, env);
    }

    /** Global-program mode: run script with an initial environment (legacy alias if needed). */
    public Map<String, Value> run(String source, Map<String, Value> initialEnv, boolean ignored) {
        // Optional: keep if any older code calls a 3-arg version; otherwise delete.
        return run(source, initialEnv);
    }

    private Map<String, Value> runInternal(String source, Environment env) {
        Interpreter interpreter = null;
        try {
            Lexer lexer = new Lexer(source);
            List<Token> tokens = lexer.tokenize();
            Parser parser = new Parser(tokens);
            List<Stmt> program = parser.parse();

            interpreter = new Interpreter(env, functions, dataShaping, maxCallDepth, mode, this::onInterpreterError);
            interpreter.execute(program);

            return env.snapshot();
        } catch (RuntimeException e) {
            // Route everything (lex/parse/runtime) through the same error policy.
            onInterpreterError(
                    e,
                    interpreter, // may be null if lex/parse failed
                    "exception",
                    null,
                    null,
                    (e.getMessage() == null) ? e.toString() : e.getMessage()
            );

            // If callback is set, suppress, always.
            if (errorCallbackFn != null) {
                return env.snapshot();
            }

            // No callback: throw to host (JUnit)
            throw e;
        }
    }

    

    /**
     * Quick, side-effect-free check: does the script define a user function named `fnName`?
     * This parses the program and scans for `function <name>(...) { ... }` declarations.
     */
    public boolean hasUserFunction(String source, String fnName) {
        if (source == null) return false;
        if (fnName == null || fnName.trim().isEmpty()) return false;

        Set<String> names = extractUserFunctionNames(source);
        return names.contains(fnName);
    }

    /**
     * Runs the script normally (top-level executes, functions register, etc),
     * then invokes a specific user-defined entry function and returns:
     * - the full env snapshot after invocation
     * - the entry function return value
     *
     * This is used by the event system: choose entry function -> run -> invoke -> persist env.
     */
    public EntryRunResult runWithEntryResult(
            String source,
            String entryFunctionName,
            List<Value> entryArgs,
            Map<String, Value> initialEnv
    ) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (entryFunctionName == null || entryFunctionName.trim().isEmpty()) {
            throw new IllegalArgumentException("entryFunctionName must not be empty");
        }

        Environment env = new Environment(initialEnv);
        Interpreter interpreter = null;

        try {
            Lexer lexer = new Lexer(source);
            List<Token> tokens = lexer.tokenize();
            Parser parser = new Parser(tokens);
            List<Stmt> program = parser.parse();

            interpreter = new Interpreter(env, functions, dataShaping, maxCallDepth, mode, this::onInterpreterError);

            // 1) globals
            interpreter.execute(program);

            // 2) entry call
            List<Value> args = (entryArgs == null) ? Collections.emptyList() : entryArgs;
            Value out = interpreter.invokeForHost(entryFunctionName, args);

            return new EntryRunResult(env.snapshot(), out);
        } catch (RuntimeException e) {
            onInterpreterError(
                    e,
                    interpreter,
                    "exception",
                    entryFunctionName,
                    null,
                    (e.getMessage() == null) ? e.toString() : e.getMessage()
            );

            // callback set => suppress (always)
            if (errorCallbackFn != null) {
                return new EntryRunResult(env.snapshot(), Value.nil());
            }

            throw e;
        }
    }


    /**
     * Helper: parse source and return all declared user function names.
     * (No execution; safe for routing decisions.)
     */
    private static Set<String> extractUserFunctionNames(String source) {
        Lexer lexer = new Lexer(source);
        List<Token> tokens = lexer.tokenize();
        Parser parser = new Parser(tokens);
        List<Stmt> program = parser.parse();

        Set<String> out = new HashSet<>();
        collectFunctionNames(program, out);
        return out;
    }

    private static void collectFunctionNames(List<Stmt> program, Set<String> out) {
        for (Stmt s : program) {
            if (s instanceof FunctionStmt) {
                out.add(((FunctionStmt) s).name.lexeme);
            } else if (s instanceof Block) {
                collectFunctionNames(((Block) s).statements, out);
            } else if (s instanceof If) {
                If is = (If) s;
                collectFunctionNames(Collections.singletonList(is.thenBranch), out);
                if (is.elseBranch != null) collectFunctionNames(Collections.singletonList(is.elseBranch), out);
            } else if (s instanceof While) {
                While ws = (While) s;
                collectFunctionNames(Collections.singletonList(ws.body), out);
            }
            // ExprStmt / VarStmt / ReturnStmt / BreakStmt: nothing to collect
        }
    }

    /**
     * Called by the interpreter whenever it detects a system-level error (missing var/fn, etc).
     * Native code can register the callback via setErrorCallback("event_system_error").
     *
     * Callback signature (ElaraScript function):
     *   event_system_error(kind, nameOrNull, functionOrNull, lineOrNull, message)
     */
    private void onInterpreterError(RuntimeException orig_e, Interpreter interpreter, String kind, String name, Token token, String message) {
        if (reportingSystemError) {
            System.err.println("[ElaraScript] recursive error while reporting: " + kind + " " + name + " " + message);
            return;
        }

        if (errorCallbackFn == null) {
            if (orig_e != null) {
                throw orig_e;
            } else {
                System.err.println("[ElaraScript] (no error callback) " + kind + " " + name + " " + message);
                return;
            }
        }

        reportingSystemError = true;
        try {
            List<Value> args = new ArrayList<Value>(5);
            args.add(Value.string(kind == null ? "exception" : kind));
            args.add(name == null ? Value.nil() : Value.string(name));

            String fn = (interpreter == null) ? null : interpreter.currentFunctionName();
            args.add(fn == null ? Value.nil() : Value.string(fn));

            if (token == null) args.add(Value.nil());
            else args.add(Value.number((double) token.line));

            args.add(Value.string(message == null ? "" : message));

            try {
                interpreter.invokeForHost(errorCallbackFn, args);
            } catch (RuntimeException e) {
                System.err.println("[ElaraScript] error callback failed: " + e + " payload=" + message);
            }
        } finally {
            reportingSystemError = false;
        }
    }

    private void registerCoreBuiltins() {
        registerFunction("pow", args -> {
            requireArgCount("pow", args, 2);
            double a = args.get(0).asNumber();
            double b = args.get(1).asNumber();
            return Value.number(Math.pow(a, b));
        });

        registerFunction("sqrt", args -> {
            requireArgCount("sqrt", args, 1);
            return Value.number(Math.sqrt(args.get(0).asNumber()));
        });

        registerFunction("abs", args -> {
            requireArgCount("abs", args, 1);
            return Value.number(Math.abs(args.get(0).asNumber()));
        });

        registerFunction("min", args -> {
            if (args.isEmpty()) throw new RuntimeException("min() expects at least 1 argument");
            double m = args.get(0).asNumber();
            for (int i = 1; i < args.size(); i++) {
                double d = args.get(i).asNumber();
                if (d < m) m = d;
            }
            return Value.number(m);
        });

        registerFunction("max", args -> {
            if (args.isEmpty()) throw new RuntimeException("max() expects at least 1 argument");
            double m = args.get(0).asNumber();
            for (int i = 1; i < args.size(); i++) {
                double d = args.get(i).asNumber();
                if (d > m) m = d;
            }
            return Value.number(m);
        });

        registerFunction("len", args -> {
            requireArgCount("len", args, 1);
            Value v = args.get(0);
            switch (v.getType()) {
                case STRING: return Value.number(v.asString().length());
                case BYTES: {
                    byte[] bb = v.asBytes();
                    return Value.number(bb == null ? 0 : bb.length);
                }
                case ARRAY: return Value.number(v.asArray().size());
                case MATRIX: return Value.number(v.asMatrix().size());
                case MAP: {
                    Map<String, Value> m = v.asMap();
                    return Value.number(m == null ? 0 : m.size());
                }
                default: throw new RuntimeException("len() not supported for type: " + v.getType());
            }
        });
        
        registerFunction("typeof", (args) -> {
            if (args.size() != 1) {
                throw new RuntimeException("typeof(x) expects 1 argument, got " + args.size());
            }
            Value v = args.get(0);
            String typeName;
            switch (v.getType()) {
                case NUMBER: typeName = "number"; break;
                case BOOL:   typeName = "bool"; break;
                case STRING: typeName = "string"; break;
                case FUNC:   typeName = "function"; break;
                case BYTES:  typeName = "bytes"; break;
                case ARRAY:  typeName = "array"; break;
                case MATRIX: typeName = "matrix"; break;
                case MAP:    typeName = "map"; break;
                case NULL:   typeName = "null"; break;
                default:     typeName = "unknown"; break;
            }
            return Value.string(typeName);
        });
        
        registerFunction("keys", args -> {
            requireArgCount("keys", args, 1);
            Value v = args.get(0);
            if (v.getType() != Value.Type.MAP) throw new RuntimeException("keys() expects a map");

            Map<String, Value> m = v.asMap();
            List<Value> out = new ArrayList<>();
            if (m != null) {
                for (String k : m.keySet()) out.add(Value.string(k));
            }
            return Value.array(out);
        });
        
        registerFunction("map_new", args -> {
            requireArgCount("map_new", args, 0);
            return Value.map(new LinkedHashMap<>());
        });
        
        registerFunction("map_clone", args -> {
            requireArgCount("map_clone", args, 1);
            Value v = args.get(0);

            if (v.getType() != Value.Type.MAP) {
                throw new RuntimeException("map_clone() expects a map");
            }

            Map<String, Value> src = v.asMap();
            Map<String, Value> out = new LinkedHashMap<>();

            if (src != null) {
                out.putAll(src);
            }

            return Value.map(out);
        });
        
        registerFunction("map_remove_key", args -> {
            requireArgCount("map_remove_key", args, 2);

            Value mv = args.get(0);
            Value kv = args.get(1);

            if (mv.getType() != Value.Type.MAP) {
                throw new RuntimeException("map_remove_key() expects a map");
            }
            if (kv.getType() != Value.Type.STRING) {
                throw new RuntimeException("map_remove_key() expects a string key");
            }

            Map<String, Value> m = mv.asMap();
            if (m == null) return Value.bool(false);

            return Value.bool(m.remove(kv.asString()) != null);
        });

    }

    private static void requireArgCount(String name, List<Value> args, int expected) {
        if (args.size() != expected) {
            throw new RuntimeException(name + "() expects " + expected + " arguments, got " + args.size());
        }
    }
}