package bms.player.beatoraja.external;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import bms.player.beatoraja.song.SongData;
import com.badlogic.gdx.utils.Json;

import bms.player.beatoraja.*;
import bms.player.beatoraja.TableData.TableFolder;

/**
 * BMS Searchアクセス用クラス
 * @see <a href="https://bmssearch.stoplight.io/docs/bmssearch-api/YXBpOjg0MzMw-bms-search-api">bmssearch-api</a>
 * @author exch
 */
public class BMSSearchAccessor extends TableDataAccessor.TableAccessor {
	private static final String DEFAULT_API_BASE = "https://api.bmssearch.net";
	private static final String SEARCH_PATH = "/v1/bmses/search?orderBy=PUBLISHED&orderDirection=DESC&limit=20";
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static final ScheduledThreadPoolExecutor BODY_TIMEOUT_SCHEDULER = createBodyTimeoutScheduler();

	private static ScheduledThreadPoolExecutor createBodyTimeoutScheduler() {
		ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, "bms-search-http-body-timeout");
			t.setDaemon(true);
			return t;
		});
		scheduler.setRemoveOnCancelPolicy(true);
		return scheduler;
	}

	private final String tabledir;
	private final String apiBaseUrl;

	public BMSSearchAccessor(String tabledir) {
		this(tabledir, DEFAULT_API_BASE);
	}

	public BMSSearchAccessor(String tabledir, String apiBaseUrl) {
		super("BMS Search");
		this.tabledir = tabledir;
		String base = (apiBaseUrl == null || apiBaseUrl.isBlank()) ? DEFAULT_API_BASE : apiBaseUrl.trim();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		this.apiBaseUrl = base;
	}

	public TableData read() {
		TableData td = null;
		try {
			String searchJson = fetchJson(apiBaseUrl + SEARCH_PATH);
			Json json = new Json();
			json.setIgnoreUnknownFields(true);
			BMSSearchElement[] elements = json.fromJson(BMSSearchElement[].class, searchJson);
			if (elements == null || elements.length == 0) {
				Logger.getGlobal().warning("BMS Search応答が空です");
				return null;
			}

			td = new TableData();
			TableFolder tdenew = new TableFolder();
			tdenew.setName("New");
			List<SongData> songs = new ArrayList<SongData>();
			for (BMSSearchElement element : elements) {
				if (element == null) {
					continue;
				}
				SongData song = new SongData();
				song.setTitle(element.getTitle());
				song.setArtist(element.getArtist());
				song.setGenre(element.getGenre());
				if (element.getDownloads() != null && element.getDownloads().length > 0 && element.getDownloads()[0] != null) {
					song.setUrl(element.getDownloads()[0].getUrl());
				}

				// MD5取得処理
				String safeId = sanitizePathSegment(element.getId());
				if (safeId != null) {
					try {
						String patternJson = fetchJson(apiBaseUrl + "/v1/bmses/" + safeId + "/patterns?limit=1");
						Json _json = new Json();
						_json.setIgnoreUnknownFields(true);
						BMSPatterns[] patterns = _json.fromJson(BMSPatterns[].class, patternJson);
						if (patterns != null && patterns.length > 0 && patterns[0] != null && patterns[0].getFile() != null) {
							song.setMd5(patterns[0].getFile().getHashMd5());
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return null;
					} catch (IOException | RuntimeException e) {
						Logger.getGlobal().warning("BMS Searchパターン取得をスキップ (" + safeId + "): " + e.getMessage());
					}
				} else {
					Logger.getGlobal().warning("BMS Search不正なIDをスキップしました");
				}

				songs.add(song);
			}
			tdenew.setSong(songs.toArray(new SongData[songs.size()]));
			td.setFolder(new TableFolder[] { tdenew });
			td.setName("BMS Search");
			if (!td.validate()) {
				Logger.getGlobal().warning("BMS Searchテーブルデータが不正です");
				return null;
			}
			Logger.getGlobal().info("BMS Search取得完了");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			Logger.getGlobal().warning("BMS Search取得が中断されました");
			return null;
		} catch (Exception e) {
			Logger.getGlobal().severe("BMS Search更新中の例外:" + e.getMessage());
			return null;
		}
		return td;
	}

	private static String sanitizePathSegment(String id) {
		if (id == null) {
			return null;
		}
		String trimmed = id.trim();
		if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..") || trimmed.contains("..")
				|| trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0
				|| trimmed.indexOf('?') >= 0 || trimmed.indexOf('#') >= 0) {
			return null;
		}
		for (int i = 0; i < trimmed.length(); i++) {
			if (Character.isISOControl(trimmed.charAt(i))) {
				return null;
			}
		}
		return URLEncoder.encode(trimmed, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String fetchJson(String url) throws IOException, InterruptedException {
		URI uri;
		try {
			uri = URI.create(url);
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid BMS Search URL: " + url, e);
		}
		String scheme = uri.getScheme();
		if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
				|| uri.getHost() == null || uri.getHost().isBlank()) {
			throw new IOException("Unsupported BMS Search URL scheme: " + url);
		}
		long timeoutNanos = Math.max(1L, REQUEST_TIMEOUT.toNanos());
		long deadlineNanos = System.nanoTime() + timeoutNanos;
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(REQUEST_TIMEOUT)
				.header("User-Agent", "beatoraja")
				.header("Accept", "application/json")
				.GET()
				.build();
		HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream body = response.body()) {
			int status = response.statusCode();
			if (status < 200 || status >= 300) {
				throw new IOException("HTTP " + status + " for " + uri.toASCIIString().toLowerCase(Locale.ROOT));
			}
			long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
			if (contentLength > MAX_RESPONSE_BYTES) {
				throw new IOException("BMS Search response exceeds " + MAX_RESPONSE_BYTES + " bytes");
			}
			long remainingNanos = deadlineNanos - System.nanoTime();
			if (remainingNanos <= 0L) {
				throw new HttpTimeoutException("BMS Search response body timed out after " + REQUEST_TIMEOUT + ": " + uri);
			}
			AtomicBoolean timedOut = new AtomicBoolean(false);
			ScheduledFuture<?> timeoutTask = BODY_TIMEOUT_SCHEDULER.schedule(() -> {
				timedOut.set(true);
				try {
					body.close();
				} catch (IOException ignored) {
				}
			}, remainingNanos, TimeUnit.NANOSECONDS);
			try {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buffer = new byte[8192];
				int total = 0;
				int n;
				while ((n = body.read(buffer)) != -1) {
					if (timedOut.get() || System.nanoTime() - deadlineNanos > 0L) {
						throw new HttpTimeoutException("BMS Search response body timed out after " + REQUEST_TIMEOUT + ": " + uri);
					}
					total += n;
					if (total > MAX_RESPONSE_BYTES) {
						throw new IOException("BMS Search response exceeds " + MAX_RESPONSE_BYTES + " bytes");
					}
					out.write(buffer, 0, n);
				}
				if (timedOut.get()) {
					throw new HttpTimeoutException("BMS Search response body timed out after " + REQUEST_TIMEOUT + ": " + uri);
				}
				return out.toString(StandardCharsets.UTF_8);
			} catch (IOException e) {
				if (timedOut.get()) {
					HttpTimeoutException timeoutEx = new HttpTimeoutException(
							"BMS Search response body timed out after " + REQUEST_TIMEOUT + ": " + uri);
					timeoutEx.initCause(e);
					throw timeoutEx;
				}
				throw e;
			} finally {
				timeoutTask.cancel(false);
			}
		}
	}

	@Override
	public void write(TableData td) {
		new TableDataAccessor(tabledir).write(td);
	}

	public static class BMSSearchElement {

		private String id;
		private String title;
		private String genre;
		private String artist;
		private Downloads[] downloads;

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public String getTitle() {
			return title;
		}

		public void setTitle(String title) {
			this.title = title;
		}

		public String getGenre() {
			return genre;
		}

		public void setGenre(String genre) {
			this.genre = genre;
		}

		public Downloads[] getDownloads() {
			return downloads;
		}

		public void setDownloads(Downloads[] downloads) {
			this.downloads = downloads;
		}

		public String getArtist() {
			return artist;
		}

		public void setArtist(String artist) {
			this.artist = artist;
		}
	}

	public static class Downloads {
		private String url;
		private String description;

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

		public String getDescription() {
			return description;
		}

		public void setDescription(String description) {
			this.description = description;
		}
	}

	public static class BMSPatterns {
		private BMSPatternsFile file;

		public BMSPatternsFile getFile() {
			return file;
		}

		public void setFile(BMSPatternsFile file) {
			this.file = file;
		}
	}

	public static class BMSPatternsFile {
		private String hashMd5;

		public String getHashMd5() {
			return hashMd5;
		}

		public void setHashMd5(String hashMd5) {
			this.hashMd5 = hashMd5;
		}
	}
}
