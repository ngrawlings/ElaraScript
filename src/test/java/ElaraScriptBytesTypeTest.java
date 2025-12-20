import com.elara.script.ElaraScript;
import com.elara.script.plugins.ElaraBinaryCodecPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static com.elara.script.ElaraScript.Value;

public class ElaraScriptBytesTypeTest {

    @Test
    void valueBytes_isDefensivelyCopied() {
        byte[] src = new byte[] { 1, 2, (byte) 255 };

        Value v = Value.bytes(src);

        // Mutate original array after creation -> must NOT affect stored bytes
        src[0] = 9;
        byte[] a1 = v.asBytes();
        assertArrayEquals(new byte[] { 1, 2, (byte) 255 }, a1);

        // Mutate returned array -> must NOT affect stored bytes
        a1[1] = 7;
        byte[] a2 = v.asBytes();
        assertArrayEquals(new byte[] { 1, 2, (byte) 255 }, a2);
    }

    @Test
    void bytes_len_and_indexing_work_in_script() {
        ElaraScript es = new ElaraScript();

        String src = """
            function main(b) {
                // len(bytes) should be byte length
                let n = len(b);
                // indexing should return NUMBER 0..255
                let x = b[2];
                return n * 1000 + x;
            }
            """;

        Value b = Value.bytes(new byte[] { 10, 20, 30, (byte) 255 });

        Value out = es.run(src, "main", List.of(b));
        assertEquals(Value.Type.NUMBER, out.getType());
        assertEquals(4030.0, out.asNumber(), 0.0); // len=4, b[2]=30
    }

    @Test
    void binaryCodecPlugin_roundTrips_nativeBytes() {
        ElaraScript es = new ElaraScript();
        ElaraBinaryCodecPlugin.register(es, null);

        String src = """
            function main(fmt, blob) {
                let pairs = [[ "blob", blob ]];

                let enc = bin_encode_bytes(fmt, pairs);   // -> BYTES
                let dec = bin_decode_bytes(fmt, enc);     // -> envPairs

                // dec[0] = ["blob", <BYTES>]
                // Return last byte as NUMBER
                return dec[0][1][3];
            }
            """;

        String fmtJson = "{\"fields\":[{\"name\":\"blob\",\"type\":\"bytes:4\"}]}";

        Value fmt = Value.string(fmtJson);
        Value blob = Value.bytes(new byte[] { 1, 2, 3, 4 });

        Value out = es.run(src, "main", List.of(fmt, blob));
        assertEquals(Value.Type.NUMBER, out.getType());
        assertEquals(4.0, out.asNumber(), 0.0);
    }
}
