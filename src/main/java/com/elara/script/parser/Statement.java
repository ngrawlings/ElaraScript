package com.elara.script.parser;

import java.util.List;

public class Statement {
	
	public interface Stmt {
        void accept(StmtVisitor visitor);
    }
	
    public interface StmtVisitor {
        void visitExprStmt(ExprStmt stmt);
        void visitVarStmt(VarStmt stmt);
        void visitBlockStmt(Block stmt);
        void visitIfStmt(If stmt);
        void visitWhileStmt(While stmt);
        void visitFunctionStmt(FunctionStmt stmt);
        void visitClassStmt(ClassStmt stmt);
        void visitReturnStmt(ReturnStmt stmt);
        void visitBreakStmt(BreakStmt stmt);
        void visitFreeStmt(FreeStmt stmt);
    }

    public static final class FreeStmt implements Stmt {
        final Token keyword;
        final Expr.ExprInterface target;
        FreeStmt(Token keyword, Expr.ExprInterface target) {
            this.keyword = keyword;
            this.target = target;
        }
        public void accept(StmtVisitor visitor) { visitor.visitFreeStmt(this); }
    }

    public static final class ExprStmt implements Stmt {
        final Expr.ExprInterface expression;
        ExprStmt(Expr.ExprInterface expression) { this.expression = expression; }
        public void accept(StmtVisitor visitor) { visitor.visitExprStmt(this); }
    }

    public static final class VarStmt implements Stmt {
        final Token name;
        final Expr.ExprInterface initializer;
        VarStmt(Token name, Expr.ExprInterface initializer) { this.name = name; this.initializer = initializer; }
        public void accept(StmtVisitor visitor) { visitor.visitVarStmt(this); }
    }

    public static final class Block implements Stmt {
        public final List<Stmt> statements;
        Block(List<Stmt> statements) { this.statements = statements; }
        public void accept(StmtVisitor visitor) { visitor.visitBlockStmt(this); }
    }

    public static final class If implements Stmt {
        final Expr.ExprInterface condition;
        public final Stmt thenBranch;
        public final Stmt elseBranch;
        If(Expr.ExprInterface condition, Stmt thenBranch, Stmt elseBranch) {
            this.condition = condition;
            this.thenBranch = thenBranch;
            this.elseBranch = elseBranch;
        }
        public void accept(StmtVisitor visitor) { visitor.visitIfStmt(this); }
    }

    public static final class While implements Stmt {
        final Expr.ExprInterface condition;
        public final Stmt body;
        While(Expr.ExprInterface condition, Stmt body) {
            this.condition = condition;
            this.body = body;
        }
        public void accept(StmtVisitor visitor) { visitor.visitWhileStmt(this); }
    }

    public static final class FunctionStmt implements Stmt {
        public final Token name;
        final List<Token> params;
        final List<Stmt> body;

        FunctionStmt(Token name, List<Token> params, List<Stmt> body) {
            this.name = name;
            this.params = params;
            this.body = body;
        }

    public void accept(StmtVisitor visitor) { visitor.visitFunctionStmt(this); }
    
    }
    
    public static final class ClassStmt implements Stmt {
        final Token name;
        final List<FunctionStmt> methods;
        final List<VarStmt> vars;

        ClassStmt(Token name, List<FunctionStmt> methods, List<VarStmt> vars) {
            this.name = name;
            this.methods = methods;
            this.vars = vars;
        }

        public void accept(StmtVisitor visitor) { visitor.visitClassStmt(this); }
    }


    public static final class ReturnStmt implements Stmt {
        final Token keyword;
        final Expr.ExprInterface value; // may be null

        ReturnStmt(Token keyword, Expr.ExprInterface value) {
            this.keyword = keyword;
            this.value = value;
        }

        public void accept(StmtVisitor visitor) { visitor.visitReturnStmt(this); }
    }

    public static final class BreakStmt implements Stmt {
        final Token keyword;
        BreakStmt(Token keyword) { this.keyword = keyword; }
        public void accept(StmtVisitor visitor) { visitor.visitBreakStmt(this); }
    }
    
}
