package bms.player.beatoraja.song;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import bms.player.beatoraja.song.archive.SongArchives;

/** Factory and single-file materialization cache for {@link SongResource}. */
public final class SongResources {

	private static final Map<String, Path> MATERIALIZED = new ConcurrentHashMap<>();
	private static final Map<String, FutureTask<Path>> COPYING = new ConcurrentHashMap<>();

	/** Point-in-time summary of archive/remote files materialized for Path-only APIs. */
	public record MaterializedStatistics(int fileCount, long totalBytes) {
	}

	static {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> MATERIALIZED.values().forEach(path -> {
			try {
				Files.deleteIfExists(path);
			} catch (IOException ignored) {
			}
		}), "song resource cleanup"));
	}

	private SongResources() {
	}

	public static SongResource fromPath(Path path) {
		return SongArchives.resource(path);
	}

	public static SongResource local(Path path) {
		return new LocalSongResource(path.normalize());
	}

	/**
	 * Creates a resource for a directly accessible HTTP(S) file. Directory
	 * listing intentionally requires a future manifest API and is unsupported.
	 */
	public static SongResource remote(URI uri) {
		return new RemoteSongResource(uri.normalize());
	}

	public static Path materialize(SongResource resource) throws IOException {
		Optional<Path> localPath = resource.localPath();
		if (localPath.isPresent()) {
			return localPath.get();
		}
		String key = resource.cacheKey();
		Path cached = MATERIALIZED.get(key);
		if (cached != null && Files.isRegularFile(cached)) {
			return cached;
		}
		FutureTask<Path> candidate = new FutureTask<>(() -> copyResource(resource, key));
		FutureTask<Path> task = COPYING.putIfAbsent(key, candidate);
		if (task == null) {
			task = candidate;
			try {
				task.run();
			} finally {
				COPYING.remove(key, task);
			}
		}
		try {
			return task.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while materializing " + resource.name(), e);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof IOException failure) {
				throw failure;
			}
			if (e.getCause() instanceof RuntimeException failure) {
				throw failure;
			}
			if (e.getCause() instanceof Error failure) {
				throw failure;
			}
			throw new IOException("Unable to materialize " + resource.name(), e.getCause());
		}
	}

	private static Path copyResource(SongResource resource, String key) throws IOException {
		Path cached = MATERIALIZED.get(key);
		if (cached != null && Files.isRegularFile(cached)) {
			return cached;
		}
		long limit = SongArchives.isChartEntry(resource.name()) ? 64L * 1024 * 1024 : 512L * 1024 * 1024;
		Path target = Files.createTempFile("beatoraja-song-resource-", extensionSuffix(resource.name()));
		try {
			try (InputStream input = resource.openStream(); var output = Files.newOutputStream(target)) {
				byte[] buffer = new byte[8192];
				long remaining = limit;
				int count;
				while ((count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining + 1))) != -1) {
					if ((remaining -= count) < 0) {
						throw new IOException("Song resource exceeds the size limit: " + resource.name());
					}
					output.write(buffer, 0, count);
				}
			}
			if (!key.equals(resource.cacheKey())) {
				throw new IOException("Song resource changed during materialization: " + resource.name());
			}
			MATERIALIZED.put(key, target);
			return target;
		} catch (IOException | RuntimeException e) {
			try {
				Files.deleteIfExists(target);
			} catch (IOException cleanupFailure) {
				e.addSuppressed(cleanupFailure);
			}
			throw e;
		}
	}

	public static MaterializedStatistics getMaterializedStatistics() {
		long totalBytes = 0;
		for (Path path : MATERIALIZED.values()) {
			try {
				totalBytes += Files.size(path);
			} catch (IOException ignored) {
				// A concurrently cleaned temporary file does not invalidate the snapshot.
			}
		}
		return new MaterializedStatistics(MATERIALIZED.size(), totalBytes);
	}

	private static String extensionSuffix(String name) {
		int index = name.lastIndexOf('.');
		return index >= 0 && index < name.length() - 1 ? name.substring(index) : ".tmp";
	}

	private record LocalSongResource(Path path) implements SongResource {
		@Override
		public SongResource parent() {
			Path parent = path.getParent();
			return parent != null ? local(parent) : this;
		}

		@Override
		public SongResource resolve(String relativePath) {
			return local(path.resolve(relativePath));
		}

		@Override
		public String name() {
			Path filename = path.getFileName();
			return filename != null ? filename.toString() : path.toString();
		}

		@Override
		public String displayPath() {
			return path.toString();
		}

		@Override
		public String cacheKey() {
			try {
				return path.toAbsolutePath() + ":" + Files.size(path) + ":" + Files.getLastModifiedTime(path);
			} catch (IOException e) {
				return path.toAbsolutePath().toString();
			}
		}

		@Override
		public boolean exists() {
			return Files.exists(path);
		}

		@Override
		public boolean isDirectory() {
			return Files.isDirectory(path);
		}

		@Override
		public long size() throws IOException {
			return Files.size(path);
		}

		@Override
		public InputStream openStream() throws IOException {
			return Files.newInputStream(path);
		}

		@Override
		public List<SongResource> list() throws IOException {
			try (var paths = Files.list(path)) {
				return paths.map(SongResources::local).toList();
			}
		}

		@Override
		public Optional<Path> localPath() {
			return Optional.of(path);
		}
	}

	private record RemoteSongResource(URI uri) implements SongResource {
		@Override
		public SongResource parent() {
			String value = uri.toString();
			int slash = value.lastIndexOf('/');
			return slash >= 0 ? remote(URI.create(value.substring(0, slash + 1))) : this;
		}

		@Override
		public SongResource resolve(String relativePath) {
			return remote(uri.resolve(relativePath));
		}

		@Override
		public String name() {
			String path = uri.getPath();
			int slash = path.lastIndexOf('/');
			return slash >= 0 ? path.substring(slash + 1) : path;
		}

		@Override
		public String displayPath() {
			return uri.toString();
		}

		@Override
		public String cacheKey() {
			return uri.toString();
		}

		@Override
		public boolean exists() throws IOException {
			HttpURLConnection connection = connection("HEAD");
			try {
				return connection.getResponseCode() / 100 == 2;
			} finally {
				connection.disconnect();
			}
		}

		@Override
		public boolean isDirectory() {
			return false;
		}

		@Override
		public long size() throws IOException {
			HttpURLConnection connection = connection("HEAD");
			try {
				long length = connection.getContentLengthLong();
				if (length < 0) {
					throw new IOException("Remote resource does not report a size: " + uri);
				}
				return length;
			} finally {
				connection.disconnect();
			}
		}

		@Override
		public InputStream openStream() throws IOException {
			HttpURLConnection connection = connection("GET");
			return new FilterInputStream(connection.getInputStream()) {
				@Override
				public void close() throws IOException {
					try {
						super.close();
					} finally {
						connection.disconnect();
					}
				}
			};
		}

		@Override
		public List<SongResource> list() throws IOException {
			throw new IOException("Remote song resources require a manifest to list entries: " + uri);
		}

		private HttpURLConnection connection(String method) throws IOException {
			HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
			connection.setRequestMethod(method);
			connection.setConnectTimeout(10_000);
			connection.setReadTimeout(30_000);
			return connection;
		}
	}
}
