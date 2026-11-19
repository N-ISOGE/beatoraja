package bms.player.beatoraja.song.archive;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.github.junrar.Archive;
import com.github.junrar.ArchiveOptions;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;
import com.github.junrar.rarfile.HostSystem;

/** RAR implementation backed by Junrar. */
public final class RarSongArchive extends SongArchive {
	private static final long MAX_DICTIONARY_SIZE = 256L * 1024 * 1024;
	private static final ArchiveOptions OPTIONS = ArchiveOptions.builder()
			.maxDictionarySize(MAX_DICTIONARY_SIZE).build();

	public RarSongArchive() {
		super(".rar");
	}

	@Override
	public List<String> listEntries(Path archive) throws IOException {
		try (Archive rar = open(archive)) {
			List<FileHeader> headers = checkedHeaders(rar, archive);
			List<String> entries = new ArrayList<>();
			for (FileHeader header : headers) {
				if (header.isDirectory()) {
					continue;
				}
				entries.add(normalizeEntryName(header.getFileName()));
			}
			return entries;
		} catch (RarException e) {
			throw new IOException("Unable to read RAR archive: " + archive, e);
		}
	}

	@Override
	public long entrySize(Path archive, String entryName) throws IOException {
		try (Archive rar = open(archive)) {
			for (FileHeader header : checkedHeaders(rar, archive)) {
				if (!header.isDirectory() && entryMatches(entryName, header.getFileName())) {
					return header.getFullUnpackSize();
				}
			}
			throw new IOException("RAR entry does not exist: " + entryName);
		} catch (RarException e) {
			throw new IOException("Unable to read RAR entry: " + entryName, e);
		}
	}

	@Override
	public InputStream openEntry(Path archive, String entryName) throws IOException {
		try {
			Archive rar = open(archive);
			try {
				for (FileHeader header : checkedHeaders(rar, archive)) {
					if (!header.isDirectory() && entryMatches(entryName, header.getFileName())) {
						long limit = entryLimit(entryName);
						if (header.getFullUnpackSize() > limit) {
							throw new IOException("RAR entry exceeds the size limit: " + entryName);
						}
						return new FilterInputStream(limitedEntry(rar.getInputStream(header), limit, entryName)) {
							@Override
							public void close() throws IOException {
								try {
									super.close();
								} finally {
									rar.close();
								}
							}
						};
					}
				}
				throw new IOException("RAR entry does not exist: " + entryName);
			} catch (IOException | RuntimeException e) {
				try {
					rar.close();
				} catch (IOException closeFailure) {
					e.addSuppressed(closeFailure);
				}
				throw e;
			}
		} catch (RarException e) {
			throw new IOException("Unable to read RAR entry: " + entryName, e);
		}
	}

	@Override
	public byte[] readEntry(Path archive, String entryName) throws IOException {
		try (Archive rar = open(archive)) {
			for (FileHeader header : checkedHeaders(rar, archive)) {
				if (header.isDirectory() || !entryMatches(entryName, header.getFileName())) {
					continue;
				}
				long size = header.getFullUnpackSize();
				if (size > MAX_CHART_SIZE) {
					throw new IOException("RAR chart is too large: " + entryName);
				}
				try (InputStream input = rar.getInputStream(header);
						ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.max(size, 0))) {
					return readLimited(input, output, MAX_CHART_SIZE, "RAR chart is too large: " + entryName);
				}
			}
			throw new IOException("RAR entry does not exist: " + entryName);
		} catch (RarException e) {
			throw new IOException("Unable to read RAR entry: " + entryName, e);
		}
	}

	@Override
	public void extract(Path archive, Path root) throws IOException {
		Files.createDirectories(root);
		try (Archive rar = open(archive)) {
			List<FileHeader> headers = checkedHeaders(rar, archive);
			long[] extractedSize = { 0 };
			for (FileHeader header : headers) {
				if (header.isDirectory()) {
					continue;
				}
				String name = normalizeEntryName(header.getFileName());
				Path target = extractionTarget(root, name, "RAR entry");
				try (OutputStream archiveOutput = new LimitedOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW),
						extractedSize, MAX_EXTRACTED_SIZE, "RAR is too large after extraction: " + archive);
						OutputStream output = new LimitedOutputStream(archiveOutput, new long[1], entryLimit(name),
								"RAR entry exceeds the size limit: " + name)) {
					rar.extractFile(header, output);
				}
			}
		} catch (RarException e) {
			throw new IOException("Unable to extract RAR archive: " + archive, e);
		}
	}

	private List<FileHeader> checkedHeaders(Archive rar, Path archive) throws IOException {
		List<FileHeader> headers = rar.getFileHeaders();
		if (headers.size() > MAX_ENTRY_COUNT) {
			throw new IOException("RAR contains too many entries: " + archive);
		}
		Set<String> names = new HashSet<>();
		Set<String> directories = new HashSet<>();
		for (FileHeader header : headers) {
			validate(header, archive);
			String raw = header.getFileName();
			if (header.isDirectory() && (raw.endsWith("/") || raw.endsWith("\\"))) {
				raw = raw.substring(0, raw.length() - 1);
			}
			String name = normalizeEntryNameOrNull(raw);
			if (name == null) {
				throw new IOException("Unsafe RAR entry: " + raw);
			}
			registerEntry(names, directories, name, header.isDirectory());
		}
		return headers;
	}

	private boolean entryMatches(String requested, String stored) {
		String name = normalizeEntryNameOrNull(requested);
		String match = normalizeEntryNameOrNull(stored);
		return name != null && match != null && entryKey(name).equals(entryKey(match));
	}

	private Archive open(Path archive) throws IOException, RarException {
		Archive rar = new Archive(archive.toFile(), OPTIONS);
		try {
			if (rar.isPasswordProtected()) {
				throw new IOException("Password protected RAR archives are not supported: " + archive);
			}
			if (rar.hasBrokenHeaders()) {
				throw new IOException("RAR archive has broken headers: " + archive);
			}
			return rar;
		} catch (IOException | RarException e) {
			try {
				rar.close();
			} catch (IOException ignored) {
			}
			throw e;
		}
	}

	private void validate(FileHeader header, Path archive) throws IOException {
		int unixType = header.getHostOS() == HostSystem.unix ? header.getFileAttr() & 0170000 : 0;
		if (header.getRedirection() != null || (unixType != 0 && unixType != 0100000 && unixType != 0040000)) {
			throw new IOException("RAR links are not supported: " + archive);
		}
		if (header.isRar5Container() && header.getRar5WinSize() > MAX_DICTIONARY_SIZE) {
			throw new IOException("RAR dictionary is too large: " + archive);
		}
		if (header.isEncrypted()) {
			throw new IOException("Password protected RAR entries are not supported: " + archive);
		}
		if (header.isSplitBefore() || header.isSplitAfter()) {
			throw new IOException("Split RAR archives are not supported: " + archive);
		}
	}

}
