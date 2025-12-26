package com.elara.script.parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.elara.script.ElaraScript.BuiltinFunction;
import com.elara.script.ElaraScript.Mode;
import com.elara.script.ElaraScript.SystemErrorReporter;
import com.elara.script.parser.Expr.Assign;
import com.elara.script.parser.Expr.Binary;
import com.elara.script.parser.Expr.Call;
import com.elara.script.parser.Expr.CallArg;
import com.elara.script.parser.Expr.ExprVisitor;
import com.elara.script.parser.Expr.Index;
import com.elara.script.parser.Expr.IndexExpr;
import com.elara.script.parser.Expr.Literal;
import com.elara.script.parser.Expr.Logical;
import com.elara.script.parser.Expr.MapLiteral;
import com.elara.script.parser.Expr.MethodCallExpr;
import com.elara.script.parser.Expr.NewExpr;
import com.elara.script.parser.Expr.SetIndex;
import com.elara.script.parser.Expr.SetIndexExpr;
import com.elara.script.parser.Expr.Unary;
import com.elara.script.parser.Expr.Variable;
import com.elara.script.parser.Statement.Block;
import com.elara.script.parser.Statement.BreakStmt;
import com.elara.script.parser.Statement.ClassStmt;
import com.elara.script.parser.Statement.ExprStmt;
import com.elara.script.parser.Statement.FreeStmt;
import com.elara.script.parser.Statement.FunctionStmt;
import com.elara.script.parser.Statement.If;
import com.elara.script.parser.Statement.ReturnStmt;
import com.elara.script.parser.Statement.Stmt;
import com.elara.script.parser.Statement.StmtVisitor;
import com.elara.script.parser.Statement.VarStmt;
import com.elara.script.parser.Statement.While;
import com.elara.script.parser.Value.ClassInstance;
import com.elara.script.shaping.DataShapingRegistry;
import com.elara.script.shaping.ElaraDataShaper;


public class Interpreter implements ExprVisitor<Value>, StmtVisitor {
    Environment env;
    private final Map<String, BuiltinFunction> functions;
    private final DataShapingRegistry shapingRegistry;
    private final Map<String, UserFunction> userFunctions = new LinkedHashMap<>();
    private final Map<String, Value.ClassDescriptor> classes = new HashMap<>();
    private final LinkedHashMap<String, Value.ClassInstance> liveInstances = new LinkedHashMap<>();
    private final Deque<CallFrame> callStack = new ArrayDeque<CallFrame>();
    private final int maxDepth;
    private final Mode mode;
    private final SystemErrorReporter errorReporter;
    
    private static final String TR_CLASS = "TryCallResult";
    
    private interface NativeMethod {
        Value call(Interpreter interpreter, Value thisValue, List<Value> args);
    }

    public Interpreter(Environment env, Map<String, BuiltinFunction> functions, DataShapingRegistry shapingRegistry, int maxDepth, Mode mode, SystemErrorReporter errorReporter) {
        this.env = env;
        this.functions = functions;
        this.shapingRegistry = shapingRegistry;
        this.maxDepth = maxDepth;
        this.mode = (mode == null) ? Mode.STRICT : mode;
        this.errorReporter = errorReporter;
    }

    /**
     * Apply automatic validation on user-function arguments based on the parameter naming convention:
     *
     *   <validatorName>_<anything>??  -> validate REQUIRED (throw if validator missing)
     *   <validatorName>_<anything>    -> validate IF validator exists (skip if missing)
     *   no_validation                 -> always skip validation for this parameter
     *
     * Only the substring before the first '_' participates in validator lookup.
     */
    public Value maybeValidateUserArg(String fnName, String paramLexeme, Value arg) {
        if (shapingRegistry == null || paramLexeme == null) return arg;

        String raw = paramLexeme;
        if ("no_validation".equals(raw)) return arg;

        boolean required = raw.endsWith("??");
        if (required) raw = raw.substring(0, raw.length() - 2);

        String match = raw;
        int us = raw.indexOf('_');
        if (us >= 0) match = raw.substring(0, us);
        match = match.trim();
        if (match.isEmpty() || "no_validation".equals(match)) return arg;

        boolean exists = shapingRegistry.has(match);
        if (!exists) {
        	String fn = "type_" + match;

            String typeFn = "type_" + match;
            boolean fnExists = userFunctions.containsKey(typeFn) || functions.containsKey(typeFn);

            if (fnExists) {
            	// Call the ES validator function with the argument as the only parameter.
                Value res = invokeByName(typeFn, List.of(arg));

                // Convention:
                // - true  => ok
                // - false => fail
                // - array => fail with details (anything else is an error)
                if (res.getType() == Value.Type.BOOL) {
                    if (res.asBool()) return arg;

                    reportSystemError("type_validation", typeFn, null,
                                "type validator returned false for " + fnName + "(" + paramLexeme + ")");
                    throw new RuntimeException("Type validation failed: " + match);
                }

                if (res.getType() == Value.Type.ARRAY) {
                    reportSystemError("type_validation", typeFn, null,
                                "type validator returned errors for " + fnName + "(" + paramLexeme + "): " + res);
                    throw new RuntimeException("Type validation failed: " + res);
                }

                reportSystemError("type_validation", typeFn, null,
                            "type validator must return bool or array, got " + res.getType());
                throw new RuntimeException("Invalid return from " + typeFn + ": " + res.getType());
            }
            
            if (required) {
                reportSystemError("shape_not_found", match, null,
                        "Missing required validator '" + match + "' for " + fnName + "(" + paramLexeme + ")");
                throw new RuntimeException("Missing required validator: " + match);
            }
            return arg;
        }

        ElaraDataShaper.Shape<Value> shape = shapingRegistry.get(match);
        if (shape.inputs().isEmpty()) {
            reportSystemError("shape_invalid", match, null,
                    "Validator shape '" + match + "' must declare at least one input field");
            throw new RuntimeException("Validator shape has no input fields: " + match);
        }

        // For argument validation we treat the first input field spec as the value contract.
        ElaraDataShaper.FieldSpec<Value> spec = shape.inputs().values().iterator().next();
        List<ElaraDataShaper.ValidationError> errs = shapingRegistry.shaper().validate(spec, arg, shape, fnName + "." + paramLexeme);
        if (!errs.isEmpty()) {
            String msg = errs.get(0).toString();
            reportSystemError("shape_validation", match, null, msg);
            throw new RuntimeException(msg);
        }
        return arg;
    }


