package bms.player.beatoraja;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import bms.model.BMSDecoder;
import bms.model.Mode;
import bms.player.beatoraja.CourseData.TrophyData;
import bms.player.beatoraja.song.SongData;
import bms.table.Course.Trophy;
import bms.table.*;

/**
 * 難易度表データアクセス用クラス
 * 
 * @author exch
 */
public class TableDataAccessor {

	private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(20);
	private static final int MAX_REMOTE_TABLE_BYTES = 16 * 1024 * 1024;
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(HTTP_CONNECT_TIMEOUT)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static final ScheduledThreadPoolExecutor BODY_TIMEOUT_SCHEDULER = createBodyTimeoutScheduler();

	private static ScheduledThreadPoolExecutor createBodyTimeoutScheduler() {
		ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, "table-http-body-timeout");
			t.setDaemon(true);
			return t;
		});
		scheduler.setRemoveOnCancelPolicy(true);
		return scheduler;
	}
	private static final Pattern META_TAG_PATTERN = Pattern.compile("<meta\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
	private static final Pattern HTML_ATTR_PATTERN = Pattern.compile(
			"([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");
	private static final Pattern EXPLICIT_SCHEME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");

	private final String tabledir;

	public TableDataAccessor(String tabledir) {
		this.tabledir = tabledir;
	}

	public void updateTableData(String[] urls) {
		if (urls == null) {
			return;
		}
		Arrays.stream(urls).parallel().forEach(url -> {
            if (url == null || url.isBlank()) {
                return;
            }
            TableAccessor tr = new DifficultyTableAccessor(tabledir, url);
            TableData td = tr.read();
            if(td != null) {
                write(td);
            }
		});		
	}

	public void loadNewTableData(String[] urls) {
		if (urls == null) {
			return;
		}
		Set<String> localTables = getLocalTableFilenames();
		Arrays.stream(urls).parallel().forEach(url -> {
			if (url == null || url.isBlank() || localTables.contains(getFileName(url) + ".bmt")) {
				return;
			}
            TableAccessor tr = new DifficultyTableAccessor(tabledir, url);
            TableData td = tr.read();
            if(td != null) {
                write(td);
            }
		});		
	}

	private Set<String> getLocalTableFilenames() {
		try (Stream<Path> paths = Files.list(Paths.get(tabledir))) {
			return paths.map(p -> p.getFileName().toString())
					.filter(s -> s.toLowerCase(Locale.ROOT).endsWith(".bmt")).collect(Collectors.toSet());
		} catch (IOException e) {
			return Collections.emptySet();
		}
	}

	public HashMap<String,String> readLocalTableNames(String[] urls) {
		if (urls == null) {
			return new HashMap<>();
		}
		HashMap<String,String> fileNameToTableNameMap = new HashMap<>();
		try (DirectoryStream<Path> paths = Files.newDirectoryStream(Paths.get(tabledir))) {
			for (Path p : paths) {
				String fileName = p.getFileName().toString();
				if (!fileName.toLowerCase(Locale.ROOT).endsWith(".bmt")) continue;
				TableData td = TableData.read(p);
				if (td == null) continue;
				fileNameToTableNameMap.put(fileName, td.getName());
			}
		} catch (IOException e) {
			return null;
		}
		HashMap<String,String> urlToTableNameMap = new HashMap<>();
		for (String url : urls) {
			if (url != null) {
				urlToTableNameMap.put(url, fileNameToTableNameMap.get(getFileName(url) + ".bmt"));
			}
		}
		return urlToTableNameMap;
	}
	
	/**
	 * 難易度表データをキャッシュする
	 * 
	 * @param td 難易度表データ
	 */
	public void write(TableData td) {
		if (td == null) {
			return;
		}
		TableData.write(Paths.get(tabledir + "/" + getFileName(td.getUrl()) + ".bmt"), td);
	}

	public void write(TableData td, String filename) {
		TableData.write(Paths.get(tabledir + "/" + filename), td);
	}

	/**
	 * 全てのキャッシュされた難易度表データを読み込む
	 * 
	 * @return 全てのキャッシュされた難易度表データ
	 */
	public TableData[] readAll() {
		return readAllExcept(null);
	}

	public TableData[] readAllExcept(String filename) {
		try (Stream<Path> paths = Files.list(Paths.get(tabledir))) {
			return paths.filter(p -> !p.getFileName().toString().equals(filename))
					.map(TableData::read).filter(Objects::nonNull).toArray(TableData[]::new);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return new TableData[0];
	}

	/**
	 * 指定のキャッシュされた難易度表データを読み込む
	 * 
	 * @param url 難易度表URL
	 * @return キャッシュされた難易度表データ。存在しない場合はnull
	 */
	public TableData readCache(String url) {
		TableData td = null;
		try (Stream<Path> paths = Files.list(Paths.get(tabledir))) {
			return paths.filter(p -> p.getFileName().toString().equals(getFileName(url) + ".bmt")).findFirst()
					.map(p -> TableData.read(p)).orElse(null);
		} catch (IOException e) {
			return null;
		}
	}
	
	public TableData read(String filename) {
		return TableData.read(Paths.get(tabledir + "/" + filename));
	}

	private String getFileName(String name) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update((name != null ? name : "").getBytes(StandardCharsets.UTF_8));
			return BMSDecoder.convertHexString(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
			return null;
		}

	}

	public static abstract class TableAccessor {

		public final String name;

		public TableAccessor(String name) {
			this.name = name;
		}

		public abstract TableData read();
		public abstract void write(TableData td);
	}

	private enum TableReadMode {
		EXTERNAL_DATA, // 外部譜面データを読み込む / Read external chart data
		HEADER_ONLY    // ヘッダーとコースのみ処理する / Process header and courses only
	}

	public static class DifficultyTableAccessor extends TableAccessor {

		private final String tabledir;
		private final String url;

		public DifficultyTableAccessor(String tabledir, String url) {
			super(url);
			this.tabledir = tabledir;
			this.url = url;
		}

		@Override
		public TableData read() {
			DifficultyTableParser dtp = new DifficultyTableParser();
			DifficultyTable dt = new DifficultyTable();
			try {
				RemoteTableResponse initial = fetchRemote(url);
				URI effectiveSourceUri = null;
				URI effectiveHeaderUri;
				byte[] headerBytes;
				if (looksLikeJson(initial.body)) {
					effectiveHeaderUri = initial.finalUri;
					headerBytes = stripUtf8Bom(initial.body);
				} else {
					String metaContent = extractBmstableMetaContent(initial.body);
					if (metaContent == null) {
						throw new IOException("bmstable meta tag not found in HTML response: " + url);
					}
					String resolvedHeaderUrl = DifficultyTableDataReader.resolveURL(
							initial.finalUri.toASCIIString(), metaContent);
					RemoteTableResponse headerResponse = fetchRemote(resolvedHeaderUrl);
					if (!looksLikeJson(headerResponse.body)) {
						throw new IOException("Header URL did not return JSON: " + resolvedHeaderUrl);
					}
					effectiveSourceUri = initial.finalUri;
					effectiveHeaderUri = headerResponse.finalUri;
					headerBytes = stripUtf8Bom(headerResponse.body);
				}

				// 全域重複除外を抑止し、外部ネットワーク呼び出しを行わずローカル一時ファイルからヘッダーのみ解析する
				// Parse only header and course metadata from bounded local bytes to avoid parser-side unbounded network I/O
				Path tempHeader = Files.createTempFile("beatoraja-table-header-", ".json");
				try {
					Files.write(tempHeader, headerBytes);
					dtp.decodeJSONTableHeader(dt, tempHeader.toFile());
				} finally {
					Files.deleteIfExists(tempHeader);
				}
				dt.setSourceURL(effectiveSourceUri != null ? effectiveSourceUri.toASCIIString() : null);
				dt.setHeadURL(effectiveHeaderUri.toASCIIString());

				TableData td = new TableData();
				td.setUrl(url);
				td.setName(dt.getName());
				td.setTag(dt.getTag());
				Mode defaultMode = dt.getMode() != null ? Mode.getMode(dt.getMode()) : null;

				String[] dataUrls = dt.getDataURL();
				TableReadMode mode = (dataUrls != null && dataUrls.length > 0)
						? TableReadMode.EXTERNAL_DATA
						: TableReadMode.HEADER_ONLY;

				switch (mode) {
					case EXTERNAL_DATA -> {
						DifficultyTableDataReader reader = new DifficultyTableDataReader(dtp);
						List<LoadedTableData> loadedFiles = reader.readAll(dt);

						// 部分成功・全体失敗ポリシー (Partial Success vs Total Failure Policy):
						// 1. 全ての data_url の読み込みに失敗した場合 (loadedFiles.isEmpty()):
						//    既存の有効な .bmt キャッシュを空フォルダで上書き破壊するのを防ぐため、中断 (IOException 送出) する。
						// 2. 一部の data_url のみが成功した場合:
						//    従来のアップストリーム仕様を維持し、読み込めたデータのみでテーブルを構築してキャッシュを更新する。
						//    (利用可能な譜面を保持する利点がある一方、失敗したファイル分のデータが欠けた状態でキャッシュが更新されるリスクを許容)
						// If all data_url reads fail, abort with IOException to preserve existing .bmt cache.
						// If a subset of data_url reads succeed, accept partial data and update cache per upstream practice
						// (preserves available charts, with trade-off of potentially incomplete cache until next refresh).
						if (loadedFiles.isEmpty()) {
							throw new IOException("全ての譜面データファイルの読み込みに失敗しました: " + url);
						}

						TableMembershipResult membership = DifficultyTableMembershipPolicy.resolve(dt, loadedFiles);

						List<TableData.TableFolder> folderList = new ArrayList<>();
						String tagPrefix = td.getTag() != null ? td.getTag() : "";
						for (String lv : membership.getLevels()) {
							List<DifficultyTableElement> dteList = membership.getCharts(lv);
							if (!dteList.isEmpty()) {
								TableData.TableFolder folder = new TableData.TableFolder();
								folder.setName(tagPrefix + lv);
								folder.setSong(dteList.stream()
										.map(dte -> toSongData(dte, defaultMode))
										.toArray(SongData[]::new));
								folderList.add(folder);
							}
						}
						td.setFolder(folderList.toArray(new TableData.TableFolder[0]));
					}
					case HEADER_ONLY -> {
						// 譜面データURLが存在しない場合はヘッダー・コースのみのテーブルとして処理
						// Treat as header/course-only table when no data_url is declared
						td.setFolder(TableData.TableFolder.EMPTY);
					}
				}

				// コースデータの変換処理
				// Course data mapping
				if (dt.getCourse() != null && dt.getCourse().length > 0) {
					td.setCourse(Stream.of(dt.getCourse())
							.filter(Objects::nonNull)
							.flatMap(courses -> Stream.of(courses))
							.filter(Objects::nonNull)
							.map(g -> {
						CourseData cd = new CourseData();
						cd.setName(g.getName());
						if (g.getCharts() != null) {
							cd.setSong(Stream.of(g.getCharts())
									.filter(Objects::nonNull)
									.map(chart -> toSongData(chart, defaultMode))
									.toArray(SongData[]::new));
						}

						if (g.getConstraint() != null) {
							cd.setConstraint(Stream.of(g.getConstraint())
									.filter(Objects::nonNull)
									.map(c -> CourseData.CourseDataConstraint.getValue(c))
									.filter(Objects::nonNull)
									.toArray(CourseData.CourseDataConstraint[]::new));
						}

						if (g.getTrophy() != null) {
							cd.setTrophy(Stream.of(g.getTrophy())
									.filter(Objects::nonNull)
									.map(trophy -> {
								TrophyData t = new TrophyData();
								t.setName(trophy.getName());
								t.setMissrate((float) trophy.getMissrate());
								t.setScorerate((float) trophy.getScorerate());
								return t;
							}).toArray(TrophyData[]::new));
						}
						return cd;
					}).toArray(CourseData[]::new));
				}

				if (!td.validate()) {
					throw new RuntimeException("難易度表の値が不正です: " + url);
				}
				return td;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				Logger.getGlobal().log(Level.WARNING, "難易度表 - " + url + " の読み込みが中断されました", e);
			} catch (Exception e) {
				Logger.getGlobal().log(Level.WARNING, "難易度表 - " + url + " の読み込み失敗: " + e.getMessage(), e);
			}
			return null;
		}

		@Override
		public void write(TableData td) {
			new TableDataAccessor(tabledir).write(td);
		}
	}

// ============================================================================
// 補助データ構造および内部クラス / Helper Data Structures and Classes
// ============================================================================

	/**
	 * ロード済み単一データファイル表現
	 * Representation of a loaded data file.
	 */
	private static class LoadedTableData {
		final String dataUrl;
		final DifficultyTableElement[] elements;

		LoadedTableData(String dataUrl, DifficultyTableElement[] elements) {
			this.dataUrl = dataUrl;
			this.elements = elements;
		}
	}

	/**
	 * 所属計算結果の防御的読み取り専用コンテナ
	 * Defensively copied read-only container of table membership result.
	 */
	public static class TableMembershipResult {
		private final List<String> levels;
		private final Map<String, List<DifficultyTableElement>> chartsByLevel;

		public TableMembershipResult(List<String> levels, Map<String, List<DifficultyTableElement>> chartsByLevel) {
			this.levels = Collections.unmodifiableList(new ArrayList<>(levels));
			Map<String, List<DifficultyTableElement>> copy = new LinkedHashMap<>();
			for (Map.Entry<String, List<DifficultyTableElement>> entry : chartsByLevel.entrySet()) {
				copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
			}
			this.chartsByLevel = Collections.unmodifiableMap(copy);
		}

		public List<String> getLevels() {
			return levels;
		}

		public List<DifficultyTableElement> getCharts(String level) {
			return chartsByLevel.getOrDefault(level, Collections.emptyList());
		}
	}

	/**
	 * 難易度表データファイルの安全な読み込みと I/O エラー隔離を担当
	 * Handles safe reading of data files and isolates file-level I/O errors.
	 *
	 * 部分成功ポリシー (Partial Success Policy):
	 * 複数の data_url のうち一部で読み込み失敗 (404, ネットワーク障害等) が発生しても、
	 * 個別例外をログに警告記録した上で処理を継続し、成功したファイルの一覧を返す。
	 * (If some files fail to load, warnings are logged and reachable files are returned).
	 */
	private static class DifficultyTableDataReader {
		private final DifficultyTableParser parser;

		public DifficultyTableDataReader(DifficultyTableParser parser) {
			this.parser = parser;
		}

		public List<LoadedTableData> readAll(DifficultyTable headerTable) {
			String[] dataUrls = headerTable.getDataURL();
			if (dataUrls == null || dataUrls.length == 0) {
				return Collections.emptyList();
			}
			String base = getEffectiveBaseURL(headerTable.getSourceURL(), headerTable.getHeadURL());
			List<LoadedTableData> loadedList = new ArrayList<>();

			for (String dataUrl : dataUrls) {
				if (dataUrl == null || dataUrl.trim().isEmpty()) {
					continue;
				}
				try {
					String absoluteUrl = resolveURL(base, dataUrl.trim());
					RemoteTableResponse dataResponse = fetchRemote(absoluteUrl);
					if (!looksLikeJson(dataResponse.body)) {
						throw new IOException("Data URL did not return JSON: " + absoluteUrl);
					}
					URL targetUrl = createInMemoryUrl(dataResponse.finalUri, stripUtf8Bom(dataResponse.body));
					DifficultyTable subTable = new DifficultyTable();
					if (headerTable.getMode() != null) {
						subTable.setMode(headerTable.getMode());
					}
					parser.decodeJSONTableData(subTable, targetUrl);

					DifficultyTableElement[] elements = subTable.getElements();
					loadedList.add(new LoadedTableData(dataUrl, elements != null ? elements : new DifficultyTableElement[0]));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					Logger.getGlobal().log(Level.WARNING, "難易度表データ読み込みが中断されました (" + dataUrl + ")", e);
					break;
				} catch (MalformedURLException e) {
					Logger.getGlobal().log(Level.WARNING, "難易度表URL形式が不正です (" + dataUrl + "): " + e.getMessage(), e);
				} catch (IOException | RuntimeException e) {
					Logger.getGlobal().log(Level.WARNING, "難易度表データ読み込み一部失敗 (" + dataUrl + "): " + e.getMessage(), e);
				}
			}
			return loadedList;
		}

		public static String getEffectiveBaseURL(String sourceUrl, String headUrl) {
			if (sourceUrl != null && !sourceUrl.trim().isEmpty()) {
				return resolveURL(sourceUrl.trim(), headUrl != null ? headUrl.trim() : "");
			}
			return headUrl != null ? headUrl.trim() : "";
		}

		@SuppressWarnings("deprecation")
		public static String resolveURL(String base, String rel) {
			if (rel == null || rel.isEmpty()) return base;
			String lower = rel.toLowerCase(Locale.ROOT);
			if (lower.startsWith("http://") || lower.startsWith("https://")) {
				return rel;
			}
			if (EXPLICIT_SCHEME_PATTERN.matcher(rel).find()) {
				return rel;
			}
			if (base != null && !base.isEmpty()) {
				try {
					return new URI(base).resolve(rel).toASCIIString();
				} catch (URISyntaxException | IllegalArgumentException ignored) {
					try {
						return new URL(new URL(base), rel).toExternalForm();
					} catch (MalformedURLException e) {
						// 相対解決失敗時はそのまま返す / Fallback on resolution failure
					}
				}
			}
			return rel;
		}
	}

	private static final class RemoteTableResponse {
		final URI finalUri;
		final byte[] body;

		RemoteTableResponse(URI finalUri, byte[] body) {
			this.finalUri = finalUri;
			this.body = body;
		}
	}

	private static URI validateHttpUri(String rawUrl) throws MalformedURLException {
		if (rawUrl == null || rawUrl.isBlank()) {
			throw new MalformedURLException("Table URL is blank");
		}
		URI uri;
		try {
			uri = new URI(rawUrl.trim());
		} catch (URISyntaxException e) {
			throw new MalformedURLException("Invalid table URI: " + rawUrl);
		}
		String scheme = uri.getScheme();
		if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
			throw new MalformedURLException("Unsupported table URL scheme: " + rawUrl);
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new MalformedURLException("Missing host in table URL: " + rawUrl);
		}
		return uri;
	}

	static RemoteTableResponse fetchRemote(String rawUrl) throws IOException, InterruptedException {
		return fetchRemote(rawUrl, HTTP_REQUEST_TIMEOUT);
	}

	static RemoteTableResponse fetchRemote(String rawUrl, Duration requestTimeout) throws IOException, InterruptedException {
		URI uri = validateHttpUri(rawUrl);
		long timeoutNanos = Math.max(1L, requestTimeout.toNanos());
		long deadlineNanos = System.nanoTime() + timeoutNanos;
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(requestTimeout)
				.header("User-Agent", "beatoraja")
				.GET()
				.build();
		HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream body = response.body()) {
			int status = response.statusCode();
			if (status < 200 || status >= 300) {
				throw new IOException("HTTP " + status + " for " + uri.toASCIIString());
			}
			long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
			if (contentLength > MAX_REMOTE_TABLE_BYTES) {
				throw new IOException("Remote table response exceeds " + MAX_REMOTE_TABLE_BYTES + " bytes: " + uri);
			}
			long remainingNanos = deadlineNanos - System.nanoTime();
			if (remainingNanos <= 0L) {
				throw new HttpTimeoutException("HTTP response body timed out after " + requestTimeout + ": " + uri);
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
						throw new HttpTimeoutException("HTTP response body timed out after " + requestTimeout + ": " + uri);
					}
					total += n;
					if (total > MAX_REMOTE_TABLE_BYTES) {
						throw new IOException("Remote table response exceeds " + MAX_REMOTE_TABLE_BYTES + " bytes: " + uri);
					}
					out.write(buffer, 0, n);
				}
				if (timedOut.get()) {
					throw new HttpTimeoutException("HTTP response body timed out after " + requestTimeout + ": " + uri);
				}
				URI finalUri = response.uri() != null ? response.uri() : uri;
				validateHttpUri(finalUri.toASCIIString());
				return new RemoteTableResponse(finalUri, out.toByteArray());
			} catch (IOException e) {
				if (timedOut.get()) {
					HttpTimeoutException timeoutEx = new HttpTimeoutException(
							"HTTP response body timed out after " + requestTimeout + ": " + uri);
					timeoutEx.initCause(e);
					throw timeoutEx;
				}
				throw e;
			} finally {
				timeoutTask.cancel(false);
			}
		}
	}

	private static boolean looksLikeJson(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			return false;
		}
		int index = 0;
		if (bytes.length >= 3
				&& (bytes[0] & 0xFF) == 0xEF
				&& (bytes[1] & 0xFF) == 0xBB
				&& (bytes[2] & 0xFF) == 0xBF) {
			index = 3;
		}
		while (index < bytes.length) {
			int b = bytes[index] & 0xFF;
			if (b == ' ' || b == '\t' || b == '\r' || b == '\n') {
				index++;
				continue;
			}
			return b == '{' || b == '[';
		}
		return false;
	}

	private static byte[] stripUtf8Bom(byte[] bytes) {
		if (bytes != null && bytes.length >= 3
				&& (bytes[0] & 0xFF) == 0xEF
				&& (bytes[1] & 0xFF) == 0xBB
				&& (bytes[2] & 0xFF) == 0xBF) {
			return Arrays.copyOfRange(bytes, 3, bytes.length);
		}
		return bytes;
	}

	private static String extractBmstableMetaContent(byte[] htmlBytes) {
		if (htmlBytes == null || htmlBytes.length == 0) {
			return null;
		}
		String html = new String(htmlBytes, StandardCharsets.UTF_8);
		Matcher tagMatcher = META_TAG_PATTERN.matcher(html);
		while (tagMatcher.find()) {
			String attrs = tagMatcher.group(1);
			String name = null;
			String content = null;
			Matcher attrMatcher = HTML_ATTR_PATTERN.matcher(attrs);
			while (attrMatcher.find()) {
				String attrName = attrMatcher.group(1);
				String attrValue = attrMatcher.group(2) != null ? attrMatcher.group(2)
						: (attrMatcher.group(3) != null ? attrMatcher.group(3) : attrMatcher.group(4));
				if ("name".equalsIgnoreCase(attrName)) {
					name = attrValue;
				} else if ("content".equalsIgnoreCase(attrName)) {
					content = attrValue;
				}
			}
			if (name != null && "bmstable".equalsIgnoreCase(name.trim())
					&& content != null && !content.trim().isEmpty()) {
				return content.trim().replace("&amp;", "&");
			}
		}
		return null;
	}

	@SuppressWarnings("deprecation")
	private static URL createInMemoryUrl(URI uri, byte[] bytes) throws MalformedURLException {
		return new URL(null, uri.toASCIIString(), new URLStreamHandler() {
			@Override
			protected URLConnection openConnection(URL u) {
				return new URLConnection(u) {
					@Override
					public void connect() {
						connected = true;
					}

					@Override
					public InputStream getInputStream() {
						return new ByteArrayInputStream(bytes);
					}

					@Override
					public long getContentLengthLong() {
						return bytes.length;
					}

					@Override
					public String getContentType() {
						return "application/json; charset=UTF-8";
					}
				};
			}
		});
	}

	/**
	 * フォルダ所属・重複判定・曲順整列のインメモリ計算政策 (Issue #865 中核)
	 * In-memory policy for level membership, deduplication, and song ordering.
	 */
	private static class DifficultyTableMembershipPolicy {

		public static TableMembershipResult resolve(DifficultyTable headerTable, List<LoadedTableData> loadedFiles) {
			Map<String, Map<String, String>> mergeConfigs = headerTable.getMergeConfigurations();
			Map<String, List<DifficultyTableElement>> chartsByLevel = new LinkedHashMap<>();
			Map<String, Set<String>> seenHashesByLevel = new HashMap<>();

			// level_order 明示設定の確認 (存在時はその順序・対象に限定)
			// Check explicit level_order (preserve order and restrict to specified levels if present)
			String[] headerLevels = headerTable.getLevelDescription();
			boolean hasExplicitOrder = (headerLevels != null && headerLevels.length > 0);
			Set<String> explicitLevelSet = new LinkedHashSet<>();
			if (hasExplicitOrder) {
				for (String lv : headerLevels) {
					if (lv != null && !lv.isEmpty()) {
						explicitLevelSet.add(lv);
					}
				}
			}

			Set<String> dynamicLevelSet = new LinkedHashSet<>();

			for (LoadedTableData file : loadedFiles) {
				Map<String, String> levelRules = null;
				if (mergeConfigs != null) {
					levelRules = mergeConfigs.get(file.dataUrl);
					if (levelRules == null) {
						levelRules = mergeConfigs.get(file.dataUrl.trim());
					}
				}

				for (DifficultyTableElement dte : file.elements) {
					String sourceLevel = dte.getLevel();
					String effectiveLevel = sourceLevel;

					// data_rule 適用 (入力要素は変更せず、有効レベル名のみを算出)
					// Apply data_rule (compute effective level without mutating the input element)
					if (levelRules != null && levelRules.containsKey(sourceLevel)) {
						String mapped = levelRules.get(sourceLevel);
						if (mapped == null || mapped.isEmpty()) {
							continue; // この項目を除外 / Drop this entry
						}
						effectiveLevel = mapped;
					}

					if (effectiveLevel == null || effectiveLevel.isEmpty()) {
						continue;
					}

					if (hasExplicitOrder && !explicitLevelSet.contains(effectiveLevel)) {
						continue;
					}

					String md5 = normalizeHash(dte.getMD5());
					String sha256 = normalizeHash(dte.getSHA256());
					if (md5 == null && sha256 == null) {
						continue;
					}

					// 同一フォルダ内のみ重複を除外し、異なるフォルダへの配置は維持する
					// Deduplicate per final level; preserve presence across different folders
					Set<String> seen = seenHashesByLevel.computeIfAbsent(effectiveLevel, k -> new HashSet<>());
					boolean duplicate = (md5 != null && seen.contains("md5:" + md5))
							|| (sha256 != null && seen.contains("sha:" + sha256));

					if (!duplicate) {
						// 受容された要素のハッシュのみを記録し、除外要素によるハッシュ汚染を防ぐ
						// Record hashes of accepted elements only to prevent alias poisoning
						if (md5 != null) seen.add("md5:" + md5);
						if (sha256 != null) seen.add("sha:" + sha256);

						dynamicLevelSet.add(effectiveLevel);
						chartsByLevel.computeIfAbsent(effectiveLevel, k -> new ArrayList<>()).add(dte);
					}
				}
			}

			List<String> effectiveLevels = new ArrayList<>(hasExplicitOrder ? explicitLevelSet : dynamicLevelSet);

			// フォルダ内の曲順は、アップストリーム実装に合わせてタイトル昇順で整列する
			// Sort charts within each folder by title case-insensitively, matching upstream getElements()
			for (List<DifficultyTableElement> list : chartsByLevel.values()) {
				list.sort((a, b) -> Objects.toString(a.getTitle(), "").compareToIgnoreCase(Objects.toString(b.getTitle(), "")));
			}

			return new TableMembershipResult(effectiveLevels, chartsByLevel);
		}

		private static String normalizeHash(String hash) {
			if (hash == null) return null;
			String trimmed = hash.trim().toLowerCase(Locale.ROOT);
			return (trimmed.length() > 10) ? trimmed : null;
		}
	}

	private static SongData toSongData(BMSTableElement te, Mode defaultMode) {
		SongData song = new SongData();
		if(te.getMD5() != null) {
			song.setMd5(te.getMD5().toLowerCase(Locale.ROOT));
		}
		if(te.getSHA256() != null) {
			song.setSha256(te.getSHA256().toLowerCase(Locale.ROOT));
		}
		song.setTitle(te.getTitle());
		song.setArtist(te.getArtist());
		Mode mode = te.getMode() != null ? Mode.getMode(te.getMode()) : null;
		song.setMode(mode != null ? mode.id : (defaultMode != null ? defaultMode.id : 0));
		song.setUrl(te.getURL());
		song.setIpfs(te.getIPFS());
		song.setOrg_md5(te.getParentHash());
		if(te instanceof DifficultyTableElement) {
			DifficultyTableElement dte = (DifficultyTableElement) te;
			song.setAppendurl(dte.getAppendURL());
			song.setAppendIpfs(dte.getAppendIPFS());
		}
		
		return song;
	}
}
