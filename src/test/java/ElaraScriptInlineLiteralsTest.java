import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptInlineLiteralsTest {

    // --- helpers ---

    private static Value evalExpr(String expr) {
        ElaraScript es = new ElaraScript();
        String src = ""
                + "function main() { \n"
                + "  return " + expr + ";\n"
                + "}\n";
        return es.run(src, "main", List.of());
    }

    private static Map<String, Value> runGlobals(String src) {
        ElaraScript es = new ElaraScript();
        return es.run(src);
    }

    // --- ARRAY LITERALS ---

    @Test
    public void arrayLiteral_empty() {
        Value v = evalExpr("[]");
        assertEquals(Value.Type.ARRAY, v.getType());
        assertEquals(0, v.asArray().size());
    }

    @Test
    public void arrayLiteral_numbersAndExpressions() {
        Value v = evalExpr("[1, 2 + 3, 4 * 2]");
        assertEquals(Value.Type.ARRAY, v.getType());
        List<Value> a = v.asArray();
        assertEquals(3, a.size());
        assertEquals(1.0, a.get(0).asNumber(), 0.0);
        assertEquals(5.0, a.get(1).asNumber(), 0.0);
        assertEquals(8.0, a.get(2).asNumber(), 0.0);
    }

    @Test
    public void arrayLiteral_nestedArrays() {
        Value v = evalExpr("[[1,2],[3,[4]]]");
        List<Value> outer = v.asArray();
        assertEquals(2, outer.size());

        List<Value> a0 = outer.get(0).asArray();
        assertEquals(2, a0.size());
        assertEquals(1.0, a0.get(0).asNumber(), 0.0);
        assertEquals(2.0, a0.get(1).asNumber(), 0.0);

        List<Value> a1 = outer.get(1).asArray();
        assertEquals(2, a1.size());
        assertEquals(3.0, a1.get(0).asNumber(), 0.0);

        List<Value> a1_1 = a1.get(1).asArray();
        assertEquals(1, a1_1.size());
        assertEquals(4.0, a1_1.get(0).asNumber(), 0.0);
    }

    // --- MAP LITERALS ---

    @Test
    public void mapLiteral_empty() {
        Value v = evalExpr("{}");
        assertEquals(Value.Type.MAP, v.getType());
        assertEquals(0, v.asMap().size());
    }

    @Test
    public void mapLiteral_identifierKeysAndStringKeys() {
        Value v = evalExpr("{a: 1, b: 2, \"c\": 3}");
        assertEquals(Value.Type.MAP, v.getType());
        Map<String, Value> m = v.asMap();
        assertEquals(3, m.size());
        assertEquals(1.0, m.get("a").asNumber(), 0.0);
        assertEquals(2.0, m.get("b").asNumber(), 0.0);
        assertEquals(3.0, m.get("c").asNumber(), 0.0);
    }

    @Test
    public void mapLiteral_valuesAreExpressionsAndNestedLiterals() {
        Value v = evalExpr("{x: 1 + 2, y: [3, 4], z: {k: 9}}");
        Map<String, Value> m = v.asMap();

        assertEquals(3.0, m.get("x").asNumber(), 0.0);

        List<Value> y = m.get("y").asArray();
        assertEquals(2, y.size());
        assertEquals(3.0, y.get(0).asNumber(), 0.0);
        assertEquals(4.0, y.get(1).asNumber(), 0.0);

        Map<String, Value> z = m.get("z").asMap();
        assertEquals(9.0, z.get("k").asNumber(), 0.0);
    }

    // --- LITERALS IN VARIABLE INITIALIZERS (common real-world usage) ---

    @Test
    public void let_arrayLiteral_initializer() {
        Map<String, Value> env = runGlobals("let a = [1,2,3];");
        assertTrue(env.containsKey("a"));
        Value a = env.get("a");
        assertEquals(Value.Type.ARRAY, a.getType());
        assertEquals(3, a.asArray().size());
    }

    @Test
    public void let_mapLiteral_initializer() {
        Map<String, Value> env = runGlobals("let m = {a: 1, b: 2};");
        assertTrue(env.containsKey("m"));
        Value m = env.get("m");
        assertEquals(Value.Type.MAP, m.getType());
        assertEquals(2, m.asMap().size());
        assertEquals(1.0, m.asMap().get("a").asNumber(), 0.0);
        assertEquals(2.0, m.asMap().get("b").asNumber(), 0.0);
    }

    // --- LITERALS USED AS FUNCTION ARGUMENTS (inline) ---

    @Test
    public void inlineArrayLiteral_inFunctionCallArgs() {
        ElaraScript es = new ElaraScript();
        es.registerFunction("sum2", args -> {
            // sum2([x,y]) -> x+y
            assertEquals(1, args.size());
            List<Value> a = args.get(0).asArray();
            return Value.number(a.get(0).asNumber() + a.get(1).asNumber());
        });

        String src = ""
                + "function main() { return sum2([10, 32]); }\n";
        Value out = es.run(src, "main", List.of());
        assertEquals(42.0, out.asNumber(), 0.0);
    }

    @Test
    public void inlineMapLiteral_inFunctionCallArgs() {
        ElaraScript es = new ElaraScript();
        es.registerFunction("getA", args -> {
            assertEquals(1, args.size());
            Map<String, Value> m = args.get(0).asMap();
            return m.get("a");
        });

        String src = ""
                + "function main() { return getA({a: 7, b: 9}); }\n";
        Value out = es.run(src, "main", List.of());
        assertEquals(7.0, out.asNumber(), 0.0);
    }

    // --- INDEXING INTO INLINE LITERALS (tightest “inline literal” stress test) ---

    @Test
    public void index_inlineArrayLiteral() {
        Value v = evalExpr("[9,8,7][1]");
        assertEquals(Value.Type.NUMBER, v.getType());
        assertEquals(8.0, v.asNumber(), 0.0);
    }

    @Test
    public void index_inlineMapLiteral_stringKey() {
        Value v = evalExpr("{\"a\": 1, b: 2}[\"b\"]");
        assertEquals(Value.Type.NUMBER, v.getType());
        assertEquals(2.0, v.asNumber(), 0.0);
    }

    // --- STATEMENT-START MAP LITERAL GOTCHA (parsed as a block) ---

    @Test
    public void mapLiteral_asExpressionStatement_requiresParentheses() {
        // Without parentheses, "{a:1};" is parsed as a BLOCK statement, not a map literal.
        // With parentheses, it becomes an expression statement containing a map literal.
        Map<String, Value> env = runGlobals("({a:1}); let ok = 1;");
        assertEquals(1.0, env.get("ok").asNumber(), 0.0);
    }

    @Test
    public void mapLiteral_statementStart_withoutParentheses_shouldFailOrBehaveAsBlock() {
        // This documents the ambiguity. If your parser treats it as a block, it's valid syntax,
        // but it is NOT a map literal expression statement.
        //
        // We'll require that "x" is NOT set by evaluating "{a:1};" as a map expression,
        // because it should be treated as a block with a label-ish construct and likely fail.
        ElaraScript es = new ElaraScript();

        String src = "{a:1};"; // statement-start
        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run(src));
        // message may differ, but we want the failure captured in CI
        assertNotNull(ex.getMessage());
    }
}