    public String currentFunctionName() {
        return callStack.isEmpty() ? null : callStack.peek().functionName;
    }

    void reportSystemError(String kind, String name, Token token, String message) {
        if (errorReporter != null) {
            errorReporter.report(new RuntimeException(kind+" : "+name+" : "+ token+" : "+ message), this, kind, name, token, message);
        }
    }

    public Value invokeForHost(String targetName, List<Value> args) {
        return invokeByName(targetName, args);
    }

    public void execute(List<Stmt> program) {
        for (Stmt stmt : program) stmt.accept(this);
    }

    public void visitExprStmt(ExprStmt stmt) { eval(stmt.expression); }

    public void visitVarStmt(VarStmt stmt) {
        Value value = eval(stmt.initializer);
        env.define(stmt.name.lexeme, value);
    }

    public void visitBlockStmt(Block stmt) {
        env.pushBlock();
        try {
            for (Stmt s : stmt.statements) s.accept(this);
        } finally {
            env.popBlock();
        }
    }

    public void visitIfStmt(If stmt) {
        Value cond = eval(stmt.condition);
        if (isTruthy(cond)) stmt.thenBranch.accept(this);
        else if (stmt.elseBranch != null) stmt.elseBranch.accept(this);
    }

    public void visitWhileStmt(While stmt) {
        while (isTruthy(eval(stmt.condition))) {
            try {
                stmt.body.accept(this);
            } catch (BreakSignal bs) {
                break;
            }
        }
    }

    public void visitFunctionStmt(FunctionStmt stmt) {
        String name = stmt.name.lexeme;
        if (functions.containsKey(name)) {
            throw new RuntimeException("Function name conflicts with builtin: " + name);
        }
        if (userFunctions.containsKey(name)) {
            throw new RuntimeException("Function already defined: " + name);
        }
        userFunctions.put(name, new UserFunction(name, stmt.params, stmt.body, env));
    }

    @Override
    public void visitClassStmt(ClassStmt stmt) {
        String className = stmt.name.lexeme; // or stmt.name.lexeme if Token

        LinkedHashMap<String, Object> methods = new LinkedHashMap<>();
        LinkedHashMap<String, Object> vars = new LinkedHashMap<>();
        
        for (FunctionStmt fn : stmt.methods) {
            String mName = fn.name.lexeme; // or fn.name.lexeme if Token

            // Compile into a callable function object NOW (so method calls work)
            UserFunction uf = new UserFunction(
                    className + "." + mName,   // debug name only
                    fn.params,
                    fn.body,
                    env                         // closure
            );

            // IMPORTANT: store by SIMPLE name
            methods.put(mName, uf);
        }
        
        for (VarStmt v : stmt.vars) {
        	String mName = v.name.lexeme;
        	if (v.initializer instanceof Literal) {
        		vars.put(mName, v.initializer);
        	} else {
        		throw new RuntimeException("Type is not a literal");
        	}
        }

        Value.ClassDescriptor desc = new Value.ClassDescriptor(className, methods, vars);

        // If you're keeping class descriptors in env now:
        env.define(className, new Value(Value.Type.CLASS, desc));
        // (or however you currently store it)

        // And/or keep registry:
        classes.put(className, desc);
    }


    public void visitReturnStmt(ReturnStmt stmt) {
        throw new ReturnSignal(stmt.value == null ? Value.nil() : eval(stmt.value));
    }

    public void visitBreakStmt(BreakStmt stmt) {
        throw new BreakSignal();
    }

    public Value visitBinaryExpr(Binary expr) {
        Value left = eval(expr.left);
        Value right = eval(expr.right);
        TokenType op = expr.operator.type;

        switch (op) {
            case PLUS: {
                // 1) numbers
                if (left.getType() == Value.Type.NUMBER && right.getType() == Value.Type.NUMBER) {
                    return Value.number(left.asNumber() + right.asNumber());
                }

                // 2) FUNC is NOT concat-able (even with a STRING)
                if (left.getType() == Value.Type.FUNC || right.getType() == Value.Type.FUNC) {
                    throw new RuntimeException("Unsupported operand types for '+': " + left.getType() + ", " + right.getType());
                }

                // 3) string concatenation (STRING only)
                if (left.getType() == Value.Type.STRING || right.getType() == Value.Type.STRING) {
                    return Value.string(stringify(left) + stringify(right));
                }

                throw new RuntimeException("Unsupported operand types for '+': " + left.getType() + ", " + right.getType());
            }
            case MINUS:
                requireNumber(left, right, expr.operator);
                return Value.number(left.asNumber() - right.asNumber());
            case STAR:
                requireNumber(left, right, expr.operator);
                return Value.number(left.asNumber() * right.asNumber());
            case SLASH:
                requireNumber(left, right, expr.operator);
                return Value.number(left.asNumber() / right.asNumber());
            case PERCENT:
                requireNumber(left, right, expr.operator);
                return Value.number(left.asNumber() % right.asNumber());

            case GREATER:
                requireNumber(left, right, expr.operator);
                return Value.bool(left.asNumber() > right.asNumber());
            case GREATER_EQUAL:
                requireNumber(left, right, expr.operator);
                return Value.bool(left.asNumber() >= right.asNumber());
            case LESS:
                requireNumber(left, right, expr.operator);
                return Value.bool(left.asNumber() < right.asNumber());
            case LESS_EQUAL:
                requireNumber(left, right, expr.operator);
                return Value.bool(left.asNumber() <= right.asNumber());

            case EQUAL_EQUAL:
                return Value.bool(isEqual(left, right));
            case BANG_EQUAL:
                return Value.bool(!isEqual(left, right));

            default:
                throw new RuntimeException("Unsupported binary operator: " + op);
        }
    }

    public Value visitUnaryExpr(Unary expr) {
        Value right = eval(expr.right);
        switch (expr.operator.type) {
            case BANG:
                return Value.bool(!isTruthy(right));
            case MINUS:
                if (right.getType() != Value.Type.NUMBER) throw new RuntimeException("Unary '-' expects number");
                return Value.number(-right.asNumber());
            default:
                throw new RuntimeException("Unsupported unary operator: " + expr.operator.type);
        }
    }

