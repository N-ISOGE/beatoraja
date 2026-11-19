package bms.player.beatoraja.song;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Arrays;

/** タイトル変更を条件とせず、隔離データの破損項目を個別に修復する。
 * Repair one damaged field without requiring a title change, using isolated data only. */
public final class MetadataRepairTest {
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]).toAbsolutePath();
        if (Files.exists(base)) throw new IllegalArgumentException("Use a fresh test directory");
        Path root = Files.createDirectories(base.resolve("charts"));
        Path chart = root.resolve("psutjf.bms"), db = base.resolve("song.db");
        byte[] bytes = ("#TITLE zsohgd\n#ARTIST oacsvd\n#GENRE ngrajo\n#BPM 120\n#PLAYER 1\n#RANK 2\n#TOTAL 100\n#WAV01 pdfwbr.wav\n#00111:01\n").getBytes(StandardCharsets.UTF_8);
        Files.write(chart, bytes);
        SQLiteSongDatabaseAccessor accessor = new SQLiteSongDatabaseAccessor(db.toString(), new String[]{root.toString()}, null);
        accessor.updateSongDatas(null, new String[]{root.toString()}, false, null);
        SongData original = accessor.getSongDatas("title", "zsohgd")[0];
        for (String field : new String[]{"artist", "genre", "title"}) {
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE song SET " + field + " = 'broken\ufffd'");
            }
            if (accessor.findCorruptedSongs().size() != 1) throw new AssertionError("detect " + field);
            if (accessor.rescanCorruptedSongs() != 1) throw new AssertionError("repair " + field);
            SongData fixed = accessor.getSongDatas("title", "zsohgd")[0];
            if (!"oacsvd".equals(fixed.getArtist()) || !"ngrajo".equals(fixed.getGenre())) throw new AssertionError(field);
            if (!original.getSha256().equals(fixed.getSha256()) || !original.getCharthash().equals(fixed.getCharthash())) throw new AssertionError("identity");
            if (accessor.rescanCorruptedSongs() != 0 || !Arrays.equals(bytes, Files.readAllBytes(chart))) throw new AssertionError("idempotence/original");
            System.out.println("PASS " + field + " repair, identity, idempotence, original bytes");
        }
        System.out.println("PASS metadata repair 3/3");
    }
}
