package bms.player.beatoraja.skin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Decodes skin definition text consistently. スキン定義を共通の規則で読み取る。 */
public final class SkinTextReader {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Charset MS932 = Charset.forName("windows-31j");
    private static final Charset MS949 = Charset.forName("x-windows-949");

    private SkinTextReader() { }

    public static BufferedReader open(Path path) throws IOException {
        return new BufferedReader(new StringReader(readText(path)));
    }

    static String readText(Path path) throws IOException {
        return decode(SkinPathPolicy.read(path, MAX_BYTES));
    }

    static String decode(byte[] bytes) throws CharacterCodingException {
        if (startsWith(bytes, 0xef, 0xbb, 0xbf)) return decode(bytes, 3, StandardCharsets.UTF_8);
        if (startsWith(bytes, 0xff, 0xfe)) return decode(bytes, 2, StandardCharsets.UTF_16LE);
        if (startsWith(bytes, 0xfe, 0xff)) return decode(bytes, 2, StandardCharsets.UTF_16BE);
        try {
            return decode(bytes, 0, StandardCharsets.UTF_8);
        } catch (CharacterCodingException invalidUtf8) {
            try {
                return decode(bytes, 0, MS932);
            } catch (CharacterCodingException invalidMs932) {
                return decode(bytes, 0, MS949);
            }
        }
    }

    private static String decode(byte[] bytes, int offset, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xff) != prefix[i]) return false;
        }
        return true;
    }
}
