package bms.player.beatoraja.song;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import bms.model.*;

/**
 * 拡張BMSデコーダ：#CHARSETディレクティブ対応および文字コード自動判別（UTF-8、CP949/MS949、Shift-JIS/MS932）。
 * Extended BMS decoder: #CHARSET directive support and encoding auto-detection (UTF-8, CP949/MS949, Shift-JIS/MS932).
 */
public class ExtendedBMSDecoder extends BMSDecoder {

	private static final Logger LOGGER = Logger.getGlobal();

	private static final Pattern CHARSET_PATTERN = Pattern.compile(
			"(?im)^[ \\t]*#[ \\t]*CHARSET[ \\t:]+[\"']?([a-zA-Z0-9_\\-]+)[\"']?");

	private static final ThreadLocal<CharsetEncoder> EUC_KR_ENCODER =
			ThreadLocal.withInitial(() -> Charset.forName("EUC-KR").newEncoder());

	public ExtendedBMSDecoder() {
		super();
	}

	public ExtendedBMSDecoder(int lntype) {
		super(lntype);
	}

	/**
	 * 譜面情報から最適なChartDecoderを返します。
	 * Returns the appropriate ChartDecoder for the given ChartInformation.
	 */
	public static ChartDecoder getDecoder(ChartInformation info) {
		if (info == null) {
			return null;
		}
		String location = info.source != null ? info.source.location() : (info.path != null ? info.path.toString() : null);
		return getDecoder(location, info.lntype);
	}

	/**
	 * 譜面パスとLNモードから最適なChartDecoderを返します。
	 * Returns the appropriate ChartDecoder for the given path and LN mode.
	 */
	public static ChartDecoder getDecoder(Path path, int lntype) {
		return getDecoder(path != null ? path.toString() : null, lntype);
	}

	/**
	 * 譜面ソースとLNモードから最適なChartDecoderを返します。
	 * Returns the appropriate ChartDecoder for the given ChartSource and LN mode.
	 */
	public static ChartDecoder getDecoder(ChartSource source, int lntype) {
		return getDecoder(source != null ? source.location() : null, lntype);
	}

