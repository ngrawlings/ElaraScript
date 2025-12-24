import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;
import com.elara.script.plugins.ByteArraysPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraByteArraysPluginTest {

    @Test
    void alloc_and_alloc_fill_work() {
        ElaraScript es = new ElaraScript();
        ByteArraysPlugin.register(es);

        String src = String.join("\n",
            "function main() {",
            "    let a = bytes_alloc(4);          // [0,0,0,0]",
            "    let b = bytes_alloc_fill(3, 7);  // [7,7,7]",
            "    return a[0] * 1000000 + b[0] * 1000 + b[2];",
            "}",
            ""
        );

        Value out = es.run(src, "main", List.of());
        assertEquals(0 * 1_000_000 + 7 * 1000 + 7, (int) out.asNumber());
    }

    @Test
    void concat_and_slice_work() {
        ElaraScript es = new ElaraScript();
        ByteArraysPlugin.register(es);

        String src = String.join("\n",
            "function main(a, b) {",
            "    let c = bytes_concat(a, b);          // [1,2,3,4,5]",
            "    let s = bytes_slice(c, 1, 3);        // [2,3,4]",
            "    return len(c) * 1000000 + s[0]*10000 + s[1]*100 + s[2];",
            "}",
            ""
        );

        Value a = Value.bytes(new byte[] { 1, 2 });
        Value b = Value.bytes(new byte[] { 3, 4, 5 });

        Value out = es.run(src, "main", List.of(a, b));
        assertEquals(Value.Type.NUMBER, out.getType());

        int expected = 5 * 1_000_000 + 2 * 10_000 + 3 * 100 + 4;
        assertEquals(expected, (int) out.asNumber());
    }

    @Test
    void concat_many_works() {
        ElaraScript es = new ElaraScript();
        ByteArraysPlugin.register(es);

        String src = String.join("\n",
            "function main(x, y, z) {",
            "    let c = bytes_concat_many([x, y, z]); // [9,8,7,6]",
            "    return len(c) * 1000 + c[3];",
            "}",
            ""
        );

        Value x = Value.bytes(new byte[] { 9 });
        Value y = Value.bytes(new byte[] { 8, 7 });
        Value z = Value.bytes(new byte[] { 6 });

        Value out = es.run(src, "main", List.of(x, y, z));
        assertEquals(4 * 1000 + 6, (int) out.asNumber());
    }

    @Test
    void slice_clamps_and_supports_negative_start() {
        ElaraScript es = new ElaraScript();
        ByteArraysPlugin.register(es);

        String src = String.join("\n",
            "function main(b) {",
            "    let last2 = bytes_slice(b, -2, 10); // clamp len -> last 2",
            "    return len(last2) * 1000 + last2[0] * 10 + last2[1];",
            "}",
            ""
        );

        Value b = Value.bytes(new byte[] { 1, 2, 3, 4 });

        Value out = es.run(src, "main", List.of(b));
        assertEquals(2 * 1000 + 3 * 10 + 4, (int) out.asNumber());
    }

    @Test
    void pad_left_right_work() {
        ElaraScript es = new ElaraScript();
        ByteArraysPlugin.register(es);

        String src = String.join("\n",
            "function main(b) {",
            "    let r = bytes_pad_right(b, 6, 255); // [1,2,255,255,255,255]",
            "    let l = bytes_pad_left(b, 6, 0);    // [0,0,0,0,1,2]",
            "    return r[5] * 1000000 + l[4] * 1000 + l[5];",
            "}",
            ""
        );

        Value b = Value.bytes(new byte[] { 1, 2 });

        Value out = es.run(src, "main", List.of(b));
        int expected = 255 * 1_000_000 + 1 * 1000 + 2;
        assertEquals(expected, (int) out.asNumber());
    }
}
