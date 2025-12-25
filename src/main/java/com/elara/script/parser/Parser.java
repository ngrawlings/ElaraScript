package com.elara.script.parser;

import java.util.ArrayList;
import java.util.List;

import com.elara.script.parser.Expr.Assign;
import com.elara.script.parser.Expr.Binary;
import com.elara.script.parser.Expr.CallArg;
import com.elara.script.parser.Expr.Index;
import com.elara.script.parser.Expr.Literal;
import com.elara.script.parser.Expr.Binary;
import com.elara.script.parser.Expr.Variable;
import com.elara.script.parser.Expr.Logical;
import com.elara.script.parser.Expr.MapLiteral;
import com.elara.script.parser.Expr.MethodCallExpr;
import com.elara.script.parser.Expr.NewExpr;
import com.elara.script.parser.Expr.SetIndex;
import com.elara.script.parser.Expr.Unary;
import com.elara.script.parser.Statement.Block;
import com.elara.script.parser.Statement.ExprStmt;
import com.elara.script.parser.Statement.FunctionStmt;
import com.elara.script.parser.Statement.Stmt;
import com.elara.script.parser.Statement.While;

public class Parser {
    private final List<Token> tokens;
    private int current = 0;
    private int loopDepth = 0;

    public Parser(List<Token> tokens) { this.tokens = tokens; }