    public Value visitLiteralExpr(Literal expr) {
        if (expr.value == null) return Value.nil();
        if (expr.value instanceof Boolean) return Value.bool((Boolean) expr.value);
        if (expr.value instanceof Double) return Value.number((Double) expr.value);
        if (expr.value instanceof String) return Value.string((String) expr.value);
        if (expr.value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Expr.ExprInterface> exprs = (List<Expr.ExprInterface>) expr.value;
            List<Value> values = new ArrayList<Value>(exprs.size());
            for (Expr.ExprInterface e : exprs) values.add(eval(e));
            return Value.array(values);
        }
        throw new RuntimeException("Unsupported literal value: " + expr.value);
    }

    public Value visitVariableExpr(Variable expr) {
        String name = expr.name.lexeme;

        // 1) Normal variable lookup first (variables shadow functions)
        if (env.exists(name)) {
            return env.get(name);
        }
        
        if (env.instance_owner != null) {
        	if ("this".equals(name))
        		return env.instance_owner;
        	
        	if (env.instance_owner.asClassInstance()._this.containsKey(name)) {
        		return env.instance_owner.asClassInstance()._this.get(name);
        	}
        }

        // 2) If no variable, but a function exists with that name, return FUNC
        if (userFunctions.containsKey(name) || functions.containsKey(name)) {
            return Value.func(name);
        }

        // 3) Otherwise: real missing variable
        reportSystemError("var_not_found", name, expr.name, "Undefined variable: " + name);
        throw new RuntimeException("Undefined variable: " + name);
    }

    public Value visitAssignExpr(Assign expr) {
        Value value = eval(expr.value);
        env.assign(expr.name.lexeme, value);
        return value;
    }

    public Value visitSetIndexExpr(SetIndexExpr expr) {
        Value target = eval(expr.target);
        Value idxV = eval(expr.index);
        Value value = eval(expr.value);

        // Map assignment: m["key"] = v
        if (target.getType() == Value.Type.MAP) {
            if (idxV.getType() != Value.Type.STRING) throw new RuntimeException("Map index must be a string key");
            Map<String, Value> m = target.asMap();
            if (m == null) throw new RuntimeException("Map is null");
            m.put(idxV.asString(), value);
            return value;
        }

        // Array/bytes assignment: x[0] = v
        if (idxV.getType() != Value.Type.NUMBER) throw new RuntimeException("Index must be a number");
        int i = (int) idxV.asNumber();

        if (target.getType() == Value.Type.ARRAY) {
            List<Value> list = target.asArray();
            if (i < 0) throw new RuntimeException("Array index out of bounds: " + i);

            // allow append at end: a[len(a)] = v
            if (i == list.size()) {
                list.add(value);
                return value;
            }

            if (i > list.size()) throw new RuntimeException("Array index out of bounds: " + i);
            list.set(i, value);
            return value;
        }

        if (target.getType() == Value.Type.BYTES) {
            byte[] bb = (byte[]) target.value;
            if (bb == null) throw new RuntimeException("Bytes is null");
            if (i < 0 || i >= bb.length) throw new RuntimeException("Bytes index out of bounds: " + i);

            if (value.getType() != Value.Type.NUMBER) throw new RuntimeException("Bytes assignment expects a number 0..255");
            int b = (int) value.asNumber();
            if (b < 0 || b > 255) throw new RuntimeException("Byte value out of range: " + b);

            bb[i] = (byte) b;
            return value;
        }

        throw new RuntimeException("Index assignment not supported on type: " + target.getType());
    }


    public Value visitLogicalExpr(Logical expr) {
        Value left = eval(expr.left);
        if (expr.operator.type == TokenType.OR_OR) {
            if (isTruthy(left)) return left;
        } else {
            if (!isTruthy(left)) return left;
        }
        return eval(expr.right);
    }

    private void ensureTryCallResultClass() {
        if (classes.containsKey(TR_CLASS)) return;

        LinkedHashMap<String, Object> methods = new LinkedHashMap<>();

        // r.result() -> bool
        methods.put("result", (NativeMethod) (itp, thisValue, a) -> {
            Value.ClassInstance inst = thisValue.asClassInstance();
            Value st = itp.env.get(inst.stateKey());
            Map<String, Value> m = st.asMap();
            Value v = (m == null) ? Value.nil() : m.get("result");
            return (v == null) ? Value.bool(false) : v;
        });

        // r.value() -> any
        methods.put("value", (NativeMethod) (itp, thisValue, a) -> {
            Value.ClassInstance inst = thisValue.asClassInstance();
            Value st = itp.env.get(inst.stateKey());
            Map<String, Value> m = st.asMap();
            Value v = (m == null) ? Value.nil() : m.get("value");
            return (v == null) ? Value.nil() : v;
        });

        // r.error() -> array<string> (always)
        methods.put("error", (NativeMethod) (itp, thisValue, a) -> {
            Value.ClassInstance inst = thisValue.asClassInstance();
            Value st = itp.env.get(inst.stateKey());
            Map<String, Value> m = st.asMap();
            Value v = (m == null) ? null : m.get("error");
            if (v == null || v.getType() != Value.Type.ARRAY) return Value.array(new ArrayList<>());
            return v;
        });

        Value.ClassDescriptor desc = new Value.ClassDescriptor(TR_CLASS, methods, null);
        classes.put(TR_CLASS, desc);

        // Optional: also expose as a global CLASS value if you want (not required for trycall)
        // env.define(TR_CLASS, new Value(Value.Type.CLASS, desc));
    }

    private Value makeTryCallResult(boolean ok, Value valueOrNull, List<String> errors) {
        // Allocate instance id
        String uuid = java.util.UUID.randomUUID().toString();
        String key = TR_CLASS + "." + uuid; // use constant

        // State map stored in env under "TryCallResult.<uuid>"
        LinkedHashMap<String, Value> state = new LinkedHashMap<>();
        state.put("result", Value.bool(ok)); // <-- FIX (was "ok")
        state.put("value", (valueOrNull == null) ? Value.nil() : valueOrNull);

        // errors is ALWAYS an array<string>
        List<Value> errArr = new ArrayList<>();
        if (errors != null) {
            for (String s : errors) errArr.add(Value.string(s == null ? "" : s));
        }
        state.put("error", Value.array(errArr));

        env.define(key, Value.map(state));

        Value.ClassInstance inst = new Value.ClassInstance(TR_CLASS, uuid);
        return new Value(Value.Type.CLASS_INSTANCE, inst);
    }
    