	/**
	 * ロケーション文字列とLNモードから最適なChartDecoderを返します。
	 * Returns the appropriate ChartDecoder for the given location string and LN mode.
	 */
	public static ChartDecoder getDecoder(String location, int lntype) {
		if (location == null) {
			return null;
		}
		String lower = location.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".bmson")) {
			return new BMSONDecoder(lntype);
		}
		if (lower.endsWith(".bms") || lower.endsWith(".bme") || lower.endsWith(".bml") || lower.endsWith(".pms")) {
			return new ExtendedBMSDecoder(lntype);
		}
		return ChartDecoder.getDecoder(location);
	}

	// 準備済みバイトを受け取ったパーサーは文字セットを再照会する。
	// The bundled parser asks for the charset again after receiving the prepared bytes.
	private String preparedCharset;

	@Override
	public BMSModel decode(ChartInformation info) {
		String previousCharset = preparedCharset;
		ChartSource original = info.source;
		byte[][] originalWithBom = new byte[1][];
		ChartSource prepared = new ChartSource() {
			@Override
			public String location() {
				return original.location();
			}

			@Override
			public InputStream openStream() throws IOException {
				try (InputStream stream = original.openStream()) {
					byte[] raw = stream.readAllBytes();
					byte[] input = prepareInput(raw);
					if (input != raw) {
						originalWithBom[0] = raw;
					}
					return new ByteArrayInputStream(input);
				}
			}
		};
		try {
			BMSModel model = super.decode(new ChartInformation(prepared, info.lntype, info.selectedRandoms));
			restoreOriginalSource(model, original, originalWithBom[0]);
			return model;
		} finally {
			preparedCharset = previousCharset;
		}
	}

	@Override
	public BMSModel decode(byte[] data, String location, boolean ispms, int[] random) {
		if (data == null) {
			return super.decode(data, location, ispms, random);
		}
		String previousCharset = preparedCharset;
		try {
			byte[] input = prepareInput(data);
			BMSModel model = super.decode(input, location, ispms, random);
			if (input != data) {
				restoreOriginalSource(model, new ByteArrayChartSource(location, data), data);
			}
			return model;
		} finally {
			preparedCharset = previousCharset;
		}
	}

	private byte[] prepareInput(byte[] raw) {
		preparedCharset = null;
		preparedCharset = detectEncoding(raw);
		int bomLength = getBomLength(raw, raw.length);
		return bomLength == 0 ? raw : Arrays.copyOfRange(raw, bomLength, raw.length);
	}

	private static void restoreOriginalSource(BMSModel model, ChartSource original, byte[] originalWithBom) {
		if (model == null) {
			return;
		}
		ChartInformation parsed = model.getChartInformation();
		model.setChartInformation(new ChartInformation(original, parsed.lntype, parsed.selectedRandoms));
		if (originalWithBom != null) {
			try {
				model.setMD5(convertHexString(MessageDigest.getInstance("MD5").digest(originalWithBom)));
				model.setSHA256(convertHexString(MessageDigest.getInstance("SHA-256").digest(originalWithBom)));
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private static int getBomLength(byte[] data, int length) {
		if (length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
			return 3;
		}
		if (length >= 4 && (data[0] & 0xFF) == 0x00 && (data[1] & 0xFF) == 0x00 && (data[2] & 0xFF) == 0xFE && (data[3] & 0xFF) == 0xFF) {
			return 4;
		}
		if (length >= 4 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE && (data[2] & 0xFF) == 0x00 && (data[3] & 0xFF) == 0x00) {
			return 4;
		}
		if (length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
			return 2;
		}
		if (length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
			return 2;
		}
		return 0;
	}

	@Override
	public String detectEncoding(byte[] data) {
		return detectEncoding(data, data != null ? data.length : 0);
	}

	@Override
	public String encodingFromGarbled(byte[] data) {
		return detectEncoding(data);
	}

	@Override
	public String encodingFromGarbled(byte[] data, int length) {
		return detectEncoding(data, length);
	}

	/**
	 * BMSファイルデータから文字エンコーディングを自動判別します。
	 * Detects character encoding from raw BMS file data.
	 */
	@Override
	public String detectEncoding(byte[] data, int length) {
		if (preparedCharset != null) {
			return preparedCharset;
		}
		if (data == null || length <= 0) {
			return "MS932";
		}
		int effectiveLength = Math.min(data.length, length);

		// 1. BOMを判別する / Detect the BOM
		String bomEncoding = detectBOM(data, effectiveLength);
		if (bomEncoding != null) {
			return bomEncoding;
		}

		String bomlessUtf16 = detectBomlessUtf16(data, effectiveLength);
		if (bomlessUtf16 != null) {
			return bomlessUtf16;
		}

		// 2. #CHARSET ディレクティブの検出 (#CHARSET directive detection)
		String explicitCharset = detectExplicitCharset(data, effectiveLength);
		if (explicitCharset != null) {
			return explicitCharset;
		}

		// 3. 厳密なUTF-8デコード検証 (Strict UTF-8 validation)
		String utf8Result = testUtf8(data, effectiveLength);
		if (utf8Result != null) {
			return utf8Result;
		}

		// 4. 従来のEUC-KR往復変換を優先する。曖昧なMS949/MS932判別は推定のまま。
		// Prefer the legacy EUC-KR round trip; ambiguous MS949/MS932 data remains heuristic.
		if (checkRoundTrip(data, effectiveLength, "EUC-KR")) {
			return "MS949";
		}

		// 5. 韓国語(MS949/CP949) vs 日本語(MS932/Shift-JIS) ヒューリスティック判定
		// Korean (MS949/CP949) vs Japanese (MS932/Shift-JIS) heuristic evaluation
		return scoreMs949VsMs932(data, effectiveLength);
	}

	/** ASCII BMSヘッダーの整列と妥当なUTF-16本文を必要とする。
	 * Requires an aligned ASCII BMS header and a well-formed UTF-16 payload. */
	private static String detectBomlessUtf16(byte[] data, int length) {
		if (length < 8 || (length & 1) != 0) {
			return null;
		}
		boolean littleEndian = data[0] == '#' && data[1] == 0;
		if (!littleEndian && !(data[0] == 0 && data[1] == '#')) {
			return null;
		}
		int asciiOffset = littleEndian ? 0 : 1;
		for (int i = 2; i < 8; i += 2) {
			int c = data[i + asciiOffset];
			if (data[i + 1 - asciiOffset] != 0 || !(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z')) {
				return null;
			}
		}
		Charset charset = littleEndian ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE;
		try {
			charset.newDecoder().decode(ByteBuffer.wrap(data, 0, length));
			return charset.name();
		} catch (CharacterCodingException e) {
			return null;
		}
	}

	private static String detectBOM(byte[] data, int length) {
		if (length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
			return "UTF-8";
		}
		if (length >= 4 && (data[0] & 0xFF) == 0x00 && (data[1] & 0xFF) == 0x00 && (data[2] & 0xFF) == 0xFE && (data[3] & 0xFF) == 0xFF) {
			return "UTF-32BE";
		}
		if (length >= 4 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE && (data[2] & 0xFF) == 0x00 && (data[3] & 0xFF) == 0x00) {
			return "UTF-32LE";
		}
		if (length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
			return "UTF-16BE";
		}
		if (length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
			return "UTF-16LE";
		}
		return null;
	}

	private static String detectExplicitCharset(byte[] data, int length) {
		int scanLen = Math.min(length, 16384);
		String asciiPrefix = new String(data, 0, scanLen, StandardCharsets.ISO_8859_1);
		Matcher matcher = CHARSET_PATTERN.matcher(asciiPrefix);
		if (matcher.find()) {
			String raw = matcher.group(1).trim();
			String normalized = normalizeCharsetName(raw);
			if (normalized != null) {
				LOGGER.fine("Explicit BMS #CHARSET detected: " + normalized + " (raw: " + raw + ")");
				return normalized;
			}
		}
		return null;
	}

	private static String normalizeCharsetName(String name) {
		if (name == null || name.isEmpty()) {
			return null;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.equals("utf-8") || lower.equals("utf8")) {
			return "UTF-8";
		}
		if (lower.equals("shift_jis") || lower.equals("shift-jis") || lower.equals("sjis")
				|| lower.equals("ms932") || lower.equals("windows-31j") || lower.equals("cp932")) {
			return "MS932";
		}
		if (lower.equals("euc-kr") || lower.equals("euckr") || lower.equals("cp949")
				|| lower.equals("ms949") || lower.equals("uhc") || lower.equals("windows-949")
				|| lower.equals("x-windows-949")) {
			return "MS949";
		}
		if (lower.equals("gbk") || lower.equals("gb2312") || lower.equals("gb18030")) {
			return "GB18030";
		}
		if (lower.equals("big5") || lower.equals("cp950")) {
			return "Big5";
		}
		try {
			if (Charset.isSupported(name)) {
				return Charset.forName(name).name();
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	private static String testUtf8(byte[] data, int length) {
		try {
			CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT);
			ByteBuffer bb = ByteBuffer.wrap(data, 0, length);
			CharBuffer cb = decoder.decode(bb);
			boolean hasNonAscii = false;
			for (int i = 0; i < cb.length(); i++) {
				if (cb.charAt(i) > 0x7F) {
					hasNonAscii = true;
					break;
				}
			}
			// 非ASCII文字を含むか、全ASCII文字で正常にUTF-8デコードできた場合はUTF-8と判定
			// If it decodes cleanly with non-ASCII or is pure ASCII, treat as UTF-8
			if (hasNonAscii) {
				return "UTF-8";
			}
		} catch (CharacterCodingException ignored) {
		}
		return null;
	}

	private static String scoreMs949VsMs932(byte[] data, int length) {
		boolean ms932RoundTrip = checkRoundTrip(data, length, "MS932");
		boolean ms949RoundTrip = checkRoundTrip(data, length, "MS949");

		// どちらか一方のみラウンドトリップが成功した場合は成功側を採用
		// If only one encoding round-trips cleanly, adopt the valid one
		if (ms949RoundTrip && !ms932RoundTrip) {
			return "MS949";
		}
		if (ms932RoundTrip && !ms949RoundTrip) {
			return "MS932";
		}
		if (!ms932RoundTrip && !ms949RoundTrip) {
			return "MS932";
		}

		String ms949Text = tryDecode(data, length, "MS949");
		String ms932Text = tryDecode(data, length, "MS932");

		if (ms949Text == null || ms932Text == null) {
			return ms932RoundTrip ? "MS932" : (ms949RoundTrip ? "MS949" : "MS932");
		}

		// 日本語固有マーカー: 平仮名(0x3040~0x309F)または全角片仮名(0x30A0~0x30FF)
		// Japanese unique markers: Hiragana or Full-width Katakana (half-width katakana excluded to avoid CP949 mojibake)
		int japaneseKana = 0;
		int japaneseKanji = 0;
		for (char c : ms932Text.toCharArray()) {
			if ((c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF)) {
				japaneseKana++;
			} else if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0xF900 && c <= 0xFAFF)) {
				japaneseKanji++;
			}
		}

		// 韓国語完成型音節（KS X 1001 標準音節及び CP949 拡張音節）
		// Korean Hangul Syllables (Standard KS X 1001 and CP949 extended)
		int standardHangul = 0;
		int extendedHangul = 0;
		for (char c : ms949Text.toCharArray()) {
			if (c >= 0xAC00 && c <= 0xD7A3) {
				if (isStandardHangul(c)) {
					standardHangul++;
				} else {
					extendedHangul++;
				}
			}
		}
		int totalHangul = standardHangul + extendedHangul;

		// 規則 1: 純粋な漢字曲名（例: 夜想曲）で標準韓国語音節がなく、漢字数が拡張ハングル以上なら日本語 (MS932)
		// Rule 1: Pure Kanji titles (e.g. 夜想曲) where Kanji in SJIS equals/exceeds CP949 extended artifacts -> MS932
		if (japaneseKanji > 0 && standardHangul == 0 && japaneseKana == 0 && japaneseKanji >= extendedHangul) {
			return "MS932";
		}

		// 規則 2: 標準韓国語音節が存在し、偶発的な仮名衝突文字数以上である場合は韓国語 (MS949)
		// Rule 2: Standard Hangul syllables exist and outnumber/match accidental Kana collisions -> MS949
		if (standardHangul > 0 && standardHangul >= japaneseKana) {
			return "MS949";
		}

		// 規則 3: 日本語の固有仮名が存在し、標準韓国語音節が一切ない場合は日本語 (MS932)
		// Rule 3: Genuine Japanese kana exists with no standard Hangul syllables -> MS932
		if (japaneseKana > 0 && standardHangul == 0) {
			return "MS932";
		}

		// 規則 4: 韓国語音節（拡張完成型 똠방각하, CAT걲 포함）が存在し、仮名がない場合は韓国語 (MS949)
		// Rule 4: Hangul syllables exist (including CP949 extended like 똠방각하, CAT걲) with no Kana -> MS949
		if (totalHangul > 0 && japaneseKana == 0) {
			// 標準完成型がなく拡張完成型が2字以上で、MS932解釈の記号/ギリシャ/キリル/漢字が全ての拡張音節を説明でき、
			// KS X 1001 ハンジャ(韓国語の積極的証拠)も無ければ日本語(MS932)。拡張音節1字だけの場合は従来どおり韓国語(MS949)。
			// Rule 4 guard: no standard Hangul, >= 2 extended syllables, all of them explained by Greek/Cyrillic/symbol/kanji in the MS932
			// reading, and no KS X 1001 Hanja in the MS949 reading -> Shift_JIS (e.g. Greek titles whose 0x83xx bytes look like extended Hangul).
			// A single extended syllable (e.g. CAT걲 / 깱) stays MS949; this is a heuristic and byte-identical strings remain inherently ambiguous.
			if (standardHangul == 0 && extendedHangul >= 2 && !hasKoreanHanja(ms949Text)
					&& countSjisNonKanaSymbols(ms932Text) + japaneseKanji >= extendedHangul) {
				return "MS932";
			}
			return "MS949";
		}

		// 規則 5: 日本語文字(仮名+漢字)が韓国語音節を上回る場合は日本語 (MS932)
		// Rule 5: Japanese characters (Kana + Kanji) exceed Korean syllables -> MS932
		if ((japaneseKana + japaneseKanji) >= totalHangul) {
			return "MS932";
		}

		return ms932RoundTrip ? "MS932" : (ms949RoundTrip ? "MS949" : "MS932");
	}

	/** MS932の先行バイト0x81..0x84等から生じ、CP949では拡張ハングルに見える文字を判定する。
	 * Characters produced by MS932 lead bytes such as 0x81..0x84 that CP949 may misread as extended Hangul:
	 * Latin-1 symbols, Greek, Cyrillic, general symbols/punctuation/box drawing, CJK symbols/squared units and full-width forms. */
	private static boolean isSjisNonKanaSymbol(char c) {
		return (c >= 0x00A1 && c <= 0x00FF) || (c >= 0x0391 && c <= 0x03C9) || (c >= 0x0401 && c <= 0x0451)
				|| (c >= 0x2000 && c <= 0x2BFF) || (c >= 0x3000 && c <= 0x303F) || (c >= 0x3200 && c <= 0x33FF)
				|| (c >= 0xFF01 && c <= 0xFF5E) || (c >= 0xFFE0 && c <= 0xFFE5);
	}

	private static int countSjisNonKanaSymbols(String ms932Text) {
		int n = 0;
		for (int i = 0; i < ms932Text.length(); i++) {
			if (isSjisNonKanaSymbol(ms932Text.charAt(i))) {
				n++;
			}
		}
		return n;
	}

	/** MS949解釈にKS X 1001漢字があれば、韓国語の積極的証拠として扱う。
	 * An EUC-KR-encodable KS X 1001 Hanja in the MS949 reading is positive evidence of Korean text. */
	private static boolean hasKoreanHanja(String ms949Text) {
		CharsetEncoder enc = EUC_KR_ENCODER.get();
		for (int i = 0; i < ms949Text.length(); i++) {
			char c = ms949Text.charAt(i);
			if (c >= 0x4E00 && c <= 0x9FFF && enc.canEncode(c)) {
				return true;
			}
		}
		return false;
	}

	private static boolean checkRoundTrip(byte[] data, int length, String charsetName) {
		try {
			Charset cs = Charset.forName(charsetName);
			String s = new String(data, 0, length, cs);
			if (!s.contains("\r\n") && !s.contains("\r") && !s.contains("\n") && !s.contains("#")) {
				return false;
			}
			byte[] reencoded = s.getBytes(cs);
			int matchCount = 0;
			int minLen = Math.min(length, reencoded.length);
			for (int i = 0; i < minLen; i++) {
				if (data[i] == reencoded[i]) {
					matchCount++;
				} else {
					break;
				}
			}
			return matchCount > length - 4;
		} catch (Exception e) {
			return false;
		}
	}

	private static String tryDecode(byte[] data, int length, String charsetName) {
		try {
			Charset cs = Charset.forName(charsetName);
			CharsetDecoder cd = cs.newDecoder()
					.onMalformedInput(CodingErrorAction.REPLACE)
					.onUnmappableCharacter(CodingErrorAction.REPLACE);
			return cd.decode(ByteBuffer.wrap(data, 0, length)).toString();
		} catch (Exception e) {
			return null;
		}
	}

	private static boolean isStandardHangul(char ch) {
		if (ch < 0xAC00 || ch > 0xD7A3) {
			return false;
		}
		try {
			return EUC_KR_ENCODER.get().canEncode(ch);
		} catch (Exception e) {
			return false;
		}
	}

	private static int evaluateKorean(String text) {
		int score = 0;
		int totalHangulSyllables = 0;
		String[] lines = text.split("\\r?\\n", -1);
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			boolean isHeader = isBmsTextHeader(trimmed);
			int multiplier = isHeader ? 5 : 1;

			int consecutiveHangul = 0;
			for (int i = 0; i < trimmed.length();) {
				int cp = trimmed.codePointAt(i);
				i += Character.charCount(cp);
				if (cp >= 0xAC00 && cp <= 0xD7A3) { // ハングル音節 / Hangul syllables
					if (isStandardHangul((char) cp)) {
						// KS X 1001の標準音節 / Standard KS X 1001 syllable
						totalHangulSyllables++;
						consecutiveHangul++;
						score += 20 * multiplier;
						if (consecutiveHangul >= 2) {
							score += 10 * multiplier; // 連続するハングルを加点 / Consecutive Hangul bonus
						}
					} else {
						// 拡張音節はShift-JISバイトをCP949で誤読した場合にも生じる。
						// Extended syllables can also arise when Shift-JIS bytes are misread as CP949.
						consecutiveHangul = 0;
						score -= 10 * multiplier;
					}
				} else {
					consecutiveHangul = 0;
					if ((cp >= 0x3131 && cp <= 0x318E) || (cp >= 0x1100 && cp <= 0x11FF)) { // ハングル字母 / Hangul Jamo
						score += 5 * multiplier;
					} else if (cp == 0xFFFD) { // 置換・エラー文字として扱う / Replacement or error character
						score -= 20 * multiplier;
					}
				}
			}
		}
		// 孤立したハングル音節が一つだけなら、Shift-JISの偶然一致を考慮して加点を制限する。
		// Cap the score for one isolated Hangul syllable to reduce accidental Shift-JIS matches.
		if (totalHangulSyllables <= 1 && score > 0) {
			score = Math.min(score, 10);
		}
		return score;
	}

	private static int evaluateJapanese(String text) {
		int score = 0;
		int halfWidthKatakanaCount = 0;
		int fullWidthKatakanaCount = 0;
		int hiraganaCount = 0;
		int kanjiCount = 0;

		String[] lines = text.split("\\r?\\n", -1);
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			boolean isHeader = isBmsTextHeader(trimmed);
			int multiplier = isHeader ? 5 : 1;

			for (int i = 0; i < trimmed.length();) {
				int cp = trimmed.codePointAt(i);
				i += Character.charCount(cp);
				if (cp >= 0x3040 && cp <= 0x309F) { // ひらがな / Hiragana
					hiraganaCount++;
					score += 30 * multiplier;
				} else if (cp >= 0x30A0 && cp <= 0x30FF) { // 全角カタカナ / Full-width Katakana
					fullWidthKatakanaCount++;
					score += 20 * multiplier;
				} else if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0xF900 && cp <= 0xFAFF)
						|| (cp >= 0x20000 && cp <= 0x2FA1F) || (cp >= 0x30000 && cp <= 0x323AF)) { // 漢字・拡張漢字・互換漢字 (CJK Unified Ideographs, Ext A-I, Compatibility Ideographs)
					kanjiCount++;
				} else if (cp >= 0xFF61 && cp <= 0xFF9F) { // 半角カタカナ / Half-width Katakana
					halfWidthKatakanaCount++;
					score += 20 * multiplier; // ヘッダーの重みを適用 / Apply the header multiplier
				} else if (cp == 0xFFFD) {
					score -= 20 * multiplier;
				}
			}
		}

		// 漢字は平仮名または全角片仮名が存在する場合に正規の日本語スコアとして加算
		// Kanji adds to Japanese score when accompanied by Hiragana or Full-width Katakana
		if (hiraganaCount > 0 || fullWidthKatakanaCount > 0) {
			score += kanjiCount * 10;
		}

		// 平仮名·全角カタカナがなく、漢字と半角カタカナのみ混在する場合: CP949韓国語がSJIS解釈された典型的なモジバケ (例: ｹ翩ﾏｴﾃﾀﾇ)
		// If Kanji and Half-width Katakana coexist without ANY Hiragana or Full-width Katakana, it is 100% typical CP949 mojibake
		if (hiraganaCount == 0 && fullWidthKatakanaCount == 0 && kanjiCount > 0 && halfWidthKatakanaCount > 0) {
			score = Math.min(score - 1000, -100); // 決定的なモジバケペナルティ (Decisive mojibake penalty)
		}

		return score;
	}

	private static boolean isBmsTextHeader(String line) {
		if (!line.startsWith("#")) {
			return false;
		}
		String upper = line.toUpperCase(Locale.ROOT);
		return upper.startsWith("#TITLE") || upper.startsWith("#ARTIST")
				|| upper.startsWith("#SUBTITLE") || upper.startsWith("#SUBARTIST")
				|| upper.startsWith("#GENRE") || upper.startsWith("#COMMENT");
	}

	/**
	 * 文字列がレガシーShift_JISモジバケ（韓国語EUC-KR/CP949がShift_JISとして誤解釈されたパターン）であるかを判定します。
	 * Checks whether the given text exhibits typical legacy Shift_JIS mojibake patterns
	 * arising from Korean EUC-KR / CP949 bytes being incorrectly decoded as Shift_JIS.
	 *
	 * Typical patterns include:
	 * - Half-width Katakana sequences (e.g. ｸｻﾇﾒ, ﾂｺ) where KS X 1001 syllable bytes in 0xB0..0xC8, 0xA1..0xDF map to half-width kana.
	 * - Unpaired lead bytes, Shift_JIS user-defined / PUA characters (0xE000..0xF8FF), or replacement characters (U+FFFD).
	 * - Re-encoding to MS932 and decoding with MS949 yields valid Korean syllables.
	 */
	public static boolean isLegacyShiftJisMojibake(String text) {
		if (text == null || text.isEmpty()) {
			return false;
		}
		int halfWidthKana = 0;
		int puaCount = 0;
		int replacementCount = 0;
		int hiraganaCount = 0;
		int fullWidthKana = 0;
		int kanjiCount = 0;
		int consecutiveHalfWidthKana = 0;
		int maxConsecutiveHalfWidthKana = 0;
		int hangulCount = 0;

		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);

			if (cp >= 0xAC00 && cp <= 0xD7A3) {
				hangulCount++;
			} else if (cp >= 0xFF61 && cp <= 0xFF9F) {
				halfWidthKana++;
				consecutiveHalfWidthKana++;
				if (consecutiveHalfWidthKana > maxConsecutiveHalfWidthKana) {
					maxConsecutiveHalfWidthKana = consecutiveHalfWidthKana;
				}
			} else {
				consecutiveHalfWidthKana = 0;
				if (cp >= 0x3041 && cp <= 0x3096) {
					hiraganaCount++;
				} else if ((cp >= 0x30A1 && cp <= 0x30FA) && cp != 0x30FB) {
					fullWidthKana++;
				} else if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0xF900 && cp <= 0xFAFF)) {
					kanjiCount++;
				} else if (cp >= 0xE000 && cp <= 0xF8FF) {
					puaCount++;
				} else if (cp == 0xFFFD || cp == 0x30FB) {
					replacementCount++;
				}
			}
		}

		// ひらがな・全角カタカナを含む日本語を韓国語の誤読として扱わない。
		// Do not treat Japanese containing Hiragana or full-width Katakana as misdecoded Korean.
		if (hiraganaCount > 0 || fullWidthKana > 0) {
			return false;
		}

		// 既に通常のハングル音節を含む / Already contains normal Hangul syllables
		if (hangulCount > 0 && halfWidthKana == 0 && puaCount == 0 && replacementCount == 0) {
			return false;
		}

		// パターン1: 日本語の指標がなく、半角カタカナが二文字以上連続する場合。
		// Pattern 1: at least two consecutive half-width Katakana without Japanese markers.
		if (maxConsecutiveHalfWidthKana >= 2 && (halfWidthKana >= 2 || puaCount > 0 || replacementCount > 0)) {
			// ハングルの往復変換で復旧を確認する / Check recovery with a Hangul round trip
			if (canRecoverToHangul(text)) {
				return true;
			}
			// 往復変換が部分的でも、かな・漢字の指標がない半角カナ連続を強い文字化け指標として扱う。
			// The existing heuristic treats consecutive half-width Kana without other Kana/Kanji as strong mojibake evidence.
			if (halfWidthKana >= 2 || puaCount > 0 || replacementCount > 0) {
				return true;
			}
		}

		// パターン2: PUA・置換文字と半角カナまたは漢字が混在する場合。
		// Pattern 2: PUA or replacement characters mixed with half-width Kana or Kanji.
		if ((puaCount > 0 || replacementCount > 0) && (halfWidthKana > 0 || kanjiCount > 0)) {
			return true;
		}

		// パターン3: 往復変換で直接確認する / Pattern 3: direct round-trip recovery check
		return canRecoverToHangul(text);
	}

	/**
	 * MS932でエンコードしたバイト列をMS949でデコードした結果、完成型ハングル音節が得られるか検査します。
	 * Checks if re-encoding the text with MS932 and decoding with MS949 yields standard Hangul syllables.
	 */
	public static boolean canRecoverToHangul(String text) {
		if (text == null || text.length() < 2) {
			return false;
		}
		try {
			Charset ms932 = Charset.forName("MS932");
			Charset ms949 = Charset.forName("MS949");
			byte[] bytes = text.getBytes(ms932);
			if (bytes.length < 2) {
				return false;
			}
			String recovered = new String(bytes, ms949);
			int recoveredHangul = 0;
			for (char c : recovered.toCharArray()) {
				if (isStandardHangul(c)) {
					recoveredHangul++;
				}
			}
			int originalHangul = 0;
			for (char c : text.toCharArray()) {
				if (isStandardHangul(c)) {
					originalHangul++;
				}
			}
			return recoveredHangul >= 2 && recoveredHangul > originalHangul;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * レガシーShift_JISモジバケ文字列を韓国語（MS949）として復元を試みます。
	 * Attempts to recover the original Korean (MS949) string from legacy Shift_JIS mojibake.
	 * If recovery is successful and produces valid Hangul, returns the recovered string;
	 * otherwise returns the original string unmodified.
	 */
	public static String tryRecoverShiftJisMojibake(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		if (!isLegacyShiftJisMojibake(text)) {
			return text;
		}
		try {
			Charset ms932 = Charset.forName("MS932");
			Charset ms949 = Charset.forName("MS949");
			byte[] bytes = text.getBytes(ms932);
			String recovered = new String(bytes, ms949);
			int recoveredHangul = 0;
			for (char c : recovered.toCharArray()) {
				if (isStandardHangul(c)) {
					recoveredHangul++;
				}
			}
			if (recoveredHangul > 0) {
				return recovered;
			}
		} catch (Exception ignored) {
		}
		return text;
	}

	/**
	 * 文字列にモジバケ（不正な制御文字、置換文字U+FFFD、未定義PUA文字、Shift_JIS誤解釈等）が含まれているかを判定します。
	 * Checks whether the text contains mojibake or corrupted characters.
	 */
	public static boolean containsMojibake(String text) {
		if (text == null || text.isEmpty()) {
			return false;
		}
		if (isLegacyShiftJisMojibake(text)) {
			return true;
		}
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == 0xFFFD || (cp >= 0xE000 && cp <= 0xF8FF)) {
				return true;
			}
			if (cp < 0x20 && cp != '\t' && cp != '\r' && cp != '\n') {
				return true;
			}
		}
		return false;
	}

	/**
	 * メタデータ文字列（タイトル、アーティスト、ジャンル等）が健全で破損していないかを検証します。
	 * Validates whether metadata text (title, artist, genre, etc.) is clean, valid, and free of corruption.
	 */
	public static boolean isValidMetadataText(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		return !containsMojibake(text);
	}
}
