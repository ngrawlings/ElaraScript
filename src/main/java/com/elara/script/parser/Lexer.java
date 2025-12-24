package com.elara.script.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Lexer {
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
        map.put("free", TokenType.FREE);
        map.put("return", TokenType.RETURN);
        map.put("break", TokenType.BREAK);
        keywords = Collections.unmodifiableMap(map);
    }

    public Lexer(String source) {
        this.source = source;
    }

    public List<Token> tokenize() {
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