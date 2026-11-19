package bms.player.beatoraja.song.archive;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.CRC32;

import org.apache.commons.compress.archivers.zip.UnicodePathExtraField;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** ZIP reader with one entry-name policy for scanning, streaming and extraction. */
public final class ZipSongArchive extends SongArchive {
	private static final Logger LOG = Logger.getLogger(ZipSongArchive.class.getName());
	private static final Charset MS932 = Charset.forName("MS932");
	private static final Charset MS949 = Charset.forName("MS949");

	public ZipSongArchive() {
		super(".zip");
	}

	@Override
	public List<String> listEntries(Path archive) throws IOException {
		try (Archive zip = new Archive(archive)) {
			return zip.entries.values().stream().map(Item::name).toList();
		}
	}

	@Override
	public long entrySize(Path archive, String entryName) throws IOException {
		try (Archive zip = new Archive(archive)) {
			return zip.find(entryName).entry().getSize();
		}
	}

	@Override
	public InputStream openEntry(Path archive, String entryName) throws IOException {
		Archive zip = new Archive(archive);
		try {
			Item item = zip.find(entryName);
			long limit = entryLimit(item.name());
			if (item.entry().getSize() > limit) {
				throw new IOException("ZIP entry exceeds the size limit: " + item.name());
			}
			return new FilterInputStream(limitedEntry(zip.file.getInputStream(item.entry()), limit, item.name())) {
				@Override
				public void close() throws IOException {
					try (zip) {
						super.close();
					}
				}
			};
		} catch (IOException | RuntimeException e) {
			closeOnFailure(zip, e);
			throw e;
		}
	}

	@Override
	public byte[] readEntry(Path archive, String entryName) throws IOException {
		try (InputStream input = openEntry(archive, entryName); var output = new ByteArrayOutputStream()) {
			return readLimited(input, output, MAX_CHART_SIZE, "ZIP chart is too large: " + entryName);
		}
	}

	@Override
	public void extract(Path archive, Path root) throws IOException {
		Files.createDirectories(root);
		try (Archive zip = new Archive(archive)) {
			long[] total = {0};
			for (Item item : zip.entries.values()) {
				Path target = extractionTarget(root, item.name(), "ZIP entry");
				try (InputStream input = limitedEntry(zip.file.getInputStream(item.entry()), entryLimit(item.name()), item.name());
						OutputStream output = new LimitedOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW),
								total, MAX_EXTRACTED_SIZE, "ZIP is too large after extraction: " + archive)) {
					input.transferTo(output);
				}
			}
		}
	}

	private static String readName(ZipArchiveEntry entry) throws IOException {
		byte[] raw = entry.getRawName();
		if (raw == null) {
			throw new IOException("ZIP entry has no central-directory name");
		}
		String utf8 = decode(raw, StandardCharsets.UTF_8);
		if (entry.getGeneralPurposeBit().usesUTF8ForNames()) {
			if (utf8 == null) {
				throw new IOException("Invalid ZIP UTF-8 entry name");
			}
			return utf8;
		}
		if (entry.getExtraField(UnicodePathExtraField.UPATH_ID) instanceof UnicodePathExtraField unicode) {
			CRC32 crc = new CRC32();
			crc.update(raw);
			if (unicode.getNameCRC32() == crc.getValue()) {
				String extra = decode(unicode.getUnicodeName(), StandardCharsets.UTF_8);
				if (extra != null) {
					return extra;
				}
			}
		}
		if (utf8 != null) {
			return utf8;
		}
		String japanese = decode(raw, MS932);
		String korean = decode(raw, MS949);
		if (korean != null && (japanese == null || (countHangul(korean) > 0
				&& japanese.codePoints().noneMatch(ZipSongArchive::isJapaneseText)))) {
			LOG.fine("Using MS949 for a legacy ZIP entry name");
			return korean;
		}
		if (japanese != null) {
			LOG.fine("Using MS932 for a legacy ZIP entry name");
			return japanese;
		}
		throw new IOException("Unsupported ZIP entry name encoding");
	}

	private static long countHangul(String text) {
		return text.codePoints().filter(c -> c >= 0xAC00 && c <= 0xD7A3).count();
	}

	private static boolean isJapaneseText(int c) {
		return (c >= 0x3041 && c <= 0x30FA) || (c >= 0x4E00 && c <= 0x9FFF);
	}

	private static String decode(byte[] bytes, Charset charset) {
		try {
			String text = charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
			return text.indexOf('\uFFFD') < 0 ? text : null;
		} catch (CharacterCodingException e) {
			return null;
		}
	}

	private static void closeOnFailure(Archive zip, Exception error) {
		try {
			zip.close();
		} catch (IOException closeFailure) {
			error.addSuppressed(closeFailure);
		}
	}

	private record Item(String name, ZipArchiveEntry entry) {}

	private static final class Archive implements AutoCloseable {
		private final ZipFile file;
		private final Map<String, Item> entries = new LinkedHashMap<>();

		private Archive(Path path) throws IOException {
			try {
				file = ZipFile.builder().setPath(path).setCharset(Charset.forName("CP437")).setUseUnicodeExtraFields(false).get();
			} catch (RuntimeException e) {
				throw new IOException("Unable to open ZIP archive: " + path, e);
			}
			try {
				int count = 0;
				Set<String> names = new HashSet<>();
				Set<String> directories = new HashSet<>();
				var all = file.getEntries();
				while (all.hasMoreElements()) {
					if (++count > MAX_ENTRY_COUNT) {
						throw new IOException("ZIP contains too many entries: " + path);
					}
					ZipArchiveEntry entry = all.nextElement();
					int type = entry.getUnixMode() & UnixStat.FILE_TYPE_FLAG;
					if (entry.isUnixSymlink() || (type != 0 && type != UnixStat.FILE_FLAG && type != UnixStat.DIR_FLAG)) {
						throw new IOException("Unsupported ZIP entry type: " + path);
					}
					String name = readName(entry);
					boolean directory = type == UnixStat.DIR_FLAG || entry.isDirectory() || name.endsWith("/");
					if (directory && name.endsWith("/")) {
						name = name.substring(0, name.length() - 1);
					}
					name = normalizeEntryNameOrNull(name);
					if (name == null || (!directory && !file.canReadEntryData(entry))) {
						throw new IOException("Invalid ZIP entry: " + entry.getName());
					}
					registerEntry(names, directories, name, directory);
					if (!directory) {
						entries.put(entryKey(name), new Item(name, entry));
					}
				}
			} catch (IOException | RuntimeException e) {
				closeOnFailure(this, e);
				LOG.fine("Rejected ZIP archive: " + path + " (" + e.getMessage() + ")");
				throw new IOException("Unable to read ZIP entries: " + path, e);
			}
		}

		private Item find(String name) throws IOException {
			String normalized = normalizeEntryNameOrNull(name);
			Item item = normalized == null ? null : entries.get(entryKey(normalized));
			if (item == null) {
				throw new IOException("ZIP entry does not exist: " + name);
			}
			return item;
		}

		@Override
		public void close() throws IOException {
			file.close();
		}
	}
}
