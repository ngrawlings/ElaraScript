package com.elara.script.parser;

import java.util.List;

import com.elara.script.parser.Interpreter.ReturnSignal;
import com.elara.script.parser.Statement.Stmt;

public class UserFunction {
    final String name;
    final List<Token> params;
    final List<Stmt> body;
    final Environment closure;

    UserFunction(String name, List<Token> params, List<Stmt> body, Environment closure) {
        this.name = name;
        this.params = params;
        this.body = body;
        this.closure = closure;
    }

    Value call(Interpreter interpreter, List<Value> args, List<Boolean> copyFlags) {
        if (args.size() != params.size()) {
            throw new RuntimeException(name + "() expects " + params.size() + " arguments, got " + args.size());
        }

        Environment previous = interpreter.env;

        // IMPORTANT:
        // New call frame should be a child of the function's closure (lexical scoping),
        // not a child of the caller's environment.
        interpreter.env = closure.childScope(null);

        try {
            for (int i = 0; i < params.size(); i++) {
                String pRaw = params.get(i).lexeme;
                Value v = args.get(i);

                // 1) validate/coerce
                v = interpreter.maybeValidateUserArg(name, pRaw, v);

                // 2) apply copy decision (AFTER validation, BEFORE binding)
                boolean c = (copyFlags != null && i < copyFlags.size()) && copyFlags.get(i);
                v = (v == null) ? null : v.getForChildStackFrame(c);

                // 3) bind param name (strip ??)
                String pVar = pRaw.endsWith("??") ? pRaw.substring(0, pRaw.length() - 2) : pRaw;
                interpreter.env.define(pVar, v);
            }

            try {
                for (Stmt s : body) s.accept(interpreter);
            } catch (ReturnSignal rs) {
                return rs.value;
            }

            return Value.nil();
        } finally {
            interpreter.env = previous;
        }
    }

    Value callWithThis(Interpreter interpreter, Value thisValue, List<Value> args, List<Boolean> copyFlags) {
        if (args.size() != params.size()) {
            throw new RuntimeException(name + "() expects " + params.size() + " arguments, got " + args.size());
        }

        Environment previous = interpreter.env;

        // Method call frame: child of closure, but instance_owner set.
        interpreter.env = closure.childScope(thisValue);

        try {
            for (int i = 0; i < params.size(); i++) {
                String pRaw = params.get(i).lexeme;
                Value v = args.get(i);

                v = interpreter.maybeValidateUserArg(name, pRaw, v);

                boolean c = (copyFlags != null && i < copyFlags.size()) && copyFlags.get(i);
                v = (v == null) ? null : v.getForChildStackFrame(c);

                String pVar = pRaw.endsWith("??") ? pRaw.substring(0, pRaw.length() - 2) : pRaw;
                interpreter.env.define(pVar, v);
            }

            try {
                for (Stmt s : body) s.accept(interpreter);
            } catch (ReturnSignal rs) {
                return rs.value;
            }

            return Value.nil();
        } catch (RuntimeException re) {
        	System.out.println(re.getMessage());
        	throw re;
        } finally {
            interpreter.env = previous;
        }
    }
    
    Value call(Interpreter interpreter, List<Value> args) {
        return call(interpreter, args, null);
    }

    Value callWithThis(Interpreter interpreter, Value thisValue, List<Value> args) {
        return callWithThis(interpreter, thisValue, args, null);
    }
}
