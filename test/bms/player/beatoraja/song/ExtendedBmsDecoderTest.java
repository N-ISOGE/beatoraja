package bms.player.beatoraja.song;

import bms.model.BMSDecoder;
import bms.model.BMSModel;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** 境界入力を生成し、ユーザー譜面や外部アーカイブを必要としない。
 * Generated boundary inputs require no user charts or external fixture archive. */
public final class ExtendedBmsDecoderTest {
    /** legacy: BOMがなく、従来のデコーダでも譜面として読める入力 / no BOM, so the plain parser reads the same chart. */
    private record Input(String id, byte[] bytes, String title, boolean legacy) { }
    private static Input input(String id, String encoding, String title, byte[] bom, boolean bpmFirst) {
        String header = bpmFirst ? "#BPM 195\n#TITLE " + title + "\n" : "#TITLE " + title + "\n#BPM 195\n";
        byte[] body = (header + "#PLAYER 1\n#WAV01 negzlt.wav\n#00111:01\n").getBytes(Charset.forName(encoding));
        byte[] raw = Arrays.copyOf(bom, bom.length + body.length);
        System.arraycopy(body, 0, raw, bom.length, body.length);
        return new Input(id, raw, title, bom.length == 0);
    }
    public static void main(String[] args) throws Exception {
        Path run = args.length == 1 ? Path.of(args[0]) : Files.createTempDirectory("bms-decoder-boundaries-");
        Files.createDirectories(run);
        byte[] none = {}, utf8 = {(byte)0xef, (byte)0xbb, (byte)0xbf};
        Input ascii = input("ascii", "US-ASCII", "algbin", none, false);
        byte[] invalid = Arrays.copyOf(ascii.bytes, ascii.bytes.length + 1); invalid[invalid.length - 1] = (byte)0xff;
        try {
            StandardCharsets.US_ASCII.newDecoder().decode(ByteBuffer.wrap(invalid));
            throw new AssertionError("Strict ASCII must reject the invalid byte");
        } catch (CharacterCodingException expected) { }
        List<Input> inputs = List.of(ascii,
            input("euc-kr", "EUC-KR", "부하트투", none, false),
            input("ms932", "MS932", "ワよホハ", none, false),
            input("utf8", "UTF-8", "서러부즈", none, false),
            input("utf8-bom-title", "UTF-8", "algbin", utf8, false),
            input("utf8-bom-bpm", "UTF-8", "algbin", utf8, true),
            input("utf8-bom-text", "UTF-8", "서러부즈", utf8, false),
            input("utf16le-bom", "UTF-16LE", "algbin", new byte[]{(byte)0xff, (byte)0xfe}, false),
            input("utf16be-bom", "UTF-16BE", "algbin", new byte[]{(byte)0xfe, (byte)0xff}, false),
            input("utf8-after-64k", "UTF-8", "A".repeat(65536) + "우쿠초시", none, false),
            new Input("invalid-tail-compatible-title", invalid, "algbin", true));
        var decoder = new ExtendedBMSDecoder(BMSModel.LNTYPE_LONGNOTE);
        for (Input input : inputs) {
            Path file = run.resolve(input.id + ".bms"); Files.write(file, input.bytes);
            verify(decoder.decode(file), input);
            BMSModel model = decoder.decode(input.bytes, file.toString(), false, null);
            verify(model, input);
            check(Arrays.equals(Files.readAllBytes(file), input.bytes), input.id + " source file unchanged");
            if (input.legacy) {
                BMSModel plain = new BMSDecoder(BMSModel.LNTYPE_LONGNOTE).decode(input.bytes, file.toString(), false, null);
                check(plain != null && plain.toChartString().equals(model.toChartString()), input.id + " chart content equals the plain parser");
            }
        }
        byte[] legacyText = "#TITLE めるロハ\n".getBytes(Charset.forName("MS932"));
        check(new BMSDecoder(BMSModel.LNTYPE_LONGNOTE).detectEncoding(legacyText).equals(decoder.detectEncoding(legacyText)),
                "legacy text is left to the parser and no charset leaks from the previous request");
        System.out.println("ExtendedBmsDecoderTest passed (" + inputs.size() + " generated inputs, file/byte entry points, BOM/BPM, raw hashes/source, whole-input UTF-8, chart content vs plain parser)");
    }
    private static void verify(BMSModel model, Input input) throws Exception {
        check(model != null && input.title.equals(model.getTitle()) && model.getBpm() == 195.0, input.id + " title/BPM");
        check(digest("MD5", input.bytes).equals(model.getMD5()), input.id + " original MD5");
        check(digest("SHA-256", input.bytes).equals(model.getSHA256()), input.id + " original SHA-256");
        try (var stream = model.getChartInformation().source.openStream()) {
            check(Arrays.equals(stream.readAllBytes(), input.bytes), input.id + " original source reopened");
        }
    }
    private static String digest(String algorithm, byte[] raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(raw));
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
