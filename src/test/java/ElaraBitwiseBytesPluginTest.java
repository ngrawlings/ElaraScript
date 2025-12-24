import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;
import com.elara.script.plugins.ElaraBitwiseBytesPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraBitwiseBytesPluginTest {

    @Test
    void and_or_xor_not_work() {
        ElaraScript es = new ElaraScript();
        ElaraBitwiseBytesPlugin.register(es);

        String src = String.join("\n",
            "function main(a, b) {",
            "    let x = bw_xor(a, b);   // [0xFF,0xFF]",
            "    let y = bw_and(a, b);   // [0x00,0x00]",
            "    let z = bw_or(a, b);    // [0xFF,0xFF]",
            "    let n = bw_not(y);      // [0xFF,0xFF]",
            "    // return: x[0] + y[0]*10 + z[0]*100 + n[0]*1000",
            "    return x[0] + y[0]*10 + z[0]*100 + n[0]*1000;",
            "}",
            ""
        );

        Value a = Value.bytes(new byte[] { (byte) 0xF0, (byte) 0x0F });
        Value b = Value.bytes(new byte[] { (byte) 0x0F, (byte) 0xF0 });

        Value out = es.run(src, "main", List.of(a, b));
        assertEquals(Value.Type.NUMBER, out.getType());

        // x[0]=0xFF=255, y[0]=0, z[0]=255, n[0]=255
        double expected = 255 + 0 * 10 + 255 * 100 + 255 * 1000;
        assertEquals(expected, out.asNumber(), 0.0);
    }

    @Test
    void shiftLeft_bigEndian_bits() {
        ElaraScript es = new ElaraScript();
        ElaraBitwiseBytesPlugin.register(es);

        String src = String.join("\n",
            "function main(b) {",
            "    // 0x12 0x34 << 4 (BE) == 0x23 0x40",
            "    let s = bw_shl(b, 4, \"BE\");",
            "    return s[0] * 1000 + s[1];",
            "}",
            ""
        );

        Value b = Value.bytes(new byte[] { 0x12, 0x34 });

        Value out = es.run(src, "main", List.of(b));
        assertEquals(0x23 * 1000 + 0x40, (int) out.asNumber());
    }

    @Test
    void shiftRight_bigEndian_bits() {
        ElaraScript es = new ElaraScript();
        ElaraBitwiseBytesPlugin.register(es);

        String src = String.join("\n",
            "function main(b) {",
            "    // 0x12 0x34 >> 4 (BE) == 0x01 0x23",
            "    let s = bw_shr(b, 4, \"BE\");",
            "    return s[0] * 1000 + s[1];",
            "}",
            ""
        );

        Value b = Value.bytes(new byte[] { 0x12, 0x34 });

        Value out = es.run(src, "main", List.of(b));
        assertEquals(0x01 * 1000 + 0x23, (int) out.asNumber());
    }

    @Test
    void rotateLeft_and_right_bigEndian_bits() {
        ElaraScript es = new ElaraScript();
        ElaraBitwiseBytesPlugin.register(es);

        String src = String.join("\n",
            "function main(b) {",
            "    // ROL by 4: 0x12 0x34 -> 0x23 0x41",
            "    let rl = bw_rol(b, 4, \"BE\");",
            "    // ROR by 4: 0x12 0x34 -> 0x41 0x23",
            "    let rr = bw_ror(b, 4, \"BE\");",
            "    // return rl[0]*1e6 + rl[1]*1e3 + rr[0]*1 + rr[1]*0 (pack)",
            "    return rl[0] * 1000000 + rl[1] * 1000 + rr[0];",
            "}",
            ""
        );

        Value b = Value.bytes(new byte[] { 0x12, 0x34 });

        Value out = es.run(src, "main", List.of(b));
        int got = (int) out.asNumber();

        int rl0 = 0x23, rl1 = 0x41, rr0 = 0x41;
        int expected = rl0 * 1_000_000 + rl1 * 1000 + rr0;
        assertEquals(expected, got);
    }

    @Test
    void mismatched_lengths_throw() {
        ElaraScript es = new ElaraScript();
        ElaraBitwiseBytesPlugin.register(es);

        String src = String.join("\n",
            "function main(a, b) {",
            "    let x = bw_xor(a, b);",
            "    return len(x);",
            "}",
            ""
        );

        Value a = Value.bytes(new byte[] { 1, 2 });
        Value b = Value.bytes(new byte[] { 3 });

        assertThrows(RuntimeException.class, () -> es.run(src, "main", List.of(a, b)));
    }
}
