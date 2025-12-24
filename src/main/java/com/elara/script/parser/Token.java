package com.elara.script.parser;

public class Token {
    final TokenType type;
    public final String lexeme;
    final Object literal;
    public final int line;

    Token(TokenType type, String lexeme, Object literal, int line) {
        this.type = type;
        this.lexeme = lexeme;
        this.literal = literal;
        this.line = line;
    }
}