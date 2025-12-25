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

        // NOTE: you declared these names in the visitor, so Expr must provide matching node types
        R visitIndexExpr(IndexExpr expr);
        R visitSetIndexExpr(SetIndexExpr expr);
        
        R visitGetExpr(GetExpr expr);
        R visitSetExpr(SetExpr expr);
    }

    // -------------------------
    // Core expression nodes
    // -------------------------

    public static final class Binary implements ExprInterface {
        public final ExprInterface left;
        public final Token operator;
        public final ExprInterface right;

        public Binary(ExprInterface left, Token operator, ExprInterface right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
        }

        @Override
        public <R> R accept(ExprVisitor<R> visitor) {
            return visitor.visitBinaryExpr(this);
        }
    }

    public static final class Unary implements ExprInterface {
        public final Token operator;
        public final ExprInterface right;

        public Unary(Token operator, ExprInterface right) {
            this.operator = operator;
            this.right = right;
        }

        @Override
        public <R> R accept(ExprVisitor<R> visitor) {
            return visitor.visitUnaryExpr(this);
        }
    }

    public static final class Literal implements ExprInterface {
        public final Object value;

        public Literal(Object value) {
            this.value = value;
        }

        @Override
        public <R> R accept(ExprVisitor<R> visitor) {
            return visitor.visitLiteralExpr(this);
        }
    }

    public static final class MapLiteral implements ExprInterface {
        public final LinkedHashMap<String, ExprInterface> entries; // deterministic order

        public MapLiteral(LinkedHashMap<String, ExprInterface> entries) {
            this.entries = entries;
        }

        @Override
        public <R> R accept(ExprVisitor<R> visitor) {
            return visitor.visitMapLiteralExpr(this);
        }
    }

    public static final class Variable implements ExprInterface {
        public final Token name;

        public Variable(Token name) {
            this.name = name;
        }

        @Override
        public <R> R accept(ExprVisitor<R> visitor) {
            return visitor.visitVariableExpr(this);
        }
    }

    public static final class Assign implements ExprInterface {
        public final Token name;
        public final ExprInterface value;

        public Assign(Token name, ExprInterface value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public <R> R accept(ExprVisitor<R> visitor) {
            return visitor.visitAssignExpr(this);
        }
    }

    public static final class Logical implements ExprInterface {
        public final ExprInterface left;
        public final Token operator;
        public final ExprInterface right;

        public Logical(ExprInterface left, Token operator, ExprInterface right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
        }

        @Override
        public <R> R accept(ExprVisitor<R> visitor) {
            return visitor.visitLogicalExpr(this);
        }
    }

    // -------------------------
    // Calls
    // -------------------------

    public static final class Call implements ExprInterface {
        public final ExprInterface callee;
        public final Token paren;
        public final List<CallArg> arguments;

        public Call(ExprInterface callee, Token paren, List<CallArg> arguments) {
            this.callee = callee;
            this.paren = paren;
            this.arguments = arguments;
        }

        @Override
        public <R> R accept(ExprVisitor<R> visitor) {
            return visitor.visitCallExpr(this);
        }
    }

    public static final class MethodCallExpr implements ExprInterface {
        public final ExprInterface receiver;
        public final Token method;
        public final List<CallArg> arguments;

        public MethodCallExpr(ExprInterface receiver, Token method, List<CallArg> arguments) {
            this.receiver = receiver;
            this.method = method;
            this.arguments = arguments;
        }

        @Override
        public <R> R accept(ExprVisitor<R> visitor) {
            return visitor.visitMethodCallExpr(this);
        }
    }

    public static final class NewExpr implements ExprInterface {
        public final Token className;
        public final List<ExprInterface> args;

        public NewExpr(Token className, List<ExprInterface> args) {
            this.className = className;
            this.args = (args == null) ? new ArrayList<>() : args;
        }

        @Override
        public <R> R accept(ExprVisitor<R> visitor) {
            return visitor.visitNewExpr(this);
        }
    }

    /**
     * Function-call argument wrapper to support spread and '&' copy qualifier:
     *   fn(1, **arr, &x, 4)
     */
    public static final class CallArg {
        public final boolean spread;
        public final boolean copy;
        public final ExprInterface expr;
        public final Token spreadToken; // may be null
        public final Token copyToken;   // may be null

        public CallArg(boolean spread, boolean copy, ExprInterface expr, Token spreadToken, Token copyToken) {
            this.spread = spread;
            this.copy = copy;
            this.expr = expr;
            this.spreadToken = spreadToken;
            this.copyToken = copyToken;
        }
    }

    // -------------------------
    // Indexing (you had Index + SetIndex, but visitor wants IndexExpr + SetIndexExpr)
    // Keep your original nodes AND provide the visitor-matching nodes.
    // -------------------------

    public static final class Index implements ExprInterface {
        public final ExprInterface target;
        public final ExprInterface index;
        public final Token bracket;

        public Index(ExprInterface target, ExprInterface index, Token bracket) {
            this.target = target;
            this.index = index;
            this.bracket = bracket;
        }

        @Override
        public <R> R accept(ExprVisitor<R> visitor) {
            // If you still have code expecting Index, you can route it through IndexExpr visitor path
            return visitor.visitIndexExpr(new IndexExpr(this.target, this.index, this.bracket));
        }
    }

    public static final class SetIndex implements ExprInterface {
        public final ExprInterface target;
        public final ExprInterface index;
        public final ExprInterface value;
        public final Token bracket;

        public SetIndex(ExprInterface target, ExprInterface index, ExprInterface value, Token bracket) {
            this.target = target;
            this.index = index;
            this.value = value;
            this.bracket = bracket;
        }

        @Override
        public <R> R accept(ExprVisitor<R> visitor) {
            // Route through the visitor’s declared node type
            return visitor.visitSetIndexExpr(new SetIndexExpr(this.target, this.index, this.value, this.bracket));
        }
    }

    // Visitor-declared node names (thin wrappers)
    public static final class IndexExpr implements ExprInterface {
        public final ExprInterface target;
        public final ExprInterface index;
        public final Token bracket;

        public IndexExpr(ExprInterface target, ExprInterface index, Token bracket) {
            this.target = target;
            this.index = index;
            this.bracket = bracket;
        }

        @Override
        public <R> R accept(ExprVisitor<R> visitor) {
            return visitor.visitIndexExpr(this);
        }
    }

    public static final class SetIndexExpr implements ExprInterface {
        public final ExprInterface target;
        public final ExprInterface index;
        public final ExprInterface value;
        public final Token bracket;

        public SetIndexExpr(ExprInterface target, ExprInterface index, ExprInterface value, Token bracket) {
            this.target = target;
            this.index = index;
            this.value = value;
            this.bracket = bracket;
        }

        @Override
        public <R> R accept(ExprVisitor<R> visitor) {
            return visitor.visitSetIndexExpr(this);
        }
    }
    

	public static final class GetExpr implements ExprInterface {
	    public final ExprInterface receiver;
	    public final Token name;
	
	    public GetExpr(ExprInterface receiver, Token name) {
	        this.receiver = receiver;
	        this.name = name;
	    }
	
	    @Override
	    public <R> R accept(ExprVisitor<R> visitor) {
	        return visitor.visitGetExpr(this);
	    }
	}
	
	public static final class SetExpr implements ExprInterface {
	    public final ExprInterface receiver;
	    public final Token name;
	    public final ExprInterface value;
	
	    public SetExpr(ExprInterface receiver, Token name, ExprInterface value) {
	        this.receiver = receiver;
	        this.name = name;
	        this.value = value;
	    }
	
	    @Override
	    public <R> R accept(ExprVisitor<R> visitor) {
	        return visitor.visitSetExpr(this);
	    }
	}
}