    public Value tryReadTryCallField(Value.ClassInstance inst, String field) {
        // Look up state map: env["TryCallResult.<uuid>"]
        String key = inst.stateKey();
        Value st = env.exists(key) ? env.get(key) : Value.nil();
        if (st.getType() != Value.Type.MAP) return Value.nil();

        Map<String, Value> m = st.asMap();
        if (m == null) return Value.nil();
        Value v = m.get(field);
        return (v == null) ? Value.nil() : v;
    }

    public Value visitCallExpr(Call expr) {
        if (!(expr.callee instanceof Variable)) throw new RuntimeException("Invalid function call target");
        String name = ((Variable) expr.callee).name.lexeme;
        List<Boolean> copyFlags = new ArrayList<>();

        // Evaluate + expand args (spread operator: '**arrayExpr').
        List<Value> args = new ArrayList<Value>();
        for (CallArg a : expr.arguments) {
            Value v = eval(a.expr);
            if (!a.spread) {
                args.add(v);
                copyFlags.add(a.copy);
                continue;
            }
            if (v.getType() != Value.Type.ARRAY) {
                String where = (a.spreadToken != null) ? (" at line " + a.spreadToken.line) : "";
                throw new RuntimeException("Spread operator expects an array" + where);
            }
            for (Value item : v.asArray()) {
                args.add(item);
                copyFlags.add(a.copy); // '&' applies per-expanded element
            }
        }

        if ("trycall".equals(name)) {
            if (args.isEmpty()) {
                return makeTryCallResult(false, Value.nil(), List.of("trycall() expects at least 1 argument (fnName)"));
            }
            Value fnNameVal = args.get(0);
            if (fnNameVal.getType() != Value.Type.STRING) {
                return makeTryCallResult(false, Value.nil(), List.of("trycall() first argument must be a string function name"));
            }

            String targetName = fnNameVal.asString();
            List<Value> rest = (args.size() == 1) ? Collections.emptyList() : args.subList(1, args.size());

            try {
                // IMPORTANT: catch *everything* from inside the invocation path
            	List<Boolean> restCopy = (copyFlags.size() <= 1) ? Collections.emptyList() : copyFlags.subList(1, copyFlags.size());
            	Value out = invokeByNameWithCopyFlags(targetName, rest, restCopy);

                return makeTryCallResult(true, out, Collections.emptyList());
            } catch (RuntimeException e) {
                String msg = (e.getMessage() == null) ? e.toString() : e.getMessage();
                return makeTryCallResult(false, Value.nil(), List.of(msg));
            }
        }

        // varexists("varName") -> bool
        if ("varexists".equals(name)) {
            if (args.size() != 1) {
                throw new RuntimeException("varexists() expects 1 argument, got " + args.size());
            }
            Value v = args.get(0);
            if (v.getType() != Value.Type.STRING) {
                throw new RuntimeException("varexists() expects a string variable name");
            }
            return Value.bool(env.exists(v.asString()));
        }
        
        // getglobal("varName") -> value|nil
        if ("getglobal".equals(name)) {
            if (args.size() != 1) throw new RuntimeException("getglobal() expects 1 argument");
            Value v = args.get(0);
            if (v.getType() != Value.Type.STRING) throw new RuntimeException("getglobal() expects a string name");

            Environment g = env;
            while (g.parent != null) g = g.parent;          // walk to root env
            return g.existsInCurrentScope(v.asString()) ? Value.nil() : g.get(v.asString());
        }

        // setglobal("varName", value) -> value
        if ("setglobal".equals(name)) {
            if (args.size() != 2) throw new RuntimeException("setglobal() expects 2 arguments");
            Value k = args.get(0);
            Value val = args.get(1);
            if (k.getType() != Value.Type.STRING) throw new RuntimeException("setglobal() first arg must be string");

            Environment g = env;
            while (g.parent != null) g = g.parent;          // walk to root env
            g.assign(k.asString(), val);                  // overwrite/create at root
            return val;
        }
        
        // debug_dump() -> string(json)
        if ("debug_dump".equals(name)) {
            if (!args.isEmpty()) throw new RuntimeException("debug_dump() expects 0 arguments");
            return Value.string(dumpAllStateJson());
        }

        // debug_print() -> null, prints json
        if ("debug_print".equals(name)) {
            if (!args.isEmpty()) throw new RuntimeException("debug_print() expects 0 arguments");
            String json = dumpAllStateJson();
            System.out.println(json);
            return Value.nil();
        }

        if (callStack.size() >= maxDepth) throw new RuntimeException("Max call depth exceeded");

        // Evaluate args once.
        //List<Value> args = new ArrayList<Value>(expr.arguments.size());
        //for (Expr argExpr : expr.arguments) args.add(eval(argExpr));

        // call("fnName", ...args) dynamic dispatch (INFERENCE mode only)
        if ("call".equals(name)) {
            if (mode != Mode.INFERENCE) {
                throw new RuntimeException("call() is only available in INFERENCE mode");
            }
            if (args.isEmpty()) throw new RuntimeException("call() expects at least 1 argument");
            Value fnNameVal = args.get(0);
            if (fnNameVal.getType() != Value.Type.STRING) {
                throw new RuntimeException("call() first argument must be a string function name");
            }
            String targetName = fnNameVal.asString();
            List<Value> rest = (args.size() == 1) ? Collections.emptyList() : args.subList(1, args.size());

            callStack.push(new CallFrame("call->" + targetName, new ArrayList<>(rest)));
            try {
            	List<Boolean> restCopy = (copyFlags.size() <= 1) ? Collections.emptyList() : copyFlags.subList(1, copyFlags.size());
            	return invokeByNameWithCopyFlags(targetName, rest, restCopy);
            } finally {
                callStack.pop();
            }
        }

        // Prefer user-defined function over builtins (JS-like shadowing).
        UserFunction uf = userFunctions.get(name);
        if (uf != null) {
            callStack.push(new CallFrame(name, args));
            try {
            	return uf.call(this, args, copyFlags);
            } finally {
                callStack.pop();
            }
        }

        BuiltinFunction fn = functions.get(name);
        if (fn != null) {
            callStack.push(new CallFrame(name, args));
            try {
            	return invokeByNameWithCopyFlags(name, args, copyFlags);
            } finally {
                callStack.pop();
            }
        }

        // INFERENCE mode: if `name` is a string variable, treat its value as the actual function name.
        if (mode == Mode.INFERENCE && env.exists(name)) {
            Value possible = env.get(name);
            if (possible.getType() == Value.Type.STRING) {
                String targetName = possible.asString();
                callStack.push(new CallFrame(name + "->" + targetName, args));
                try {
                	return invokeByNameWithCopyFlags(targetName, args, copyFlags);
                } finally {
                    callStack.pop();
                }
            }
        }            reportSystemError("fn_not_found", name, expr.paren, "Unknown function: " + name);
        throw new RuntimeException("Unknown function: " + name);
    }
    
