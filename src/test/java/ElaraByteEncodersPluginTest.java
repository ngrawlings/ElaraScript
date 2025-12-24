import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;
import com.elara.script.plugins.ByteEncodersPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraByteEncodersPluginTest {

    @Test
    void hex_roundTrip_bytes() {
        ElaraScript es = new ElaraScript();
        ByteEncodersPlugin.register(es);

        String src = String.join("\n",
            "function main(b) {",
            "    let h = hex_encode(b);",
            "    let bb = hex_decode(h);",
            "    // return len*1000 + lastByte (0..255)",
            "    return len(bb) * 1000 + bb[len(bb) - 1];",
            "}",
            ""
        );

        Value b = Value.bytes(new byte[] { 0x00, 0x01, 0x2A, (byte) 0xFF });

        Value out = es.run(src, "main", List.of(b));
        assertEquals(Value.Type.NUMBER, out.getType());
        assertEquals(4 * 1000 + 255, (int) out.asNumber());
    }

    @Test
    void b64_roundTrip_bytes() {
        ElaraScript es = new ElaraScript();
        ByteEncodersPlugin.register(es);

        String src = String.join("\n",
            "function main(b) {",
            "    let s = b64_encode(b);",
            "    let bb = b64_decode(s);",
            "    return len(bb) * 1000 + bb[2];",
            "}",
            ""
        );

        Value b = Value.bytes(new byte[] { 10, 20, 30, 40, 50 });

        Value out = es.run(src, "main", List.of(b));
        assertEquals(Value.Type.NUMBER, out.getType());
        assertEquals(5 * 1000 + 30, (int) out.asNumber());
    }

    @Test
    void b64url_roundTrip_bytes() {
        ElaraScript es = new ElaraScript();
        ByteEncodersPlugin.register(es);

        String src = String.join("\n",
            "function main(b) {",
            "    let s = b64url_encode(b);",
            "    let bb = b64url_decode(s);",
            "    return len(bb) * 1000 + bb[0];",
            "}",
            ""
        );

        Value b = Value.bytes(new byte[] { (byte) 0xFF, 0x00, 0x11, 0x22 });

        Value out = es.run(src, "main", List.of(b));
        assertEquals(Value.Type.NUMBER, out.getType());
        assertEquals(4 * 1000 + 255, (int) out.asNumber());
    }

    @Test
    void hex_decode_rejects_odd_length() {
        ElaraScript es = new ElaraScript();
        ByteEncodersPlugin.register(es);

        String src = String.join("\n",
            "function main() {",
            "    // odd length -> should throw",
            "    let b = hex_decode(\"abc\");",
            "    return len(b);",
            "}",
            ""
        );

        assertThrows(RuntimeException.class, () -> es.run(src, "main", List.of()));
    }

    @Test
    void b64_decode_rejects_invalid() {
        ElaraScript es = new ElaraScript();
        ByteEncodersPlugin.register(es);

        String src = String.join("\n",
            "function main() {",
            "    let b = b64_decode(\"not_base64!!\");",
            "    return len(b);",
            "}",
            ""
        );

        assertThrows(RuntimeException.class, () -> es.run(src, "main", List.of()));
    }
}
