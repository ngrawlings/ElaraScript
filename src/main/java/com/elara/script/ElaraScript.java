package com.elara.script;

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
    public static final class Value {
        public enum Type { NUMBER, BOOL, STRING, FUNC, BYTES, ARRAY, MATRIX, MAP, CLASS, CLASS_INSTANCE, NULL }

        private final Type type;
        private final Object value;

        private Value(Type type, Object value) {
            this.type = type;
            this.value = value;
        }

        public static Value func(String s) { return new Value(Type.FUNC, s); }
        public static Value number(double d) { return new Value(Type.NUMBER, d); }
        public static Value bool(boolean b) { return new Value(Type.BOOL, b); }
        public static Value string(String s) { return new Value(Type.STRING, s); }
        public static Value bytes(byte[] b) {
            if (b == null) return new Value(Type.BYTES, null);
            return new Value(Type.BYTES, Arrays.copyOf(b, b.length));
        }
        public static Value array(List<Value> a) { return new Value(Type.ARRAY, a); }
        public static Value matrix(List<List<Value>> m) { return new Value(Type.MATRIX, m); }
        public static Value map(Map<String, Value> m) {
            if (m == null) return new Value(Type.MAP, null);
            return new Value(Type.MAP, new LinkedHashMap<>(m));
        }
        public static Value clazz(ClassDescriptor c) { return new Value(Type.CLASS, c); }

        /** Stateless class descriptor (no instance state yet). */
        public static final class ClassDescriptor {
            public final String name;
            public final LinkedHashMap<String, Object> methods; // value: Interpreter.UserFunction later

            public ClassDescriptor(String name, LinkedHashMap<String, Object> methods) {
                this.name = name;
                this.methods = (methods == null) ? new LinkedHashMap<>() : methods;
            }
        }
        
        public static final class ClassInstance {
            public final String className;
            public final String uuid;

            public ClassInstance(String className, String uuid) {
                this.className = className;
                this.uuid = uuid;
            }

            public String stateKey() {
                return className + "." + uuid;
            }
        }

        public static Value nil() { return new Value(Type.NULL, null); }

        public Type getType() { return type; }
        
        public String asFunc() {
            if (type != Type.FUNC) throw new RuntimeException("Expected function, got " + type);
            return (String) value;
        }

        public double asNumber() {
            if (type != Type.NUMBER) throw new RuntimeException("Expected number, got " + type);
            return (double) value;
        }

        public boolean asBool() {
            if (type != Type.BOOL) throw new RuntimeException("Expected bool, got " + type);
            return (boolean) value;
        }

        public String asString() {
            if (type != Type.STRING && type != Type.FUNC) {
                throw new RuntimeException("Expected string, got " + type);
            }
            return (String) value;
        }

        public byte[] asBytes() {
            if (type != Type.BYTES) throw new RuntimeException("Expected bytes, got " + type);
            if (value == null) return null;
            return Arrays.copyOf((byte[]) value, ((byte[]) value).length);
        }

        @SuppressWarnings("unchecked")
        public List<Value> asArray() {
            if (type != Type.ARRAY) throw new RuntimeException("Expected array, got " + type);
            return (List<Value>) value;
        }

        @SuppressWarnings("unchecked")
        public List<List<Value>> asMatrix() {
            if (type != Type.MATRIX) throw new RuntimeException("Expected matrix, got " + type);
            return (List<List<Value>>) value;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Value> asMap() {
            if (type != Type.MAP) throw new RuntimeException("Expected map, got " + type);
            return (Map<String, Value>) value;
        }
        
        public ClassInstance asClassInstance() {
            if (type != Type.CLASS_INSTANCE) {
                throw new RuntimeException("Expected class instance, got " + type);
            }
            return (ClassInstance) value;
        }

        @Override
        public String toString() {
            switch (type) {
                case NUMBER:
                    return Double.toString(asNumber());
                case BOOL:
                    return Boolean.toString(asBool());
                case STRING:
                    return '"' + asString() + '"';
                case FUNC:
            		return '"' + asString() + '"';
                case BYTES:
                    byte[] bb = (byte[]) value;
                    return (bb == null) ? "bytes(null)" : ("bytes(" + bb.length + ")");
                case ARRAY:
                    return asArray().toString();
                case MATRIX:
                    return asMatrix().toString();
                case MAP: {
                    Map<String, Value> m = (Map<String, Value>) value;
                    return (m == null) ? "map(null)" : m.toString();
                }
                default:
                    return "null";
            }
        }
    }

    /** Functional interface for built-in functions. */
    public interface BuiltinFunction {
        Value call(List<Value> args);
    }

    // ===================== DATA SHAPING (REGISTRY-BASED) =====================

    /** Bridges ElaraScript.Value <-> ElaraDataShaper without coupling the shaper to interpreter internals. */
    private static final ElaraDataShaper.ValueAdapter<Value> VALUE_ADAPTER = new ElaraDataShaper.ValueAdapter<Value>() {
        @Override
        public ElaraDataShaper.Type typeOf(Value v) {
            if (v == null) return ElaraDataShaper.Type.NULL;
            switch (v.getType()) {
                case NUMBER: return ElaraDataShaper.Type.NUMBER;
                case BOOL:   return ElaraDataShaper.Type.BOOL;
                case STRING: return ElaraDataShaper.Type.STRING;
                case FUNC:   return ElaraDataShaper.Type.STRING;
                case BYTES:  return ElaraDataShaper.Type.BYTES;
                case ARRAY:  return ElaraDataShaper.Type.ARRAY;
                case MATRIX: return ElaraDataShaper.Type.MATRIX;
                case MAP:    return ElaraDataShaper.Type.MAP;
                case NULL:
                default:     return ElaraDataShaper.Type.NULL;
            }
        }

        @Override public double asNumber(Value v) { return v.asNumber(); }
        @Override public boolean asBool(Value v) { return v.asBool(); }
        @Override public String asString(Value v) { return v.asString(); }
        @Override public byte[] asBytes(Value v) { return v.asBytes(); }

        @Override public List<Value> asArray(Value v) { return v.asArray(); }
        @Override public List<List<Value>> asMatrix(Value v) { return v.asMatrix(); }
        @Override public Map<String, Value> asMap(Value v) { return v.asMap(); }

        @Override public Value number(double d) { return Value.number(d); }
        @Override public Value bool(boolean b) { return Value.bool(b); }
        @Override public Value string(String s) { return Value.string(s); }
        @Override public Value bytes(byte[] b) { return Value.bytes(b); }
        @Override public Value array(List<Value> a) { return Value.array(a); }
        @Override public Value matrix(List<List<Value>> m) { return Value.matrix(m); }
        @Override public Value map(Map<String, Value> m) { return Value.map(m); }
        @Override public Value nil() { return Value.nil(); }
    };

    /**
     * Registry of named shapes.
     *
     * This replaces the legacy embedded DataShape/FieldSpec/etc inside ElaraScript.
     * Shapes live outside the interpreter and can be shared across apps.
     */
    public static final class DataShapingRegistry {
        private final Map<String, ElaraDataShaper.Shape<Value>> shapes = new ConcurrentHashMap<>();
        private final ElaraDataShaper<Value> shaper = new ElaraDataShaper<>(VALUE_ADAPTER);

        public void register(String name, ElaraDataShaper.Shape<Value> shape) {
            if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("shape name required");
            if (shape == null) throw new IllegalArgumentException("shape must not be null");
            shapes.put(name.trim(), shape);
        }

        public boolean has(String name) {
            if (name == null) return false;
            return shapes.containsKey(name.trim());
        }

        public ElaraDataShaper.Shape<Value> get(String name) {
            if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("shape name required");
            ElaraDataShaper.Shape<Value> s = shapes.get(name.trim());
            if (s == null) throw new IllegalArgumentException("Unknown shape: " + name);
            return s;
        }

        public ElaraDataShaper<Value> shaper() { return shaper; }
        public Map<String, ElaraDataShaper.Shape<Value>> snapshot() { return Collections.unmodifiableMap(new LinkedHashMap<>(shapes)); }
    }

    /** Simple call frame for stack tracking. */
    private static final class CallFrame {
        final String functionName;
        final List<Value> arguments;

        CallFrame(String functionName, List<Value> arguments) {
            this.functionName = functionName;
            this.arguments = arguments;
        }
    }

    /** Runtime environment (variables + scoping). */
    private static final class Environment {
        private final Map<String, Value> vars = new LinkedHashMap<>();
        private final Environment parent;

        Environment() { this.parent = null; }
        Environment(Environment parent) { this.parent = parent; }
        Environment(Map<String, Value> initial) {
            this.parent = null;
            this.vars.putAll(initial);
        }

        void define(String name, Value value) {
            if (vars.containsKey(name)) {
                throw new RuntimeException("Variable already defined: " + name);
            }
            vars.put(name, value);
        }

        void assign(String name, Value value) {
            if (vars.containsKey(name)) {
                vars.put(name, value);
            } else if (parent != null) {
                parent.assign(name, value);
            } else {
                throw new RuntimeException("Undefined variable: " + name);
            }
        }

        Value get(String name) {
            if (vars.containsKey(name)) {
                return vars.get(name);
            }
            if (parent != null) return parent.get(name);
            throw new RuntimeException("Undefined variable: " + name);
        }

        boolean exists(String name) {
            if (vars.containsKey(name)) return true;
            return parent != null && parent.exists(name);
        }

        Environment childScope() {
            return new Environment(this);
        }

        Map<String, Value> snapshot() {
            Map<String, Value> out = (parent != null) ? parent.snapshot() : new LinkedHashMap<String, Value>();
            out.putAll(vars);
            return out;
        }
    }

    // ===================== LEXER =====================

    private enum TokenType {
        LEFT_PAREN, RIGHT_PAREN,
        LEFT_BRACE, RIGHT_BRACE,
        LEFT_BRACKET, RIGHT_BRACKET,
        COMMA, COLON, DOT, SEMICOLON,
        PLUS, MINUS, STAR, DOUBLE_STAR, SLASH, PERCENT,

        BANG, BANG_EQUAL,
        EQUAL, EQUAL_EQUAL,
        GREATER, GREATER_EQUAL,
        LESS, LESS_EQUAL,
        AND_AND, OR_OR,

        IDENTIFIER, STRING, NUMBER,

        LET, IF, ELSE, WHILE, FOR, TRUE, FALSE, NULL,
        FUNCTION, RETURN, BREAK,
        CLASS, DEF, NEW,

        EOF
    }

    private static final class Token {
        final TokenType type;
        final String lexeme;
        final Object literal;
        final int line;

        Token(TokenType type, String lexeme, Object literal, int line) {
            this.type = type;
            this.lexeme = lexeme;
            this.literal = literal;
            this.line = line;
        }
    }

    private static final class Lexer {
        private final String source;
        private final List<Token> tokens = new ArrayList<>();
        private int start = 0;
        private int current = 0;
        private int line = 1;

        private static final Map<String, TokenType> keywords;
        static {
            Map<String, TokenType> map = new HashMap<>();
            map.put("let", TokenType.LET);
            map.put("if", TokenType.IF);
            map.put("else", TokenType.ELSE);
            map.put("while", TokenType.WHILE);
            map.put("for", TokenType.FOR);
            map.put("true", TokenType.TRUE);
            map.put("false", TokenType.FALSE);
            map.put("null", TokenType.NULL);
            map.put("function", TokenType.FUNCTION);
            map.put("class", TokenType.CLASS);
            map.put("def", TokenType.DEF);
            map.put("new", TokenType.NEW);
            map.put("return", TokenType.RETURN);
            map.put("break", TokenType.BREAK);
            keywords = Collections.unmodifiableMap(map);
        }

        Lexer(String source) {
            this.source = source;
        }

        List<Token> tokenize() {
            while (!isAtEnd()) {
                start = current;
                scanToken();
            }
            tokens.add(new Token(TokenType.EOF, "", null, line));
            return tokens;
        }

        private void scanToken() {
            char c = advance();
            switch (c) {
                case '(': addToken(TokenType.LEFT_PAREN); break;
                case ')': addToken(TokenType.RIGHT_PAREN); break;
                case '{': addToken(TokenType.LEFT_BRACE); break;
                case '}': addToken(TokenType.RIGHT_BRACE); break;
                case '[': addToken(TokenType.LEFT_BRACKET); break;
                case ']': addToken(TokenType.RIGHT_BRACKET); break;
                case ',': addToken(TokenType.COMMA); break;
                case ':': addToken(TokenType.COLON); break;
                case '.': addToken(TokenType.DOT); break;
                case ';': addToken(TokenType.SEMICOLON); break;
                case '+': addToken(TokenType.PLUS); break;
                case '-': addToken(TokenType.MINUS); break;
                case '*':
                    // '**' is reserved for the spread operator in function calls.
                    if (match('*')) addToken(TokenType.DOUBLE_STAR);
                    else addToken(TokenType.STAR);
                    break;

                case '/':
                    if (match('/')) {
                        while (!isAtEnd() && peek() != '\n') advance();
                    } else {
                        addToken(TokenType.SLASH);
                    }
                    break;
                case '%': addToken(TokenType.PERCENT); break;
                case '!': addToken(match('=') ? TokenType.BANG_EQUAL : TokenType.BANG); break;
                case '=': addToken(match('=') ? TokenType.EQUAL_EQUAL : TokenType.EQUAL); break;
                case '<': addToken(match('=') ? TokenType.LESS_EQUAL : TokenType.LESS); break;
                case '>': addToken(match('=') ? TokenType.GREATER_EQUAL : TokenType.GREATER); break;
                case '&':
                    if (match('&')) addToken(TokenType.AND_AND);
                    else throw error("Unexpected '&'");
                    break;
                case '|':
                    if (match('|')) addToken(TokenType.OR_OR);
                    else throw error("Unexpected '|'");
                    break;
                case ' ': case '\r': case '\t':
                    break;
                case '\n':
                    line++;
                    break;
                case '"':
                    string();
                    break;
                default:
                    if (isDigit(c)) number();
                    else if (isAlpha(c)) identifier();
                    else throw error("Unexpected character: " + c);
            }
        }

        private void identifier() {
            while (isAlphaNumeric(peek())) advance();
            String text = source.substring(start, current);
            TokenType type = keywords.getOrDefault(text, TokenType.IDENTIFIER);
            addToken(type);
        }

        private void number() {
            while (isDigit(peek())) advance();
            if (peek() == '.' && isDigit(peekNext())) {
                advance();
                while (isDigit(peek())) advance();
            }
            double value = Double.parseDouble(source.substring(start, current));
            addToken(TokenType.NUMBER, value);
        }

        private void string() {
            while (!isAtEnd() && peek() != '"') {
                if (peek() == '\n') line++;
                advance();
            }
            if (isAtEnd()) throw error("Unterminated string");
            advance();
            String value = source.substring(start + 1, current - 1);
            addToken(TokenType.STRING, value);
        }

        private boolean isAtEnd() { return current >= source.length(); }
        private char advance() { return source.charAt(current++); }

        private boolean match(char expected) {
            if (isAtEnd()) return false;
            if (source.charAt(current) != expected) return false;
            current++;
            return true;
        }

        private char peek() { return isAtEnd() ? '\0' : source.charAt(current); }
        private char peekNext() { return (current + 1 >= source.length()) ? '\0' : source.charAt(current + 1); }

        private boolean isDigit(char c) { return c >= '0' && c <= '9'; }
        private boolean isAlpha(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
        }
        private boolean isAlphaNumeric(char c) {
            // Allow '?' inside identifiers so user-function parameters can carry
            // validation hints like: shape_param??
            return isAlpha(c) || isDigit(c) || c == '?';
        }

        private void addToken(TokenType type) { addToken(type, null); }
        private void addToken(TokenType type, Object literal) {
            String text = source.substring(start, current);
            tokens.add(new Token(type, text, literal, line));
        }

        private RuntimeException error(String msg) {
            return new RuntimeException("[line " + line + "] " + msg);
        }
    }

    // ===================== AST =====================

    private interface Expr {
        <R> R accept(ExprVisitor<R> visitor);
    }

    private interface ExprVisitor<R> {
        R visitBinaryExpr(Binary expr);
        R visitUnaryExpr(Unary expr);
        R visitLiteralExpr(Literal expr);
        R visitMapLiteralExpr(MapLiteral expr);
        R visitVariableExpr(Variable expr);
        R visitAssignExpr(Assign expr);
        R visitLogicalExpr(Logical expr);
        R visitCallExpr(Call expr);
        R visitMethodCallExpr(MethodCallExpr expr);
        R visitNewExpr(NewExpr expr);
        R visitIndexExpr(Index expr);
        R visitSetIndexExpr(SetIndex expr);
    }

    private static final class Binary implements Expr {
        final Expr left;
        final Token operator;
        final Expr right;
        Binary(Expr left, Token operator, Expr right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
        }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitBinaryExpr(this); }
    }

    private static final class Unary implements Expr {
        final Token operator;
        final Expr right;
        Unary(Token operator, Expr right) {
            this.operator = operator;
            this.right = right;
        }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitUnaryExpr(this); }
    }

        private static final class Literal implements Expr {
        final Object value;
        Literal(Object value) { this.value = value; }

        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitLiteralExpr(this); }
    }
    
    private static final class MapLiteral implements Expr {
            final LinkedHashMap<String, Expr> entries; // deterministic order
            MapLiteral(LinkedHashMap<String, Expr> entries) {
                this.entries = entries;
            }
            public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitMapLiteralExpr(this); }
        }
    
    private static final class Variable implements Expr {
        final Token name;
        Variable(Token name) { this.name = name; }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitVariableExpr(this); }
    }

    private static final class Assign implements Expr {
        final Token name;
        final Expr value;
        Assign(Token name, Expr value) { this.name = name; this.value = value; }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitAssignExpr(this); }
    }


    private static final class SetIndex implements Expr {
        final Expr target;
        final Expr index;
        final Expr value;
        final Token bracket;
        SetIndex(Expr target, Expr index, Expr value, Token bracket) {
            this.target = target;
            this.index = index;
            this.value = value;
            this.bracket = bracket;
        }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitSetIndexExpr(this); }
    }

    private static final class Logical implements Expr {
        final Expr left;
        final Token operator;
        final Expr right;
        Logical(Expr left, Token operator, Expr right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
        }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitLogicalExpr(this); }
    }
    
    

    private static final class Call implements Expr {
        final Expr callee;
        final Token paren;
        final List<CallArg> arguments;
        Call(Expr callee, Token paren, List<CallArg> arguments) {
            this.callee = callee;
            this.paren = paren;
            this.arguments = arguments;
        }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitCallExpr(this); }
    }
    
    public static class MethodCallExpr implements Expr {
        public final Expr receiver;      // expression producing CLASS_INSTANCE
        public final Token method;       // identifier token
        public final List<Expr> args;

        public MethodCallExpr(Expr receiver, Token method, List<Expr> args) {
            this.receiver = receiver;
            this.method = method;
            this.args = args;
        }

        @Override
        public Value accept(ExprVisitor visitor) {
            return (Value) visitor.visitMethodCallExpr(this);
        }
    }
    
    public static class NewExpr implements Expr {
        public final Token className;

        public NewExpr(Token className) {
            this.className = className;
        }

        @Override
        public Value accept(ExprVisitor visitor) {
            return (Value) visitor.visitNewExpr(this);
        }
    }


    /**
     * Function-call argument wrapper to support the spread operator:
     *   fn(1, **arr, 4)
     * Where '**expr' must evaluate to an array and is expanded into positional arguments.
     */
    private static final class CallArg {
        final boolean spread;
        final Expr expr;
        final Token spreadToken; // may be null

        CallArg(boolean spread, Expr expr, Token spreadToken) {
            this.spread = spread;
            this.expr = expr;
            this.spreadToken = spreadToken;
        }
    }


    private static final class Index implements Expr {
        final Expr target;
        final Expr index;
        final Token bracket;
        Index(Expr target, Expr index, Token bracket) {
            this.target = target;
            this.index = index;
            this.bracket = bracket;
        }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitIndexExpr(this); }
    }

    private interface Stmt {
        void accept(StmtVisitor visitor);
    }

    private interface StmtVisitor {
        void visitExprStmt(ExprStmt stmt);
        void visitVarStmt(VarStmt stmt);
        void visitBlockStmt(Block stmt);
        void visitIfStmt(If stmt);
        void visitWhileStmt(While stmt);
        void visitFunctionStmt(FunctionStmt stmt);
        void visitClassStmt(ClassStmt stmt);
        void visitReturnStmt(ReturnStmt stmt);
        void visitBreakStmt(BreakStmt stmt);
    }

    private static final class ExprStmt implements Stmt {
        final Expr expression;
        ExprStmt(Expr expression) { this.expression = expression; }
        public void accept(StmtVisitor visitor) { visitor.visitExprStmt(this); }
    }

    private static final class VarStmt implements Stmt {
        final Token name;
        final Expr initializer;
        VarStmt(Token name, Expr initializer) { this.name = name; this.initializer = initializer; }
        public void accept(StmtVisitor visitor) { visitor.visitVarStmt(this); }
    }

    private static final class Block implements Stmt {
        final List<Stmt> statements;
        Block(List<Stmt> statements) { this.statements = statements; }
        public void accept(StmtVisitor visitor) { visitor.visitBlockStmt(this); }
    }

    private static final class If implements Stmt {
        final Expr condition;
        final Stmt thenBranch;
        final Stmt elseBranch;
        If(Expr condition, Stmt thenBranch, Stmt elseBranch) {
            this.condition = condition;
            this.thenBranch = thenBranch;
            this.elseBranch = elseBranch;
        }
        public void accept(StmtVisitor visitor) { visitor.visitIfStmt(this); }
    }

    private static final class While implements Stmt {
        final Expr condition;
        final Stmt body;
        While(Expr condition, Stmt body) {
            this.condition = condition;
            this.body = body;
        }
        public void accept(StmtVisitor visitor) { visitor.visitWhileStmt(this); }
    }

    private static final class FunctionStmt implements Stmt {
        final Token name;
        final List<Token> params;
        final List<Stmt> body;

        FunctionStmt(Token name, List<Token> params, List<Stmt> body) {
            this.name = name;
            this.params = params;
            this.body = body;
        }

    public void accept(StmtVisitor visitor) { visitor.visitFunctionStmt(this); }
    
    }
    
    private static final class ClassStmt implements Stmt {
        final Token name;
        final List<FunctionStmt> methods;

        ClassStmt(Token name, List<FunctionStmt> methods) {
            this.name = name;
            this.methods = methods;
        }

        public void accept(StmtVisitor visitor) { visitor.visitClassStmt(this); }
    }


    private static final class ReturnStmt implements Stmt {
        final Token keyword;
        final Expr value; // may be null

        ReturnStmt(Token keyword, Expr value) {
            this.keyword = keyword;
            this.value = value;
        }

        public void accept(StmtVisitor visitor) { visitor.visitReturnStmt(this); }
    }

    private static final class BreakStmt implements Stmt {
        final Token keyword;
        BreakStmt(Token keyword) { this.keyword = keyword; }
        public void accept(StmtVisitor visitor) { visitor.visitBreakStmt(this); }
    }

    // ===================== PARSER =====================

    private static final class Parser {
        private final List<Token> tokens;
        private int current = 0;
        private int loopDepth = 0;

        Parser(List<Token> tokens) { this.tokens = tokens; }

        List<Stmt> parse() {
            List<Stmt> statements = new ArrayList<Stmt>();
            while (!isAtEnd()) {
                statements.add(declaration());
            }
            return statements;
        }

        private Stmt declaration() {
            if (match(TokenType.CLASS)) return classDeclaration();
            if (match(TokenType.FUNCTION)) return functionDeclaration();
            if (match(TokenType.LET)) return varDeclaration();
            return statement();
        }

        private Stmt functionDeclaration() {
            Token name = consume(TokenType.IDENTIFIER, "Expect function name.");
            consume(TokenType.LEFT_PAREN, "Expect '(' after function name.");

            List<Token> params = new ArrayList<>();
            if (!check(TokenType.RIGHT_PAREN)) {
                do {
                    if (params.size() >= 64) {
                        throw error(peek(), "Too many parameters (max 64).");
                    }
                    params.add(consume(TokenType.IDENTIFIER, "Expect parameter name."));
                } while (match(TokenType.COMMA));
            }

            consume(TokenType.RIGHT_PAREN, "Expect ')' after parameters.");
            consume(TokenType.LEFT_BRACE, "Expect '{' before function body.");

            List<Stmt> body = block();
            return new FunctionStmt(name, params, body);
        }

        private Stmt classDeclaration() {
            Token name = consume(TokenType.IDENTIFIER, "Expect class name.");
            consume(TokenType.LEFT_BRACE, "Expect '{' after class name.");

            List<FunctionStmt> methods = new ArrayList<>();

            while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
                if (match(TokenType.DEF)) {
                    // IMPORTANT: add the parsed method to the list
                    methods.add(defFunction("method"));
                } else {
                    throw error(peek(), "Only 'def' declarations are allowed inside a class body.");
                }
            }

            consume(TokenType.RIGHT_BRACE, "Expect '}' after class body.");

            // If your ClassStmt.name is String:
            return new ClassStmt(name, methods);
        }

        /** Parses a class method declaration after having consumed 'def'. */
        private FunctionStmt defDeclaration(String className) {
            Token methodName = consume(TokenType.IDENTIFIER, "Expect method name.");
            consume(TokenType.LEFT_PAREN, "Expect '(' after method name.");

            List<Token> params = new ArrayList<>();
            if (!check(TokenType.RIGHT_PAREN)) {
                do {
                    if (params.size() >= 64) {
                        throw error(peek(), "Too many parameters (max 64).");
                    }
                    params.add(consume(TokenType.IDENTIFIER, "Expect parameter name."));
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RIGHT_PAREN, "Expect ')' after parameters.");

            consume(TokenType.LEFT_BRACE, "Expect '{' before method body.");
            List<Stmt> body = block();

            // Store as qualified name for later method dispatch rules: MyClass.myMethod
            Token qualified = new Token(TokenType.IDENTIFIER, className + "." + methodName.lexeme, null, methodName.line);
            return new FunctionStmt(qualified, params, body);
        }
        
        private FunctionStmt defFunction(String kind) {
            Token name = consume(TokenType.IDENTIFIER, "Expect " + kind + " name.");
            consume(TokenType.LEFT_PAREN, "Expect '(' after " + kind + " name.");

            List<Token> parameters = new ArrayList<>();
            if (!check(TokenType.RIGHT_PAREN)) {
                do {
                    parameters.add(consume(TokenType.IDENTIFIER, "Expect parameter name."));
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RIGHT_PAREN, "Expect ')' after parameters.");

            consume(TokenType.LEFT_BRACE, "Expect '{' before " + kind + " body.");
            List<Stmt> body = block(); // your existing block() that reads until RIGHT_BRACE

            // Match your FunctionStmt constructor (String vs Token name)
            return new FunctionStmt(name, parameters, body);
        }

        private Stmt varDeclaration() {
            Token name = consume(TokenType.IDENTIFIER, "Expect variable name.");
            consume(TokenType.EQUAL, "Expect '=' after variable name.");
            Expr initializer = expression();
            consume(TokenType.SEMICOLON, "Expect ';' after variable declaration.");
            return new VarStmt(name, initializer);
        }

        private Stmt statement() {
            if (match(TokenType.IF)) return ifStatement();
            if (match(TokenType.WHILE)) return whileStatement();
            if (match(TokenType.FOR)) return forStatement();
            if (match(TokenType.RETURN)) return returnStatement();
            if (match(TokenType.BREAK)) return breakStatement();
            if (match(TokenType.LEFT_BRACE)) return new Block(block());
            return exprStatement();
        }

        private Stmt breakStatement() {
            Token keyword = previous();
            if (loopDepth <= 0) {
                throw error(keyword, "'break' used outside of a loop.");
            }
            consume(TokenType.SEMICOLON, "Expect ';' after 'break'.");
            return new BreakStmt(keyword);
        }

        private Stmt returnStatement() {
            Token keyword = previous();
            Expr value = null;
            if (!check(TokenType.SEMICOLON)) {
                value = expression();
            }
            consume(TokenType.SEMICOLON, "Expect ';' after return value.");
            return new ReturnStmt(keyword, value);
        }

        private Stmt ifStatement() {
            consume(TokenType.LEFT_PAREN, "Expect '(' after 'if'.");
            Expr condition = expression();
            consume(TokenType.RIGHT_PAREN, "Expect ')' after if condition.");
            Stmt thenBranch = statement();
            Stmt elseBranch = null;
            if (match(TokenType.ELSE)) elseBranch = statement();
            return new If(condition, thenBranch, elseBranch);
        }

        private Stmt whileStatement() {
            consume(TokenType.LEFT_PAREN, "Expect '(' after 'while'.");
            Expr condition = expression();
            consume(TokenType.RIGHT_PAREN, "Expect ')' after condition.");

            loopDepth++;
            try {
                Stmt body = statement();
                return new While(condition, body);
            } finally {
                loopDepth--;
            }
        }

        // for (init; cond; inc) body
        // => { init; while (cond) { body; inc; } }
        private Stmt forStatement() {
            consume(TokenType.LEFT_PAREN, "Expect '(' after 'for'.");

            // initializer
            Stmt initializer = null;
            if (match(TokenType.SEMICOLON)) {
                initializer = null;
            } else if (match(TokenType.LET)) {
                initializer = varDeclaration(); // consumes first ';'
            } else {
                initializer = exprStatement();  // consumes first ';'
            }

            // condition
            Expr condition = null;
            if (!check(TokenType.SEMICOLON)) {
                condition = expression();
            }
            consume(TokenType.SEMICOLON, "Expect ';' after loop condition.");

            // increment
            Expr increment = null;
            if (!check(TokenType.RIGHT_PAREN)) {
                increment = expression();
            }
            consume(TokenType.RIGHT_PAREN, "Expect ')' after for clauses.");

            loopDepth++;
            Stmt body;
            try {
                body = statement();
            } finally {
                loopDepth--;
            }

            if (increment != null) {
                List<Stmt> list = new ArrayList<>();
                list.add(body);
                list.add(new ExprStmt(increment));
                body = new Block(list);
            }

            if (condition == null) condition = new Literal(Boolean.TRUE);
            body = new While(condition, body);

            if (initializer != null) {
                List<Stmt> list = new ArrayList<>();
                list.add(initializer);
                list.add(body);
                body = new Block(list);
            }

            return body;
        }

        private List<Stmt> block() {
            List<Stmt> statements = new ArrayList<Stmt>();
            while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
                statements.add(declaration());
            }
            consume(TokenType.RIGHT_BRACE, "Expect '}' after block.");
            return statements;
        }

        private Stmt exprStatement() {
            Expr expr = expression();
            consume(TokenType.SEMICOLON, "Expect ';' after expression.");
            return new ExprStmt(expr);
        }

        private Expr expression() { return assignment(); }

        private Expr assignment() {
            Expr expr = or();
            if (match(TokenType.EQUAL)) {
                Token equals = previous();
                Expr value = assignment();
                if (expr instanceof Variable) {
                    Token name = ((Variable) expr).name;
                    return new Assign(name, value);
                }
                if (expr instanceof Index) {
                    Index ix = (Index) expr;
                    return new SetIndex(ix.target, ix.index, value, ix.bracket);
                }
                throw error(equals, "Invalid assignment target.");
            }
            return expr;
        }

        private Expr or() {
            Expr expr = and();
            while (match(TokenType.OR_OR)) {
                Token op = previous();
                Expr right = and();
                expr = new Logical(expr, op, right);
            }
            return expr;
        }

        private Expr and() {
            Expr expr = equality();
            while (match(TokenType.AND_AND)) {
                Token op = previous();
                Expr right = equality();
                expr = new Logical(expr, op, right);
            }
            return expr;
        }

        private Expr equality() {
            Expr expr = comparison();
            while (match(TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL)) {
                Token op = previous();
                Expr right = comparison();
                expr = new Binary(expr, op, right);
            }
            return expr;
        }

        private Expr comparison() {
            Expr expr = term();
            while (match(TokenType.GREATER, TokenType.GREATER_EQUAL, TokenType.LESS, TokenType.LESS_EQUAL)) {
                Token op = previous();
                Expr right = term();
                expr = new Binary(expr, op, right);
            }
            return expr;
        }

        private Expr term() {
            Expr expr = factor();
            while (match(TokenType.PLUS, TokenType.MINUS)) {
                Token op = previous();
                Expr right = factor();
                expr = new Binary(expr, op, right);
            }
            return expr;
        }

        private Expr factor() {
            Expr expr = unary();
            while (match(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
                Token op = previous();
                Expr right = unary();
                expr = new Binary(expr, op, right);
            }
            return expr;
        }

        private Expr unary() {
            if (match(TokenType.BANG, TokenType.MINUS)) {
                Token op = previous();
                Expr right = unary();
                return new Unary(op, right);
            }
            return call();
        }

        private Expr call() {
            Expr expr = primary();

            while (true) {
                if (match(TokenType.LEFT_PAREN)) {
                    // existing normal function call parse...
                    expr = finishCall(expr);
                } else if (match(TokenType.DOT)) {
                    Token method = consume(TokenType.IDENTIFIER, "Expect method name after '.'.");
                    consume(TokenType.LEFT_PAREN, "Expect '(' after method name.");
                    List<Expr> args = new ArrayList<>();
                    if (!check(TokenType.RIGHT_PAREN)) {
                        do {
                            args.add(expression());
                        } while (match(TokenType.COMMA));
                    }
                    consume(TokenType.RIGHT_PAREN, "Expect ')' after arguments.");
                    expr = new MethodCallExpr(expr, method, args);
                } else {
                    break;
                }
            }

            return expr;
        }

        private Expr finishCall(Expr callee) {
            List<CallArg> arguments = new ArrayList<CallArg>();
            if (!check(TokenType.RIGHT_PAREN)) {
                do {
                    boolean spread = false;
                    Token spreadTok = null;
                    if (match(TokenType.DOUBLE_STAR)) {
                        spread = true;
                        spreadTok = previous();
                    }
                    Expr argExpr = expression();
                    arguments.add(new CallArg(spread, argExpr, spreadTok));
                } while (match(TokenType.COMMA));
            }
            Token paren = consume(TokenType.RIGHT_PAREN, "Expect ')' after arguments.");
            return new Call(callee, paren, arguments);
        }

        private Expr primary() {
            if (match(TokenType.FALSE)) return new Literal(Boolean.FALSE);
            if (match(TokenType.TRUE)) return new Literal(Boolean.TRUE);
            if (match(TokenType.NULL)) return new Literal(null);
            if (match(TokenType.NUMBER)) return new Literal(previous().literal);
            if (match(TokenType.STRING)) return new Literal(previous().literal);
            if (match(TokenType.IDENTIFIER)) return new Variable(previous());

            if (match(TokenType.LEFT_PAREN)) {
                Expr expr = expression();
                consume(TokenType.RIGHT_PAREN, "Expect ')' after expression.");
                return expr;
            }

            if (match(TokenType.LEFT_BRACKET)) {
                List<Expr> items = new ArrayList<Expr>();
                if (!check(TokenType.RIGHT_BRACKET)) {
                    do {
                        items.add(expression());
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RIGHT_BRACKET, "Expect ']' after array literal.");
                return new Literal(items);
            }

            
            // Map literal (JSON-style object)
            if (match(TokenType.LEFT_BRACE)) {
                java.util.LinkedHashMap<String, Expr> entries = new java.util.LinkedHashMap<>();
                if (!check(TokenType.RIGHT_BRACE)) {
                    do {
                        String key;
                        if (match(TokenType.STRING)) {
                            key = (String) previous().literal;
                        } else if (match(TokenType.IDENTIFIER)) {
                            key = previous().lexeme;
                        } else {
                            throw error(peek(), "Expect map key (string or identifier).");
                        }
                        consume(TokenType.COLON, "Expect ':' after map key.");
                        Expr val = expression();
                        entries.put(key, val);
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RIGHT_BRACE, "Expect '}' after map literal.");
                return new MapLiteral(entries);
            }
            
            if (match(TokenType.NEW)) {
                Token name = consume(TokenType.IDENTIFIER, "Expect class name after 'new'.");
                consume(TokenType.LEFT_PAREN, "Expect '(' after class name.");
                consume(TokenType.RIGHT_PAREN, "Expect ')' after new expression.");
                return new NewExpr(name);
            }


            throw error(peek(), "Expect expression.");
        }

        private boolean match(TokenType... types) {
            for (TokenType type : types) {
                if (check(type)) {
                    advance();
                    return true;
                }
            }
            return false;
        }

        private Token consume(TokenType type, String message) {
            if (check(type)) return advance();
            throw error(peek(), message);
        }

        private boolean check(TokenType type) {
            if (isAtEnd()) return false;
            return peek().type == type;
        }

        private Token advance() {
            if (!isAtEnd()) current++;
            return previous();
        }

        private boolean isAtEnd() { return peek().type == TokenType.EOF; }
        private Token peek() { return tokens.get(current); }
        private Token previous() { return tokens.get(current - 1); }

        private RuntimeException error(Token token, String message) {
            return new RuntimeException("[line " + token.line + "] " + message);
        }
    }

    // ===================== INTERPRETER =====================

    /** Error reporter hook used by the interpreter to surface system errors to the host. */
    private interface SystemErrorReporter {
        void report(RuntimeException e, Interpreter interpreter, String kind, String name, Token token, String message);
    }



    private static final class Interpreter implements ExprVisitor<Value>, StmtVisitor {
        private Environment env;
        private final Map<String, BuiltinFunction> functions;
        private final DataShapingRegistry shapingRegistry;
        private final Map<String, UserFunction> userFunctions = new LinkedHashMap<>();
        private final Map<String, Value.ClassDescriptor> classes = new HashMap<>();
        private final Deque<CallFrame> callStack = new ArrayDeque<CallFrame>();
        private final int maxDepth;
        private final Mode mode;
        private final SystemErrorReporter errorReporter;

        Interpreter(Environment env, Map<String, BuiltinFunction> functions, DataShapingRegistry shapingRegistry, int maxDepth, Mode mode, SystemErrorReporter errorReporter) {
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
        private Value maybeValidateUserArg(String fnName, String paramLexeme, Value arg) {
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


        String currentFunctionName() {
            return callStack.isEmpty() ? null : callStack.peek().functionName;
        }

        void reportSystemError(String kind, String name, Token token, String message) {
            if (errorReporter != null) {
                errorReporter.report(new RuntimeException(kind+" : "+name+" : "+ token+" : "+ message), this, kind, name, token, message);
            }
        }

        Value invokeForHost(String targetName, List<Value> args) {
            return invokeByName(targetName, args);
        }

        void execute(List<Stmt> program) {
            for (Stmt stmt : program) stmt.accept(this);
        }

        public void visitExprStmt(ExprStmt stmt) { eval(stmt.expression); }

        public void visitVarStmt(VarStmt stmt) {
            Value value = eval(stmt.initializer);
            env.define(stmt.name.lexeme, value);
        }

        public void visitBlockStmt(Block stmt) {
            Environment previous = this.env;
            this.env = env.childScope();
            try {
                for (Stmt s : stmt.statements) s.accept(this);
            } finally {
                this.env = previous;
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

            Value.ClassDescriptor desc = new Value.ClassDescriptor(className, methods);

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
                List<Expr> exprs = (List<Expr>) expr.value;
                List<Value> values = new ArrayList<Value>(exprs.size());
                for (Expr e : exprs) values.add(eval(e));
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

        public Value visitSetIndexExpr(SetIndex expr) {
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

        public Value visitCallExpr(Call expr) {
            if (!(expr.callee instanceof Variable)) throw new RuntimeException("Invalid function call target");
            String name = ((Variable) expr.callee).name.lexeme;

            // Evaluate + expand args (spread operator: '**arrayExpr').
            List<Value> args = new ArrayList<Value>();
            for (CallArg a : expr.arguments) {
                Value v = eval(a.expr);
                if (!a.spread) {
                    args.add(v);
                    continue;
                }
                if (v.getType() != Value.Type.ARRAY) {
                    String where = (a.spreadToken != null) ? (" at line " + a.spreadToken.line) : "";
                    throw new RuntimeException("Spread operator expects an array" + where);
                }
                args.addAll(v.asArray());
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
                Value out = g.vars.get(v.asString());           // root lookup only
                return (out == null) ? Value.nil() : out;
            }

            // setglobal("varName", value) -> value
            if ("setglobal".equals(name)) {
                if (args.size() != 2) throw new RuntimeException("setglobal() expects 2 arguments");
                Value k = args.get(0);
                Value val = args.get(1);
                if (k.getType() != Value.Type.STRING) throw new RuntimeException("setglobal() first arg must be string");

                Environment g = env;
                while (g.parent != null) g = g.parent;          // walk to root env
                g.vars.put(k.asString(), val);                  // overwrite/create at root
                return val;
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
                    return invokeByName(targetName, rest);
                } finally {
                    callStack.pop();
                }
            }

            // Prefer user-defined function over builtins (JS-like shadowing).
            UserFunction uf = userFunctions.get(name);
            if (uf != null) {
                callStack.push(new CallFrame(name, args));
                try {
                    return uf.call(this, args);
                } finally {
                    callStack.pop();
                }
            }

            BuiltinFunction fn = functions.get(name);
            if (fn != null) {
                callStack.push(new CallFrame(name, args));
                try {
                    return fn.call(args);
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
                        return invokeByName(targetName, args);
                    } finally {
                        callStack.pop();
                    }
                }
            }            reportSystemError("fn_not_found", name, expr.paren, "Unknown function: " + name);
            throw new RuntimeException("Unknown function: " + name);
        }
        
        @Override
        public Value visitMethodCallExpr(MethodCallExpr expr) {
            Value recv = eval(expr.receiver);
            if (recv.getType() != Value.Type.CLASS_INSTANCE) {
                throw new RuntimeException("Only class instances support '.' method calls.");
            }

            Value.ClassInstance inst = recv.asClassInstance();

            Value.ClassDescriptor desc = classes.get(inst.className);
            if (desc == null) {
                throw new RuntimeException("Unknown class: " + inst.className);
            }

            Object fnObj = desc.methods.get(expr.method.lexeme);
            if (!(fnObj instanceof UserFunction)) {
                throw new RuntimeException("Unknown method: " + inst.className + "." + expr.method.lexeme);
            }

            List<Value> args = new ArrayList<>();
            for (Expr a : expr.args) args.add(eval(a));

            return ((UserFunction) fnObj).callWithThis(this, recv, args);
        }

        private Value callMethodWithThis(UserFunction fn, Value thisValue, List<Value> args) {
            return fn.callWithThis(this, thisValue, args);
        }

        private Value invokeByName(String targetName, List<Value> args) {
            UserFunction uf = userFunctions.get(targetName);
            if (uf != null) {
                return uf.call(this, args);
            }
            BuiltinFunction bf = functions.get(targetName);
            if (bf != null) {
                return bf.call(args);
            }            reportSystemError("fn_not_found", targetName, null, "Unknown function: " + targetName);
            throw new RuntimeException("Unknown function: " + targetName);
        }

        public Value visitIndexExpr(Index expr) {
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

        private Value eval(Expr expr) { return expr.accept(this); }

        private boolean isTruthy(Value v) {
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
        
        private Value evalNew(String className) {
            Value.ClassDescriptor desc = classes.get(className);
            if (desc == null) throw new RuntimeException("Unknown class: " + className);

            String uuid = java.util.UUID.randomUUID().toString();
            String key = className + "." + uuid;

            // Create instance state map (use your existing MAP representation)
            LinkedHashMap<String, Value> state = new LinkedHashMap<>();
            env.define(key, Value.map(state)); // <-- adapt to your actual MAP constructor

            // Return lightweight instance handle
            Value.ClassInstance inst = new Value.ClassInstance(className, uuid);
            return new Value(Value.Type.CLASS_INSTANCE, inst);
        }

        private boolean isEqual(Value a, Value b) {
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

        private void requireNumber(Value a, Value b, Token op) {
            if (a.getType() != Value.Type.NUMBER || b.getType() != Value.Type.NUMBER) {
                throw new RuntimeException("Operator '" + op.lexeme + "' expects numbers");
            }
        }

        private String stringify(Value v) {
            if (v.getType() == Value.Type.NULL) return "null";
            switch (v.getType()) {
                case STRING: return v.asString();
                case FUNC:   return v.asString();
                default: return v.toString();
            }
        }

        private static final class ReturnSignal extends RuntimeException {
            private static final long serialVersionUID = 1L;
			final Value value;
            ReturnSignal(Value value) { super(null, null, false, false); this.value = value; }
        }

        private static final class BreakSignal extends RuntimeException {
            private static final long serialVersionUID = 1L;

			BreakSignal() { super(null, null, false, false); }
        }

        private static final class UserFunction {
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

            Value call(Interpreter interpreter, List<Value> args) {
                if (args.size() != params.size()) {
                    throw new RuntimeException(name + "() expects " + params.size() + " arguments, got " + args.size());
                }

                Environment previous = interpreter.env;
                interpreter.env = new Environment(closure);
                try {
                	for (int i = 0; i < params.size(); i++) {
                	    String pRaw = params.get(i).lexeme;     // e.g. "user_payload??"
                	    Value v = args.get(i);

                	    // Optional, name-driven validation/coercion hook (must see ?? marker).
                	    v = interpreter.maybeValidateUserArg(name, pRaw, v);

                	    // Bind variable name WITHOUT the trailing "??" (avoid visual confusion)
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
            
            Value callWithThis(Interpreter interpreter, Value thisValue, List<Value> args) {
                if (args.size() != params.size()) {
                    throw new RuntimeException(name + "() expects " + params.size() + " arguments, got " + args.size());
                }

                Environment previous = interpreter.env;
                interpreter.env = new Environment(closure);
                try {
                    // Inject `this` FIRST
                    interpreter.env.define("this", thisValue);

                    // Bind parameters (same logic as call())
                    for (int i = 0; i < params.size(); i++) {
                        String pRaw = params.get(i).lexeme;     // e.g. "user_payload??"
                        Value v = args.get(i);

                        v = interpreter.maybeValidateUserArg(name, pRaw, v);

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

        }

		@Override
		public Value visitMapLiteralExpr(MapLiteral expr) {
			LinkedHashMap<String, Value> out = new LinkedHashMap<>();
            if (expr != null && expr.entries != null) {
                for (Map.Entry<String, Expr> e : expr.entries.entrySet()) {
                    out.put(e.getKey(), eval(e.getValue()));
                }
            }
            return Value.map(out);
		}

		@Override
		public Value visitNewExpr(NewExpr expr) {
		    return evalNew(expr.className.lexeme);
		}

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
        Environment env = (initialEnv == null) ? new Environment() : new Environment(initialEnv);
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

    public static final class EntryRunResult {
        private final Map<String, Value> env;
        private final Value value;

        private EntryRunResult(Map<String, Value> env, Value value) {
            this.env = env;
            this.value = value;
        }

        public Map<String, Value> env() { return env; }
        public Value value() { return value; }
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

        Environment env = (initialEnv == null) ? new Environment() : new Environment(initialEnv);
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