    private Value invokeByNameWithCopyFlags(String name, List<Value> args, List<Boolean> copyFlags) {
        // builtins:
        if (functions.containsKey(name)) {
            // builtins can ignore copy flags OR you can apply them here if you want:
            // (I’d apply them here so builtins receive copied values consistently.)
            List<Value> cooked = new ArrayList<>(args.size());
            for (int i = 0; i < args.size(); i++) {
                boolean c = (copyFlags != null && i < copyFlags.size()) && copyFlags.get(i);
                Value v = args.get(i);
                cooked.add(v == null ? null : v.getForChildStackFrame(c));
            }
            return functions.get(name).call(cooked);
        }

        // user function:
        UserFunction uf = userFunctions.get(name);
        if (uf != null) {
            return uf.call(this, args, copyFlags);
        }

        throw new RuntimeException("Undefined function: " + name);
    }
    
    @Override
    public Value visitMethodCallExpr(MethodCallExpr expr) {
        Value recv = eval(expr.receiver);
        if (recv.getType() != Value.Type.CLASS_INSTANCE) {
            throw new RuntimeException("Only class instances support '.' method calls.");
        }
        
        // Evaluate + expand args (spread + & copy flags)
        List<Value> args = new ArrayList<>();
        List<Boolean> copyFlags = new ArrayList<>();

        if (expr.arguments != null) {
            for (CallArg a : expr.arguments) {
                Value v = eval(a.expr);

                if (!a.spread) {
                    args.add(v);
                    copyFlags.add(a.copy);
                    continue;
                }

                if (v.getType() != Value.Type.ARRAY) {
                    String where = (a.spreadToken != null) ? (" at line " + a.spreadToken.line) : "";
                    throw new RuntimeException("Spread operator expects an array" + where);
                }

                for (Value item : v.asArray()) {
                    args.add(item);
                    copyFlags.add(a.copy); // spread + & means copy each expanded element
                }
            }
        }


        Value.ClassInstance inst = recv.asClassInstance();

        // Ensure the pseudo-class exists so .result()/.value()/.error() resolve
        if (TR_CLASS.equals(inst.className)) {
            ensureTryCallResultClass();
        }

        if ("trycall".equals(expr.method.lexeme)) {
            if (args.size() < 1) {
                return makeTryCallResult(false, Value.nil(),
                        List.of("trycall(methodName, ...args) expects at least 1 argument"));
            }

            Value methodNameV = args.get(0);
            if (methodNameV.getType() != Value.Type.STRING) {
                return makeTryCallResult(false, Value.nil(),
                        List.of("trycall first argument must be a string method name"));
            }

            String methodName = methodNameV.asString();
            List<Value> callArgs = (args.size() == 1) ? Collections.emptyList() : args.subList(1, args.size());
            List<Boolean> callCopy = (copyFlags.size() <= 1) ? Collections.emptyList() : copyFlags.subList(1, copyFlags.size());

            try {
                Value.ClassDescriptor desc = classes.get(inst.className);
                if (desc == null) {
                    return makeTryCallResult(false, Value.nil(), List.of("Unknown class: " + inst.className));
                }

                Object fnObj = desc.methods.get(methodName);
                if (!(fnObj instanceof UserFunction) && !(fnObj instanceof NativeMethod)) {
                    return makeTryCallResult(false, Value.nil(),
                            List.of("Unknown method: " + inst.className + "." + methodName));
                }

                // Apply copy flags at call boundary (same contract as functions)
                List<Value> cooked = new ArrayList<>(callArgs.size());
                for (int i = 0; i < callArgs.size(); i++) {
                    boolean c = (callCopy != null && i < callCopy.size()) && callCopy.get(i);
                    Value v = callArgs.get(i);
                    cooked.add(v == null ? null : v.getForChildStackFrame(c));
                }

                Value out;
                if (fnObj instanceof NativeMethod) {
                    out = ((NativeMethod) fnObj).call(this, recv, cooked);
                } else {
                    out = ((UserFunction) fnObj).callWithThis(this, recv, cooked /* and copyFlags if you add it */);
                }
                return makeTryCallResult(true, out, Collections.emptyList());

            } catch (RuntimeException e) {
                String msg = (e.getMessage() == null) ? e.toString() : e.getMessage();
                return makeTryCallResult(false, Value.nil(), List.of(msg));
            }
        }

        // normal method dispatch (NOW supports NativeMethod too)
        Value.ClassDescriptor desc = classes.get(inst.className);
        if (desc == null) {
            throw new RuntimeException("Unknown class: " + inst.className);
        }

        Object fnObj = desc.methods.get(expr.method.lexeme);

        if (fnObj instanceof NativeMethod) {
            // Native has no param-binding stage, so apply copy flags here
            List<Value> cooked = new ArrayList<>(args.size());
            for (int i = 0; i < args.size(); i++) {
                boolean c = (copyFlags != null && i < copyFlags.size()) && copyFlags.get(i);
                Value v = args.get(i);
                cooked.add(v == null ? null : v.getForChildStackFrame(c));
            }
            return ((NativeMethod) fnObj).call(this, recv, cooked);
        }

        if (fnObj instanceof UserFunction) {
            // UserFunction will apply copyFlags during param binding
            return ((UserFunction) fnObj).callWithThis(this, recv, args, copyFlags);
        }

        throw new RuntimeException("Unknown method: " + inst.className + "." + expr.method.lexeme);
    }

