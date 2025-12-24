package com.elara.script.parser;

import java.util.List;

public class CallFrame {
    final String functionName;
    final List<Value> arguments;

    CallFrame(String functionName, List<Value> arguments) {
        this.functionName = functionName;
        this.arguments = arguments;
    }
}