package bms.player.beatoraja.song.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.text.Normalizer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Base class for a song archive format. Implementations provide archive-format
 * specific I/O while this class centralizes limits and path validation.
 */
public abstract class SongArchive {

	protected static final long MAX_EXTRACTED_SIZE = 4L * 1024 * 1024 * 1024;
	protected static final long MAX_CHART_SIZE = 64L * 1024 * 1024;
	protected static final long MAX_MEDIA_SIZE = 512L * 1024 * 1024;
	protected static final int MAX_ENTRY_COUNT = 100_000;

	private final List<String> extensions;

	protected SongArchive(String... extensions) {
		this.extensions = Arrays.stream(extensions)
				.map(extension -> extension.toLowerCase(Locale.ROOT))
				.toList();
	}

	public final boolean supports(Path path) {
		Path filename = path.getFileName();
		if (filename == null || !Files.isRegularFile(path)) {
			return false;
		}
		String name = filename.toString().toLowerCase(Locale.ROOT);
		return extensions.stream().anyMatch(name::endsWith);
	}

	public final List<String> extensions() {
		return extensions;
	}

	public abstract List<String> listEntries(Path archive) throws IOException;

	/** Opens one entry. Closing the returned stream must also close the archive. */
	public abstract InputStream openEntry(Path archive, String entryName) throws IOException;

	public abstract long entrySize(Path archive, String entryName) throws IOException;

	public abstract byte[] readEntry(Path archive, String entryName) throws IOException;

	public abstract void extract(Path archive, Path root) throws IOException;

	protected static String normalizeEntryName(String entryName) {
		String normalized = normalizeEntryNameOrNull(entryName);
		if (normalized == null) {
			throw new IllegalArgumentException("Unsafe archive entry: " + entryName);
		}
		return normalized;
	}

	protected static String normalizeEntryNameOrNull(String entryName) {
		String normalized = entryName.replace('\\', '/');
		while (normalized.startsWith("./")) {
			normalized = normalized.substring(2);
		}
		if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains("//")) {
			return null;
		}
		for (String component : normalized.split("/", -1)) {
			if (component.equals(".") || component.equals("..") || component.isEmpty() || component.endsWith(".")
					|| component.endsWith(" ") || component.codePoints().anyMatch(c -> Character.isISOControl(c)
					|| "<>:\"|?*".indexOf(c) >= 0)
					|| component.matches("(?i)(CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[1-9¹²³]|LPT[1-9¹²³])( *\\..*)?")) {
				return null;
			}
		}
		return normalized;
	}

	protected static String entryKey(String name) {
		return Normalizer.normalize(name, Normalizer.Form.NFC);
	}

	protected static void registerEntry(Set<String> files, Set<String> directories, String name,
			boolean directory) throws IOException {
		String key = entryKey(name);
		for (int slash = key.indexOf('/'); slash >= 0; slash = key.indexOf('/', slash + 1)) {
			String parent = key.substring(0, slash);
			if (files.contains(parent)) {
				throw new IOException("Archive entry conflicts with a file: " + name);
			}
			directories.add(parent);
		}
		if (directory) {
			if (files.contains(key)) {
				throw new IOException("Archive directory conflicts with a file: " + name);
			}
			directories.add(key);
		} else if (directories.contains(key) || !files.add(key)) {
			throw new IOException("Duplicate or conflicting archive entry: " + name);
		}
	}

	protected static long entryLimit(String name) {
		return SongArchives.isChartEntry(name) ? MAX_CHART_SIZE : MAX_MEDIA_SIZE;
	}

	protected static Path extractionTarget(Path root, String entryName, String errorPrefix) throws IOException {
		String name = normalizeEntryNameOrNull(entryName);
		if (name == null) {
			throw new IOException("Unsafe " + errorPrefix + ": " + entryName);
		}
		Path directory = root.toRealPath();
		String[] components = name.split("/");
		for (int i = 0; i < components.length - 1; i++) {
			directory = directory.resolve(components[i]);
			try {
				Files.createDirectory(directory);
			} catch (FileAlreadyExistsException e) {
				if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.equals(directory.toRealPath())) {
					throw new IOException("Unsafe " + errorPrefix + " parent: " + entryName, e);
				}
			}
		}
		return directory.resolve(components[components.length - 1]);
	}

	protected static InputStream limitedEntry(InputStream input, long limit, String name) {
		return new FilterInputStream(input) {
			private long remaining = limit;

			private void consumed(long count) throws IOException {
				if (count > 0 && (remaining -= count) < 0) {
					throw new IOException("Archive entry exceeds the size limit: " + name);
				}
			}

			@Override
			public int read() throws IOException {
				int value = in.read();
				consumed(value == -1 ? 0 : 1);
				return value;
			}

			@Override
			public int read(byte[] bytes, int offset, int length) throws IOException {
				Objects.checkFromIndexSize(offset, length, bytes.length);
				int count = in.read(bytes, offset, (int) Math.min(length, Math.max(1, remaining + 1)));
				consumed(count);
				return count;
			}

			@Override
			public long skip(long length) throws IOException {
				long count = in.skip(Math.min(length, Math.max(1, remaining + 1)));
				consumed(count);
				return count;
			}

			@Override
			public boolean markSupported() {
				return false;
			}

			@Override
			public void mark(int limit) {
			}

			@Override
			public void reset() throws IOException {
				throw new IOException("Archive stream does not support reset");
			}
		};
	}

	protected static byte[] readLimited(InputStream input, ByteArrayOutputStream output, long maxSize,
			String errorMessage) throws IOException {
		byte[] buffer = new byte[8192];
		long size = 0;
		int read;
		while ((read = input.read(buffer)) >= 0) {
			size += read;
			if (size > maxSize) {
				throw new IOException(errorMessage);
			}
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}

	protected static final class LimitedOutputStream extends OutputStream {
		private final OutputStream delegate;
		private final long[] extractedSize;
		private final long maxSize;
		private final String errorMessage;

		public LimitedOutputStream(OutputStream delegate, long[] extractedSize, long maxSize, String errorMessage) {
			this.delegate = delegate;
			this.extractedSize = extractedSize;
			this.maxSize = maxSize;
			this.errorMessage = errorMessage;
		}

		@Override
		public void write(int value) throws IOException {
			increment(1);
			delegate.write(value);
		}

		@Override
		public void write(byte[] buffer, int offset, int length) throws IOException {
			increment(length);
			delegate.write(buffer, offset, length);
		}

		@Override
		public void close() throws IOException {
			delegate.close();
		}

		private void increment(int length) throws IOException {
			extractedSize[0] += length;
			if (extractedSize[0] > maxSize) {
				throw new IOException(errorMessage);
			}
		}
	}
}