    public Value callMethodWithThis(UserFunction fn, Value thisValue, List<Value> args) {
        return fn.callWithThis(this, thisValue, args);
    }
    
    public Value callMethodWithThis(UserFunction fn, Value thisValue, List<Value> args, List<Boolean> copyFlags) {
        return fn.callWithThis(this, thisValue, args, copyFlags);
    }

    public Value invokeByName(String targetName, List<Value> args, List<Boolean> copyFlags) {
        return invokeByNameWithCopyFlags(targetName, args, copyFlags);
    }
    
    public Value invokeByName(String targetName, List<Value> args) {
        return invokeByNameWithCopyFlags(targetName, args, null);
    }

    public Value visitIndexExpr(IndexExpr expr) {
        Value target = eval(expr.target);
        Value idx = eval(expr.index);

        // Map indexing: m["key"]
        if (target.getType() == Value.Type.MAP) {
            if (idx.getType() != Value.Type.STRING) throw new RuntimeException("Map index must be a string key");
            Map<String, Value> m = target.asMap();
            if (m == null) return Value.nil();
            Value out = m.get(idx.asString());
            return (out == null) ? Value.nil() : out;
        }

        // Array/bytes indexing: x[0]
        if (idx.getType() != Value.Type.NUMBER) throw new RuntimeException("Index must be a number");
        int i = (int) idx.asNumber();

        if (target.getType() == Value.Type.BYTES) {
            byte[] bb = target.asBytes();
            if (bb == null) throw new RuntimeException("Bytes is null");
            if (i < 0 || i >= bb.length) throw new RuntimeException("Bytes index out of bounds: " + i);
            return Value.number((double) (bb[i] & 0xFF));
        }

        if (target.getType() == Value.Type.ARRAY) {
            List<Value> list = target.asArray();
            if (i < 0 || i >= list.size()) throw new RuntimeException("Array index out of bounds: " + i);
            return list.get(i);
        }
        throw new RuntimeException("Indexing not supported on type: " + target.getType());
    }

    public Value eval(Expr.ExprInterface expr) { return expr.accept(this); }

    public boolean isTruthy(Value v) {
        switch (v.getType()) {
            case NULL: return false;
            case BOOL: return v.asBool();
            case NUMBER: return v.asNumber() != 0.0;
            case STRING: return !v.asString().isEmpty();
            case FUNC:   return !v.asString().isEmpty();
            case BYTES: {
                byte[] bb = v.asBytes();
                return bb != null && bb.length != 0;
            }
            case ARRAY: return !v.asArray().isEmpty();
            case MATRIX: return !v.asMatrix().isEmpty();
            case MAP: {
                Map<String, Value> m = v.asMap();
                return m != null && !m.isEmpty();
            }
            default: return false;
        }
    }
    
    public Value evalNew(String className, List<Value> args) {
        Value.ClassDescriptor desc = classes.get(className);
        if (desc == null) throw new RuntimeException("Unknown class: " + className);

        String uuid = java.util.UUID.randomUUID().toString();
        String key = className + "." + uuid;

        // Create state map (match your MAP representation)
        LinkedHashMap<String, Value> state = new LinkedHashMap<>();
        
        env.define(key, Value.map(state)); // <-- adapt to your actual map constructor

        // Create instance handle
        Value.ClassInstance inst = new Value.ClassInstance(className, uuid);
        Value instanceValue = new Value(Value.Type.CLASS_INSTANCE, inst);
        liveInstances.put(key, inst);

        // define variables
        for (Entry<String, Object> e : desc.vars.entrySet()) {
        	String name = e.getKey();
        	if (e.getValue() instanceof Literal) {
        		Literal v = (Literal)e.getValue();
        		Object val = ((Literal)e.getValue()).value;
	        	
        		// TODO: Must be registered to the current _this
	        	if (val instanceof String) {
	        		Value _v = new Value(Value.Type.STRING, val);
	        		((ClassInstance)instanceValue.value)._this.put(name, _v);
	        	} else if (val instanceof Double) {
	        		Value _v = new Value(Value.Type.NUMBER, val);
	        		((ClassInstance)instanceValue.value)._this.put(name, _v);
	        	} else if (val instanceof Boolean) {
	        		Value _v = new Value(Value.Type.BOOL, val);
	        		((ClassInstance)instanceValue.value)._this.put(name, _v);
	        	} else {
	        		throw new RuntimeException("Type not yet supported in call instantiation");
	        	}
	        	
        	}
        }
        
        // Invoke constructor if present
        Object ctorObj = desc.methods.get("constructor");
        if (ctorObj instanceof UserFunction) {
            ((UserFunction) ctorObj).callWithThis(this, (Value)instanceValue, args);
        }

        return instanceValue;
    }


    public boolean isEqual(Value a, Value b) {
        if (a.getType() == Value.Type.NULL && b.getType() == Value.Type.NULL) return true;
        if (a.getType() == Value.Type.NULL || b.getType() == Value.Type.NULL) return false;
        if (a.getType() != b.getType()) return false;
        
        boolean aStrish = (a.getType() == Value.Type.STRING || a.getType() == Value.Type.FUNC);
        boolean bStrish = (b.getType() == Value.Type.STRING || b.getType() == Value.Type.FUNC);
        if (aStrish && bStrish) {
            return a.asString().equals(b.asString());
        }

        switch (a.getType()) {
            case NUMBER: return a.asNumber() == b.asNumber();
            case BOOL: return a.asBool() == b.asBool();
            case BYTES: {
                byte[] ab = a.asBytes();
                byte[] bb = b.asBytes();
                return Arrays.equals(ab, bb);
            }
            case ARRAY: return a.asArray().equals(b.asArray());
            case MATRIX: return a.asMatrix().equals(b.asMatrix());
            case MAP: {
                Map<String, Value> am = a.asMap();
                Map<String, Value> bm = b.asMap();
                if (am == null && bm == null) return true;
                if (am == null || bm == null) return false;
                return am.equals(bm);
            }
            case CLASS_INSTANCE: {
                Value.ClassInstance ca = a.asClassInstance();
                Value.ClassInstance cb = b.asClassInstance();
                return ca.className.equals(cb.className) && ca.uuid.equals(cb.uuid);
            }

            default: return true;
        }
    }

