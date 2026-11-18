package bms.player.beatoraja.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import bms.player.beatoraja.song.SongResource;

/**
 * Remembers the last listed directory, so the case-insensitive fallback lists a
 * chart directory once instead of once per unresolved sound. A local directory
 * is listed again when its modification time changes.
 */
final class DirectoryListings {
	private record Listing<T>(String key, FileTime stamp, Map<String, List<T>> byLowerName) {
	}

	private static final AtomicReference<Listing<Path>> paths = new AtomicReference<>();
	private static final AtomicReference<Listing<SongResource>> resources = new AtomicReference<>();

	private DirectoryListings() {
	}

	/** Regular files in directory whose name equals name ignoring case. */
	static List<Path> files(Path directory, String name) throws IOException {
		Path absolute = directory.toAbsolutePath().normalize();
		String key = absolute.toString();
		FileTime stamp = Files.getLastModifiedTime(absolute);
		Listing<Path> listing = paths.get();
		if (listing == null || !listing.key().equals(key) || !listing.stamp().equals(stamp)) {
			Map<String, List<Path>> byName = new HashMap<>();
			try (var children = Files.list(absolute)) {
				children.filter(Files::isRegularFile).forEach(child -> add(byName, child.getFileName().toString(), child));
			}
			listing = new Listing<>(key, stamp, byName);
			paths.set(listing);
		}
		return listing.byLowerName().getOrDefault(lower(name), List.of());
	}

	/** Non-directory children of directory whose name equals name ignoring case. */
	static List<SongResource> children(SongResource directory, String name) throws IOException {
		String key = directory.cacheKey();
		FileTime stamp = directory.localPath().isPresent()
				? Files.getLastModifiedTime(directory.localPath().get()) : null;
		Listing<SongResource> listing = resources.get();
		if (listing == null || !listing.key().equals(key) || !Objects.equals(listing.stamp(), stamp)) {
			Map<String, List<SongResource>> byName = new HashMap<>();
			for (SongResource child : directory.list()) {
				if (!child.isDirectory()) {
					add(byName, child.name(), child);
				}
			}
			listing = new Listing<>(key, stamp, byName);
			resources.set(listing);
		}
		return listing.byLowerName().getOrDefault(lower(name), List.of());
	}

	private static <T> void add(Map<String, List<T>> byName, String name, T value) {
		byName.computeIfAbsent(lower(name), ignored -> new ArrayList<>()).add(value);
	}

	private static String lower(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
