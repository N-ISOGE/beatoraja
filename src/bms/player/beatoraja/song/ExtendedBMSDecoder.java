package bms.player.beatoraja.song;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import bms.model.BMSDecoder;
import bms.model.BMSModel;
import bms.model.ByteArrayChartSource;
import bms.model.ChartDecoder;
import bms.model.ChartInformation;
import bms.model.ChartSource;

/**
 * BOMを取り除いてからパーサーへ渡し、BOMのないUTF-8を入力全体で判定するBMSデコーダ。
 * 譜面の原本バイト列・ソース・ハッシュは変えない。それ以外の文字コード判定はパーサーのまま。
 * BMS decoder that removes a BOM before parsing and recognizes BOM-less UTF-8 from the whole input.
 * The original bytes, source and hashes of the chart are kept. Any other encoding choice is left to the parser.
 */
public class ExtendedBMSDecoder extends BMSDecoder {

	// 長いBOMを先に比較する (UTF-32LEはUTF-16LEのBOMで始まる)。
	// Longer marks first: the UTF-32LE mark starts with the UTF-16LE one.
	private static final String[] BOM_CHARSETS = { "UTF-8", "UTF-32BE", "UTF-32LE", "UTF-16BE", "UTF-16LE" };
	private static final byte[][] BOMS = { { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF }, { 0, 0, (byte) 0xFE, (byte) 0xFF },
			{ (byte) 0xFF, (byte) 0xFE, 0, 0 }, { (byte) 0xFE, (byte) 0xFF }, { (byte) 0xFF, (byte) 0xFE } };

	// パーサーが文字コード判定に使う先頭部分の長さ。
	// Length of the leading part the parser samples for its own encoding choice.
	private static final int PARSER_SAMPLE = 1024 * 64;

	// パーサーは準備済みバイト列を受け取ったあと、文字コードをもう一度問い合わせる。
	// The parser asks for the encoding again after it receives the prepared bytes.
	private String preparedCharset;

	public ExtendedBMSDecoder(int lntype) {
		super(lntype);
	}

	/**
	 * .bms/.bme/.bml/.pms にはこのデコーダを、それ以外には従来のデコーダを返す。
	 * Returns this decoder for .bms/.bme/.bml/.pms and the usual decoder for anything else.
	 */
	public static ChartDecoder getDecoder(ChartInformation info) {
		ChartDecoder decoder = info.source != null ? ChartDecoder.getDecoder(info.source) : ChartDecoder.getDecoder(info.path);
		return decoder instanceof BMSDecoder ? new ExtendedBMSDecoder(info.lntype) : decoder;
	}

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
		preparedCharset = detectEncoding(raw, raw.length);
		int bom = bomIndex(raw);
		return bom < 0 ? raw : Arrays.copyOfRange(raw, BOMS[bom].length, raw.length);
	}

	// パーサーにはBOMのないバイト列を渡したので、原本のソースと原本バイト列のハッシュへ戻す。
	// The parser saw the bytes without the BOM; put back the original source and the hashes of the original bytes.
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

	@Override
	public String detectEncoding(byte[] data) {
		return detectEncoding(data, data != null ? data.length : 0);
	}

	/**
	 * BOM、次に入力全体が非ASCIIを含む正しいUTF-8かを見て、どちらでもなければパーサーの判定に任せる。
	 * A BOM first, then whether the whole input is well-formed UTF-8 with non-ASCII text; otherwise the parser decides as before.
	 */
	@Override
	public String detectEncoding(byte[] data, int length) {
		if (preparedCharset != null) {
			return preparedCharset;
		}
		if (data != null) {
			int bom = bomIndex(data);
			if (bom >= 0) {
				return BOM_CHARSETS[bom];
			}
			if (isNonAsciiUtf8(data, Math.min(data.length, length))) {
				return "UTF-8";
			}
		}
		return super.detectEncoding(data, Math.min(length, PARSER_SAMPLE));
	}

	private static int bomIndex(byte[] data) {
		for (int i = 0; i < BOMS.length; i++) {
			int size = BOMS[i].length;
			if (data.length >= size && Arrays.equals(data, 0, size, BOMS[i], 0, size)) {
				return i;
			}
		}
		return -1;
	}

	private static boolean isNonAsciiUtf8(byte[] data, int length) {
		boolean nonAscii = false;
		for (int i = 0; i < length && !nonAscii; i++) {
			nonAscii = data[i] < 0;
		}
		if (!nonAscii) {
			return false;
		}
		try {
			// A new decoder reports malformed input instead of replacing it.
			StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data, 0, length));
			return true;
		} catch (CharacterCodingException e) {
			return false;
		}
	}
}
