package bms.player.beatoraja;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
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
	
	private final String tabledir;

	public TableDataAccessor(String tabledir) {
		this.tabledir = tabledir;
	}

	public void updateTableData(String[] urls) {
		Arrays.stream(urls).parallel().forEach(url -> {
            TableAccessor tr = new DifficultyTableAccessor(tabledir, url);
            TableData td = tr.read();
            if(td != null) {
                write(td);
            }
		});		
	}

	public void loadNewTableData(String[] urls) {
		Set<String> localTables = getLocalTableFilenames();
		Arrays.stream(urls).parallel().forEach(url -> {
			if (localTables.contains(getFileName(url) + ".bmt")) {
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
					.filter(s -> s.toLowerCase().endsWith(".bmt")).collect(Collectors.toSet());
		} catch (IOException e) {
			return null;
		}
	}

	public HashMap<String,String> readLocalTableNames(String[] urls) {
		HashMap<String,String> fileNameToTableNameMap = new HashMap<>();
		try (DirectoryStream<Path> paths = Files.newDirectoryStream(Paths.get(tabledir))) {
			for (Path p : paths) {
				String fileName = p.getFileName().toString();
				if (!fileName.endsWith(".bmt")) continue;
				TableData td = TableData.read(p);
				if (td == null) continue;
				fileNameToTableNameMap.put(fileName, td.getName());
			}
		} catch (IOException e) {
			return null;
		}
		HashMap<String,String> urlToTableNameMap = new HashMap<>();
		for (String url : urls) {
			urlToTableNameMap.put(url, fileNameToTableNameMap.get(getFileName(url) + ".bmt"));
		}
		return urlToTableNameMap;
	}
	
	/**
	 * 難易度表データをキャッシュする
	 * 
	 * @param td 難易度表データ
	 */
	public void write(TableData td) {
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
			digest.update(name.getBytes());
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
			if (url.endsWith(".json")) {
				dt.setHeadURL(url);
			} else {
				dt.setSourceURL(url);
			}
			try {
				// 全域重複除外を抑止するため、ヘッダーとコースのみ読み込む
				// Parse only header and course metadata to prevent global chart deduplication
				dtp.decode(false, dt);

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
					td.setCourse(Stream.of(dt.getCourse()).flatMap(courses -> Stream.of(courses)).map(g -> {
						CourseData cd = new CourseData();
						cd.setName(g.getName());
						cd.setSong(Stream.of(g.getCharts()).map(chart -> toSongData(chart, defaultMode))
								.toArray(SongData[]::new));

						cd.setConstraint(Stream.of(g.getConstraint()).map(c -> CourseData.CourseDataConstraint.getValue(c))
								.filter(Objects::nonNull).toArray(CourseData.CourseDataConstraint[]::new));

						if (g.getTrophy() != null) {
							cd.setTrophy(Stream.of(g.getTrophy()).map(trophy -> {
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
			} catch (Throwable e) {
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
					URL targetUrl = new URL(absoluteUrl);
					DifficultyTable subTable = new DifficultyTable();
					if (headerTable.getMode() != null) {
						subTable.setMode(headerTable.getMode());
					}
					parser.decodeJSONTableData(subTable, targetUrl);

					DifficultyTableElement[] elements = subTable.getElements();
					loadedList.add(new LoadedTableData(dataUrl, elements != null ? elements : new DifficultyTableElement[0]));
				} catch (MalformedURLException e) {
					Logger.getGlobal().log(Level.WARNING, "難易度表URL形式が不正です (" + dataUrl + "): " + e.getMessage(), e);
				} catch (IOException e) {
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

		public static String resolveURL(String base, String rel) {
			if (rel == null || rel.isEmpty()) return base;
			if (rel.startsWith("http://") || rel.startsWith("https://") || rel.startsWith("file:")) {
				return rel;
			}
			try {
				if (base != null && !base.isEmpty()) {
					return new URL(new URL(base), rel).toExternalForm();
				}
			} catch (MalformedURLException e) {
				// 相対解決失敗時はそのまま返す / Fallback on resolution failure
			}
			return rel;
		}
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
			song.setMd5(te.getMD5().toLowerCase());
		}
		if(te.getSHA256() != null) {
			song.setSha256(te.getSHA256().toLowerCase());
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