    public List<Stmt> parse() {
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

    private Statement.Stmt classDeclaration() {
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
        return new Statement.ClassStmt(name, methods);
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
        Expr.ExprInterface initializer = expression();
        consume(TokenType.SEMICOLON, "Expect ';' after variable declaration.");
        return new Statement.VarStmt(name, initializer);
    }

    private Stmt statement() {
        if (match(TokenType.IF)) return ifStatement();
        if (match(TokenType.WHILE)) return whileStatement();
        if (match(TokenType.FOR)) return forStatement();
        if (match(TokenType.RETURN)) return returnStatement();
        if (match(TokenType.BREAK)) return breakStatement();
        if (match(TokenType.FREE)) return freeStatement();
        if (match(TokenType.LEFT_BRACE)) return new Block(block());
        return exprStatement();
    }

    private Statement.Stmt breakStatement() {
        Token keyword = previous();
        if (loopDepth <= 0) {
            throw error(keyword, "'break' used outside of a loop.");
        }
        consume(TokenType.SEMICOLON, "Expect ';' after 'break'.");
        return new Statement.BreakStmt(keyword);
    }

    private Statement.Stmt returnStatement() {
        Token keyword = previous();
        Expr.ExprInterface value = null;
        if (!check(TokenType.SEMICOLON)) {
            value = expression();
        }
        consume(TokenType.SEMICOLON, "Expect ';' after return value.");
        return new Statement.ReturnStmt(keyword, value);
    }
    
    private Statement.Stmt freeStatement() {
        Token keyword = previous();          // 'free'
        Expr.ExprInterface target = expression();          // allow `free x;` or `free (x);`
        consume(TokenType.SEMICOLON, "Expect ';' after free target.");
        return new Statement.FreeStmt(keyword, target);
    }

    private Statement.Stmt ifStatement() {
        consume(TokenType.LEFT_PAREN, "Expect '(' after 'if'.");
        Expr.ExprInterface condition = expression();
        consume(TokenType.RIGHT_PAREN, "Expect ')' after if condition.");
        Stmt thenBranch = statement();
        Stmt elseBranch = null;
        if (match(TokenType.ELSE)) elseBranch = statement();
        return new Statement.If(condition, thenBranch, elseBranch);
    }

    private Statement.Stmt whileStatement() {
        consume(TokenType.LEFT_PAREN, "Expect '(' after 'while'.");
        Expr.ExprInterface condition = expression();
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
    private Statement.Stmt forStatement() {
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
        Expr.ExprInterface condition = null;
        if (!check(TokenType.SEMICOLON)) {
            condition = expression();
        }
        consume(TokenType.SEMICOLON, "Expect ';' after loop condition.");

        // increment
        Expr.ExprInterface increment = null;
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

    private Statement.Stmt exprStatement() {
        Expr.ExprInterface expr = expression();
        consume(TokenType.SEMICOLON, "Expect ';' after expression.");
        return new ExprStmt(expr);
    }

    private Expr.ExprInterface expression() { return assignment(); }

    private Expr.ExprInterface assignment() {
        Expr.ExprInterface expr = or();
        if (match(TokenType.EQUAL)) {
            Token equals = previous();
            Expr.ExprInterface value = assignment();
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

    private Expr.ExprInterface or() {
        Expr.ExprInterface expr = and();
        while (match(TokenType.OR_OR)) {
            Token op = previous();
            Expr.ExprInterface right = and();
            expr = new Logical(expr, op, right);
        }
        return expr;
    }

    private Expr.ExprInterface and() {
        Expr.ExprInterface expr = equality();
        while (match(TokenType.AND_AND)) {
            Token op = previous();
            Expr.ExprInterface right = equality();
            expr = new Logical(expr, op, right);
        }
        return expr;
    }

    private Expr.ExprInterface equality() {
        Expr.ExprInterface expr = comparison();
        while (match(TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL)) {
            Token op = previous();
            Expr.ExprInterface right = comparison();
            expr = new Binary(expr, op, right);
        }
        return expr;
    }

    private Expr.ExprInterface comparison() {
        Expr.ExprInterface expr = term();
        while (match(TokenType.GREATER, TokenType.GREATER_EQUAL, TokenType.LESS, TokenType.LESS_EQUAL)) {
            Token op = previous();
            Expr.ExprInterface right = term();
            expr = new Binary(expr, op, right);
        }
        return expr;
    }

    private Expr.ExprInterface term() {
        Expr.ExprInterface expr = factor();
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            Token op = previous();
            Expr.ExprInterface right = factor();
            expr = new Binary(expr, op, right);
        }
        return expr;
    }

    private Expr.ExprInterface factor() {
        Expr.ExprInterface expr = unary();
        while (match(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
            Token op = previous();
            Expr.ExprInterface right = unary();
            expr = new Binary(expr, op, right);
        }
        return expr;
    }

    private Expr.ExprInterface unary() {
        if (match(TokenType.BANG, TokenType.MINUS)) {
            Token op = previous();
            Expr.ExprInterface right = unary();
            return new Unary(op, right);
        }
        return call();
    }

    private Expr.ExprInterface call() {
        Expr.ExprInterface expr = primary();

        while (true) {
            if (match(TokenType.LEFT_PAREN)) {
                expr = finishCall(expr);
            } else if (match(TokenType.LEFT_BRACKET)) {
                Token bracket = previous();
                Expr.ExprInterface index = expression();
                consume(TokenType.RIGHT_BRACKET, "Expect ']' after index.");
                expr = new Index(expr, index, bracket);
            } else if (match(TokenType.DOT)) {
                Token method = consume(TokenType.IDENTIFIER, "Expect method name after '.'.");
                consume(TokenType.LEFT_PAREN, "Expect '(' after method name.");

                List<CallArg> args = new ArrayList<>();
                if (!check(TokenType.RIGHT_PAREN)) {
                    do {
                        args.add(parseCallArg());
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

    private Expr.ExprInterface finishCall(Expr.ExprInterface callee) {
        List<CallArg> arguments = new ArrayList<>();

        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                arguments.add(parseCallArg());
            } while (match(TokenType.COMMA));
        }

        Token paren = consume(TokenType.RIGHT_PAREN, "Expect ')' after arguments.");
        return new Expr.Call(callee, paren, arguments);
    }
    
    private CallArg parseCallArg() {
        boolean spread = false;
        Token spreadTok = null;
        if (match(TokenType.DOUBLE_STAR)) {
            spread = true;
            spreadTok = previous();
        }

        boolean copy = false;
        Token copyTok = null;

        // prefix &
        if (match(TokenType.AMP)) {
            copy = true;
            copyTok = previous();
        }

        Expr.ExprInterface argExpr = expression();

        // suffix & (optional support)
        if (!copy && match(TokenType.AMP)) {
            copy = true;
            copyTok = previous();
        }

        return new CallArg(spread, copy, argExpr, spreadTok, copyTok);
    }

    private Expr.ExprInterface primary() {
        if (match(TokenType.FALSE)) return new Literal(Boolean.FALSE);
        if (match(TokenType.TRUE)) return new Literal(Boolean.TRUE);
        if (match(TokenType.NULL)) return new Literal(null);
        if (match(TokenType.NUMBER)) return new Literal(previous().literal);
        if (match(TokenType.STRING)) return new Literal(previous().literal);
        if (match(TokenType.IDENTIFIER)) return new Variable(previous());

        if (match(TokenType.LEFT_PAREN)) {
            Expr.ExprInterface expr = expression();
            consume(TokenType.RIGHT_PAREN, "Expect ')' after expression.");
            return expr;
        }

        if (match(TokenType.LEFT_BRACKET)) {
            List<Expr.ExprInterface> items = new ArrayList<Expr.ExprInterface>();
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
            java.util.LinkedHashMap<String, Expr.ExprInterface> entries = new java.util.LinkedHashMap<>();
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
                    Expr.ExprInterface val = expression();
                    entries.put(key, val);
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RIGHT_BRACE, "Expect '}' after map literal.");
            return new MapLiteral(entries);
        }
        
        if (match(TokenType.NEW)) {
            Token name = consume(TokenType.IDENTIFIER, "Expect class name after 'new'.");
            consume(TokenType.LEFT_PAREN, "Expect '(' after class name.");

            List<Expr.ExprInterface> args = new ArrayList<>();
            if (!check(TokenType.RIGHT_PAREN)) {
                do {
                    args.add(expression());
                } while (match(TokenType.COMMA));
            }

            consume(TokenType.RIGHT_PAREN, "Expect ')' after arguments.");
            return new NewExpr(name, args);
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