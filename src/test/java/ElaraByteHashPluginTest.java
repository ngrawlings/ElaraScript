import com.elara.script.ElaraScript;
import com.elara.script.plugins.ByteHashPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.elara.script.ElaraScript.Value;
import static org.junit.jupiter.api.Assertions.*;

public class ElaraByteHashPluginTest {

    @Test
    void sha256_hex_string_vector() {
        ElaraScript es = new ElaraScript();
        ByteHashPlugin.register(es);

        String src = """
            function main() {
                return sha256_hex("abc");
            }
            """;

        Value out = es.run(src, "main", List.of());
        assertEquals(Value.Type.STRING, out.getType());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", out.asString());
    }

    @Test
    void md5_hex_string_vector() {
        ElaraScript es = new ElaraScript();
        ByteHashPlugin.register(es);

        String src = """
            function main() {
                return md5_hex("abc");
            }
            """;

        Value out = es.run(src, "main", List.of());
        assertEquals(Value.Type.STRING, out.getType());
        assertEquals("900150983cd24fb0d6963f7d28e17f72", out.asString());
    }

    @Test
    void sha256_bytes_accepts_nativeBytes_and_returns_bytes() {
        ElaraScript es = new ElaraScript();
        ByteHashPlugin.register(es);

        String src = """
            function main(b) {
                let d = sha256_bytes(b);
                // return len(d) * 1000 + d[0] (0..255) to prove BYTES behavior
                return len(d) * 1000 + d[0];
            }
            """;

        Value b = Value.bytes(new byte[] { 'a', 'b', 'c' });
        Value out = es.run(src, "main", List.of(b));

        // SHA-256 length is 32 bytes; first byte is 0xBA = 186
        assertEquals(32 * 1000 + 186, (int) out.asNumber());
    }

    @Test
    void generic_hash_hex_accepts_algorithm_names() {
        ElaraScript es = new ElaraScript();
        ByteHashPlugin.register(es);

        String src = """
            function main() {
                return hash_hex("SHA-256", "abc");
            }
            """;

        Value out = es.run(src, "main", List.of());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", out.asString());
    }
}
