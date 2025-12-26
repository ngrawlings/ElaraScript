import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

public class ElaraScriptCodePenerationTester {

	@Test
    public void newPassesArgsToConstructor() {
        ElaraScript es = new ElaraScript();

        String src =
        	    "class MyClass {\n" +
        	    "  def constructor() {\n" +
        	    "    this.x = 1;\n" +
        	    "    this.y = 2;\n" +
        	    "    let x1 = this.x;\n" +
        	    "    let y1 = this.y;\n" +
        	    "  }\n" +
        	    "  def getX() { return this.x; }\n" +
        	    "}\n" +
        	    "function main() {\n" +
        	    "  let a = new MyClass();\n" +
        	    "  let x2 = a.x;\n" +
        	    "  debug_print();\n" +
        	    "  return x2;\n" +
        	    "}\n";

        Value out = es.run(src, "main", Collections.emptyList());
        assertEquals(Value.Type.NUMBER, out.getType());
        assertEquals(1.0, out.asNumber(), 0.0);
	}
	
//	@Test
//    public void testSimpleReturn() {
//        ElaraScript es = new ElaraScript();
//
//        String src =
//        	    "function main() {\n" +
//        	    "  return 123;\n" +   // forces getter path too
//        	    "}\n";
//
//        Value out = es.run(src, "main", Collections.emptyList());
//        assertEquals(Value.Type.NUMBER, out.getType());
//        assertEquals(123.0, out.asNumber(), 0.0);
//	}
}

