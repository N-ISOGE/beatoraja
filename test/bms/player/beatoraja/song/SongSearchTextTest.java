package bms.player.beatoraja.song;

import bms.player.beatoraja.Config.SongArchiveExtractMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** 生成した譜面と一時ディレクトリのsong.dbだけを使う、検索文字列とパス接頭辞の回帰試験。
 * Search text and path prefix regression; uses generated charts and a song.db in a temporary directory only. */
public final class SongSearchTextTest {
    // One Hangul syllable and its canonical decomposition (three jamo).
    private static final String NFC = "한", NFD = java.text.Normalizer.normalize(NFC, java.text.Normalizer.Form.NFD);

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void chart(Path directory, String name) throws Exception {
        Files.createDirectories(directory);
        Files.write(directory.resolve(name + ".bms"), ("#TITLE " + name + "\n#BPM 120\n#PLAYER 1\n#WAV01 " + name
                + ".wav\n#00111:01\n").getBytes(StandardCharsets.US_ASCII));
    }

    private static void retitle(Connection connection, String from, String to) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE song SET title = ? WHERE title = ?")) {
            statement.setString(1, to);
            statement.setString(2, from);
            check(statement.executeUpdate() == 1, "retitle " + from);
        }
    }

    public static void main(String[] args) throws Exception {
        check(NFC.length() == 1 && NFD.length() == 3, "test strings are composed and decomposed");
        Path base = Files.createTempDirectory("song-search-text-").toAbsolutePath().normalize();
        Path kept = base.resolve("lib_a"), dropped = base.resolve("libxa"), db = base.resolve("song.db");
        for (String name : new String[]{"qkzpwv", "htmrlx", "wdnyfc", "bjsgvu", "pxekzo", "rmvtya", "gouqns"}) chart(kept, name);
        chart(dropped, "zzuqtn");
        String[] both = {kept.toString(), dropped.toString()};
        SQLiteSongDatabaseAccessor accessor = new SQLiteSongDatabaseAccessor(db.toString(), both, null,
                SQLiteSongDatabaseAccessor.SongUpdaterType.BATCHED, true, SongArchiveExtractMode.NO_TEMPORARY_FILES);
        accessor.updateSongDatas(null, both, false, null);
        check(accessor.getSongDatasByText("zzuqtn").length == 1 && accessor.getSongDatasByText("qkzpwv").length == 1, "initial scan");

        // '_' in a root path is not a wildcard: removing the root "libxa" must drop its rows although "lib_a" remains.
        accessor.updateSongDatas(null, new String[]{kept.toString()}, false, null);
        check(accessor.getSongDatasByText("zzuqtn").length == 0, "rows of a removed root are deleted when another root contains '_'");
        check(accessor.getSongDatasByText("qkzpwv").length == 1, "rows of the remaining root are kept");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            retitle(connection, "qkzpwv", "50%qkzpwv");
            retitle(connection, "htmrlx", "50xhtmrlx");
            retitle(connection, "wdnyfc", "a_wdnyfc");
            retitle(connection, "bjsgvu", "axbjsgvu");
            retitle(connection, "pxekzo", "px\\ekzo");
            retitle(connection, "rmvtya", NFC + "rmvtya");
            retitle(connection, "gouqns", NFD + "gouqns");
            try (PreparedStatement statement = connection.prepareStatement("UPDATE song SET subtitle = NULL WHERE title = '50%qkzpwv'")) {
                check(statement.executeUpdate() == 1, "null subtitle");
            }
        }
        check(accessor.getSongDatasByText("50%").length == 1, "'%' is literal");
        check(accessor.getSongDatasByText("a_").length == 1, "'_' is literal");
        check(accessor.getSongDatasByText("\\").length == 1, "'\\' is literal");
        check(accessor.getSongDatasByText("50").length == 2, "plain text still matches as a substring");
        check(accessor.getSongDatasByText("50%qkzpwv").length == 1, "a NULL column does not hide the row");
        check(accessor.getSongDatasByText(NFC).length == 2, "composed search text matches both forms");
        check(accessor.getSongDatasByText(NFD).length == 2, "decomposed search text matches both forms");
        check(accessor.getSongDatasByText(NFC + "50%").length == 0, "'%' stays literal in the non-ASCII path");
        check(accessor.getSongDatasByText(null).length == 0, "null search text");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rows = connection.createStatement().executeQuery("SELECT title FROM song WHERE title LIKE '%gouqns'")) {
            check(rows.next() && (NFD + "gouqns").equals(rows.getString(1)), "stored title is not normalized");
        }
        System.out.println("SongSearchTextTest passed (literal % _ \\, NFC/NFD, NULL column, root path with _)");
    }
}