    public void requireNumber(Value a, Value b, Token op) {
        if (a.getType() != Value.Type.NUMBER || b.getType() != Value.Type.NUMBER) {
            throw new RuntimeException("Operator '" + op.lexeme + "' expects numbers");
        }
    }

    public String stringify(Value v) {
        if (v.getType() == Value.Type.NULL) return "null";
        switch (v.getType()) {
            case STRING: return v.asString();
            case FUNC:   return v.asString();
            default: return v.toString();
        }
    }

    public static final class ReturnSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
		final Value value;
        ReturnSignal(Value value) { super(null, null, false, false); this.value = value; }
    }

    public static final class BreakSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;

		BreakSignal() { super(null, null, false, false); }
    }

    

	@Override
	public Value visitMapLiteralExpr(MapLiteral expr) {
		LinkedHashMap<String, Value> out = new LinkedHashMap<>();
        if (expr != null && expr.entries != null) {
            for (Map.Entry<String, Expr.ExprInterface> e : expr.entries.entrySet()) {
                out.put(e.getKey(), eval(e.getValue()));
            }
        }
        return Value.map(out);
	}

	@Override
	public Value visitNewExpr(NewExpr expr) {
	    List<Value> args = new ArrayList<>();
	    for (Expr.ExprInterface a : expr.args) {
	        args.add(eval(a)); // your interpreter uses eval()
	    }
	    return evalNew(expr.className.lexeme, args);
	}

	@Override
	public void visitFreeStmt(FreeStmt stmt) {
	    Value v = eval(stmt.target);

	    if (v.getType() != Value.Type.CLASS_INSTANCE) {
	        throw new RuntimeException("free expects a class instance");
	    }

	    Value.ClassInstance inst = v.asClassInstance();
	    Value.ClassDescriptor desc = classes.get(inst.className);
	    if (desc == null) throw new RuntimeException("Unknown class: " + inst.className);

	    // call on_free if present
	    Object fnObj = desc.methods.get("on_free");
	    if (fnObj instanceof UserFunction) {
	        ((UserFunction) fnObj).callWithThis(this, v, Collections.emptyList());
	    }

	    // remove state
	    String key = inst.stateKey(); // class.uuid
	    // IMPORTANT: you can't currently delete from env via API, so add a method:
	    env.remove(key);  // implement below
	    liveInstances.remove(key);
	}
	
	@Override
	public Value visitGetExpr(Expr.GetExpr expr) {
	    Value recv = eval(expr.receiver);
	    return objectGet(recv, expr.name); // funnels into stateKey map
	}

	@Override
	public Value visitSetExpr(Expr.SetExpr expr) {
	    Value recv = eval(expr.receiver);
	    Value val  = eval(expr.value);
	    objectSet(recv, expr.name, val);   // funnels into stateKey map
	    return val;
	}
	
	/*
	 * IMPORTANT NOTE
	 * Getting of any class member variable should end up here
	 */
	private Value objectGet(Value recv, Token name) {
	    // Only class instances support '.' property access in this VM
	    if (recv.getType() != Value.Type.CLASS_INSTANCE) {
	        throw new RuntimeException("Only class instances support '.' access.");
	    }
	    return getInstanceField(recv.asClassInstance(), name.lexeme);
	}

	/*
	 * IMPORTANT NOTE
	 * Setting of any class member variable should end up here
	 */
	private Value objectSet(Value recv, Token name, Value value) {
	    if (recv.getType() != Value.Type.CLASS_INSTANCE) {
	        throw new RuntimeException("Only class instances support '.' assignment.");
	    }
	    return setInstanceField(recv.asClassInstance(), name.lexeme, value);
	}
	
	@SuppressWarnings("unchecked")
	private Map<String, Value> requireInstanceStateMap(Value.ClassInstance inst) {
	    String key = inst.stateKey();

	    // Ensure state exists and is a map
	    if (!env.exists(key)) {
	        LinkedHashMap<String, Value> fresh = new LinkedHashMap<>();
	        env.define(key, Value.map(fresh));
	    }

	    Value st = env.get(key);
	    if (st.getType() != Value.Type.MAP) {
	        throw new RuntimeException("Instance state is not a map for: " + key + " (got " + st.getType() + ")");
	    }

	    Map<String, Value> m = st.asMap();
	    if (m == null) {
	        // env contains map(null) which is invalid for instance state
	        LinkedHashMap<String, Value> fresh = new LinkedHashMap<>();
	        env.assign(key, Value.map(fresh));
	        return fresh;
	    }

	    return m;
	}

	/**
	 * Ensures the authoritative instance state map is `inst._this` and that the ROOT
	 * environment has a visible `ClassName.uuid -> MAP(inst._this)` entry.
	 *
	 * Why: inside method frames, Environment.get("Class.uuid") may be redirected to
	 * the instance owner's `_this` map. Using `env.exists/get` in that situation can
	 * accidentally create shadow entries in the current scope. So for field access we
	 * operate on `inst._this` directly and keep the root snapshot key in sync.
	 */
	private Map<String, Value> ensureInstanceStateBinding(Value.ClassInstance inst) {
	    // Authoritative state lives here
	    Map<String, Value> state = inst._this;
	    if (state == null) {
	        throw new RuntimeException("Instance _this map is null for: " + inst.stateKey());
	    }

	    // Root env must expose Class.uuid -> MAP(state) for snapshots/debugging.
	    Environment root = env;
	    while (root.parent != null) root = root.parent;

	    String key = inst.stateKey();
	    if (!root.exists(key)) {
	        root.define(key, Value.map(state));
	        return state;
	    }

	    Value st = root.get(key);
	    if (st.getType() != Value.Type.MAP) {
	        root.assign(key, Value.map(state));
	        return state;
	    }

	    Map<String, Value> bound = st.asMap();
	    if (bound == null || bound != state) {
	        root.assign(key, Value.map(state));
	    }

	    return state;
	}

	private Value getInstanceField(Value.ClassInstance inst, String field) {
	    Map<String, Value> m = ensureInstanceStateBinding(inst);
	    Value v = m.get(field);
	    return (v == null) ? Value.nil() : v;
	}

	private Value setInstanceField(Value.ClassInstance inst, String field, Value value) {
	    Map<String, Value> m = ensureInstanceStateBinding(inst);
	    m.put(field, value);
	    return value;
	}
	
	// Interpreter.java
	private static String jsonEscape(String s) {
	    if (s == null) return "";
	    StringBuilder sb = new StringBuilder(s.length() + 16);
	    for (int i = 0; i < s.length(); i++) {
	        char c = s.charAt(i);
	        switch (c) {
	            case '\\': sb.append("\\\\"); break;
	            case '"':  sb.append("\\\""); break;
	            case '\n': sb.append("\\n"); break;
	            case '\r': sb.append("\\r"); break;
	            case '\t': sb.append("\\t"); break;
	            default:
	                if (c < 32) sb.append(String.format("\\u%04x", (int)c));
	                else sb.append(c);
	        }
	    }
	    return sb.toString();
	}

	private String valueToJson(Value v) {
	    if (v == null) return "null";
	    switch (v.getType()) {
	        case NULL:
	            return "null";
	        case NUMBER:
	            return Double.toString(v.asNumber());
	        case BOOL:
	            return v.asBool() ? "true" : "false";
	        case STRING:
	        case FUNC:
	            return "\"" + jsonEscape(v.asString()) + "\"";
	        case BYTES: {
	            byte[] bb = (byte[]) v.value;
	            if (bb == null) return "null";
	            // keep it compact: length only (or base64 later)
	            return "{\"bytes_len\":" + bb.length + "}";
	        }
	        case ARRAY: {
	            List<Value> a = v.asArray();
	            if (a == null) return "null";
	            StringBuilder sb = new StringBuilder();
	            sb.append('[');
	            for (int i = 0; i < a.size(); i++) {
	                if (i > 0) sb.append(',');
	                sb.append(valueToJson(a.get(i)));
	            }
	            sb.append(']');
	            return sb.toString();
	        }
	        case MATRIX: {
	            List<List<Value>> m = v.asMatrix();
	            if (m == null) return "null";
	            StringBuilder sb = new StringBuilder();
	            sb.append('[');
	            for (int r = 0; r < m.size(); r++) {
	                if (r > 0) sb.append(',');
	                List<Value> row = m.get(r);
	                if (row == null) {
	                    sb.append("null");
	                } else {
	                    sb.append('[');
	                    for (int c = 0; c < row.size(); c++) {
	                        if (c > 0) sb.append(',');
	                        sb.append(valueToJson(row.get(c)));
	                    }
	                    sb.append(']');
	                }
	            }
	            sb.append(']');
	            return sb.toString();
	        }
	        case MAP: {
	            Map<String, Value> map = v.asMap();
	            if (map == null) return "null";
	            StringBuilder sb = new StringBuilder();
	            sb.append('{');
	            boolean first = true;
	            for (Map.Entry<String, Value> e : map.entrySet()) {
	                if (!first) sb.append(',');
	                first = false;
	                sb.append('"').append(jsonEscape(e.getKey())).append("\":");
	                sb.append(valueToJson(e.getValue()));
	            }
	            sb.append('}');
	            return sb.toString();
	        }
	        case CLASS: {
	            Value.ClassDescriptor cd = v.asClass();
	            return "{\"class\":\"" + jsonEscape(cd.name) + "\",\"methods\":" + cd.methods.size() + "}";
	        }
	        case CLASS_INSTANCE: {
	            Value.ClassInstance ci = v.asClassInstance();
	            return "{\"instance\":\"" + jsonEscape(ci.stateKey()) + "\"}";
	        }
	        default:
	            return "\"<unsupported:" + v.getType() + ">\"";
	    }
	}

	private String dumpAllStateJson() {
	    StringBuilder sb = new StringBuilder(8192);

	    Environment cur = this.env;
	    Environment root = cur.root();

	    sb.append("{");

	    // 1) globals
	    sb.append("\"global\":");
	    sb.append(valueToJson(Value.map(Environment.global)));

	    // 2) env chain
	    sb.append(",\"env_chain\":[");
	    int idx = 0;
	    for (Environment e = cur; e != null; e = e.parent) {
	        if (idx++ > 0) sb.append(',');

	        sb.append('{');
	        sb.append("\"depth\":").append(idx - 1);

	        if (e.instance_owner != null) {
	            sb.append(",\"instance_owner\":\"").append(jsonEscape(e.instance_owner.asClassInstance().stateKey())).append('"');
	        } else {
	            sb.append(",\"instance_owner\":null");
	        }

	        // scopes
	        sb.append(",\"scopes\":[");
	        List<Map<String, Value>> scopes = e.debugScopesBottomToTop();
	        for (int si = 0; si < scopes.size(); si++) {
	            if (si > 0) sb.append(',');
	            sb.append(valueToJson(Value.map(scopes.get(si))));
	        }
	        sb.append("]");

	        // merged
	        sb.append(",\"merged\":").append(valueToJson(Value.map(e.debugFrameMerged())));

	        sb.append('}');
	    }
	    sb.append("]");

	    sb.append("}");
	    return sb.toString();
	}
	
	/**
	 * Returns a structured snapshot for export/import:
	 *  {
	 *    class_instances: [ { key, className, uuid, state } ... ],
	 *    environments: [ { vars, this_ref? } ... ]   // outer -> inner
	 *  }
	 */
	public Map<String, Value> snapshot() {
	    Map<String, Value> out = new LinkedHashMap<>();

	    // environments
	    List<Map<String, Value>> frames = env.snapshotFrames();
	    List<Value> frameVals = new ArrayList<>(frames.size());
	    for (Map<String, Value> f : frames) frameVals.add(Value.map(f));
	    out.put("environments", Value.array(frameVals));

	    // class_instances (authoritative heap dump from instance._this)
	    List<Value> instVals = new ArrayList<>(liveInstances.size());
	    for (Map.Entry<String, Value.ClassInstance> e : liveInstances.entrySet()) {
	        String key = e.getKey();
	        Value.ClassInstance inst = e.getValue();

	        Map<String, Value> ci = new LinkedHashMap<>();
	        ci.put("key", Value.string(key));
	        ci.put("className", Value.string(inst.className));
	        ci.put("uuid", Value.string(inst.uuid));
	        ci.put("state", Value.map(inst._this)); // authoritative
	        instVals.add(Value.map(ci));
	    }
	    out.put("class_instances", Value.array(instVals));

	    return out;
	}



}
