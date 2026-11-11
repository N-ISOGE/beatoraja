package bms.player.beatoraja.song;

/**
 * 楽曲データベースへのアクセスインターフェイス
 * 
 * @author exch
 */
public interface SongDatabaseAccessor {

	/**
	 * 楽曲を取得する
	 * 
	 * @param key
	 *            属性
	 * @param value
	 *            属性値
	 * @return 検索結果
	 */
	public SongData[] getSongDatas(String key, String value);

	/**
	 * MD5/SHA256で指定した楽曲をまとめて取得する
	 * 
	 * @param hashes
	 *            楽曲のハッシュ
	 * @return
	 */
	public SongData[] getSongDatas(String[] hashes);

	/**
	 * スコアデータベース、スコアログデータベース、譜面情報データベースを跨いでSQLで問い合わせを行う
	 * 
	 * @param sql
	 *            SQL
	 * @param score
	 *            スコアデータベースのパス
	 * @param scorelog
	 *            スコアログデータベースのパス
	 * @param info
	 *            譜面情報データベースのパス
	 * @return
	 */
	public SongData[] getSongDatas(String sql, String score, String scorelog, String info);

	public void setSongDatas(SongData[] songs);

	public default void setSongReviews(SongData[] songs) {
		setSongDatas(songs);
	}

	/** Updates the current player's review level without modifying song metadata. */
	default boolean setSongReviewLevel(SongData song, double level) {
		return false;
	}

	default SongReview getSongReview(String sha256) {
		return null;
	}

	public SongData[] getSongDatasByText(String text);

	/**
	 * 楽曲を取得する
	 * 
	 * @param key
	 *            属性
	 * @param value
	 *            属性値
	 * @return 検索結果
	 */
	public FolderData[] getFolderDatas(String key, String value);

	/**
	 * データベースを更新する
	 * 
	 * @param updatepath
	 *            更新するフォルダのパス。全更新する場合はnull
	 * @param updateAll
	 *            更新の必要がないものも更新するかどうか
	 */
	public void updateSongDatas(String updatepath, String[] bmsroot, boolean updateAll, SongInformationAccessor info);

	/**
	 * 楽曲データのメタデータ（タイトル、アーティスト、ジャンル等）が破損（Shift_JISモジバケ等）しているかを判定します。
	 * Checks whether the song metadata contains corrupted encoding (such as legacy Shift_JIS mojibake).
	 *
	 * @param song 検査対象の楽曲データ
	 * @return 破損が検出された場合true
	 */
	public default boolean isCorruptedMetadata(SongData song) {
		return false;
	}

	/**
	 * データベース内の破損したメタデータを持つ楽曲リストを検索・取得します。
	 * Searches and returns a list of songs with corrupted metadata from the database.
	 *
	 * @return 破損が検出された楽曲リスト
	 */
	public default java.util.List<SongData> findCorruptedSongs() {
		return java.util.Collections.emptyList();
	}

	/**
	 * 破損したメタデータを持つ楽曲を再スキャンし、DBスキーマおよびハッシュを変更せずに修復します。
	 * Safely rescans songs with corrupted metadata without altering DB schema or hash invariants.
	 *
	 * @return 修復された楽曲数
	 */
	public default int rescanCorruptedSongs() {
		return 0;
	}

}
