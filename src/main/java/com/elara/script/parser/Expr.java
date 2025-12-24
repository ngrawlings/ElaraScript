package com.elara.script.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class Expr {

    public interface ExprInterface {
        <R> R accept(ExprVisitor<R> visitor);
    }

    public interface ExprVisitor<R> {
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

    public static final class Binary implements ExprInterface {
        final Expr.ExprInterface left;
        final Token operator;
        final Expr.ExprInterface right;
        Binary(Expr.ExprInterface left, Token operator, Expr.ExprInterface right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
        }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitBinaryExpr(this); }
    }

    public static final class Unary implements ExprInterface {
        final Token operator;
        final Expr.ExprInterface right;
        Unary(Token operator, Expr.ExprInterface right) {
            this.operator = operator;
            this.right = right;
        }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitUnaryExpr(this); }
    }

    public static final class Literal implements ExprInterface {
        final Object value;
        Literal(Object value) { this.value = value; }

        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitLiteralExpr(this); }
    }
    
    public static final class MapLiteral implements ExprInterface {
            final LinkedHashMap<String, Expr.ExprInterface> entries; // deterministic order
            MapLiteral(LinkedHashMap<String, Expr.ExprInterface> entries) {
                this.entries = entries;
            }
            public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitMapLiteralExpr(this); }
        }
    
    public static final class Variable implements ExprInterface {
        final Token name;
        Variable(Token name) { this.name = name; }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitVariableExpr(this); }
    }

    public static final class Assign implements ExprInterface {
        final Token name;
        final Expr.ExprInterface value;
        Assign(Token name, Expr.ExprInterface value) { this.name = name; this.value = value; }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitAssignExpr(this); }
    }


    public static final class SetIndex implements ExprInterface {
        final Expr.ExprInterface target;
        final Expr.ExprInterface index;
        final Expr.ExprInterface value;
        final Token bracket;
        SetIndex(Expr.ExprInterface target, Expr.ExprInterface index, Expr.ExprInterface value, Token bracket) {
            this.target = target;
            this.index = index;
            this.value = value;
            this.bracket = bracket;
        }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitSetIndexExpr(this); }
    }

    public static final class Logical implements ExprInterface {
        final Expr.ExprInterface left;
        final Token operator;
        final Expr.ExprInterface right;
        Logical(Expr.ExprInterface left, Token operator, Expr.ExprInterface right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
        }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitLogicalExpr(this); }
    }
    
    

    public static final class Call implements ExprInterface {
        final Expr.ExprInterface callee;
        final Token paren;
        final List<CallArg> arguments;
        Call(Expr.ExprInterface callee, Token paren, List<CallArg> arguments) {
            this.callee = callee;
            this.paren = paren;
            this.arguments = arguments;
        }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitCallExpr(this); }
    }
    
    public static class MethodCallExpr implements ExprInterface {
        public final Expr.ExprInterface receiver;      // expression producing CLASS_INSTANCE
        public final Token method;       // identifier token
        public final List<Expr.ExprInterface> args;

        public MethodCallExpr(Expr.ExprInterface receiver, Token method, List<Expr.ExprInterface> args) {
            this.receiver = receiver;
            this.method = method;
            this.args = args;
        }

        @Override
        public Value accept(ExprVisitor visitor) {
            return (Value) visitor.visitMethodCallExpr(this);
        }
    }
    
    public static class NewExpr implements ExprInterface {
        public final Token className;
        public final List<Expr.ExprInterface> args;

        public NewExpr(Token className, List<Expr.ExprInterface> args) {
            this.className = className;
            this.args = (args == null) ? new ArrayList<>() : args;
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
    public static final class CallArg {
        final boolean spread;
        final Expr.ExprInterface expr;
        final Token spreadToken; // may be null

        CallArg(boolean spread, Expr.ExprInterface expr, Token spreadToken) {
            this.spread = spread;
            this.expr = expr;
            this.spreadToken = spreadToken;
        }
    }


    public static final class Index implements ExprInterface {
        final Expr.ExprInterface target;
        final Expr.ExprInterface index;
        final Token bracket;
        Index(Expr.ExprInterface target, Expr.ExprInterface index, Token bracket) {
            this.target = target;
            this.index = index;
            this.bracket = bracket;
        }
        public <R> R accept(ExprVisitor<R> visitor) { return visitor.visitIndexExpr(this); }
    }

    
	
}
