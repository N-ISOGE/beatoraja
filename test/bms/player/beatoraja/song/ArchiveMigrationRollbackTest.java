package bms.player.beatoraja.song;

import bms.player.beatoraja.Config.SongArchiveExtractMode;
import org.apache.commons.dbutils.QueryRunner;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.zip.*;

/** 新しい隔離ディレクトリだけに書き込むヘッドレス移行試験。
 * Headless migration regression; all writes stay in a fresh isolated directory. */
public final class ArchiveMigrationRollbackTest {
    static SQLiteSongDatabaseAccessor accessor(Path db, Path root, SongArchiveExtractMode mode) throws Exception {
        return new SQLiteSongDatabaseAccessor(db.toString(), new String[]{root.toString()}, null,
                SQLiteSongDatabaseAccessor.SongUpdaterType.BATCHED, true, mode);
    }

    static List<String> rows(Path db) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            for (String table : List.of("song", "folder")) {
                try (ResultSet r = s.executeQuery("SELECT path FROM " + table + " ORDER BY path")) {
                    while (r.next()) rows.add(table + ":" + r.getString(1));
                }
            }
            try (ResultSet r = s.executeQuery("PRAGMA integrity_check")) {
                check(r.next() && "ok".equals(r.getString(1)), "integrity");
            }
        }
        return rows;
    }

    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static boolean[] failDelete(SQLiteSongDatabaseAccessor db, String table) throws Exception {
        Field field = null;
        for (Class<?> c = db.getClass(); c != null; c = c.getSuperclass()) {
            try { field = c.getDeclaredField("qr"); break; } catch (NoSuchFieldException ignored) { }
        }
        if (field == null) throw new AssertionError("QueryRunner not found");
        field.setAccessible(true);
        QueryRunner real = (QueryRunner) field.get(db);
        boolean[] fired = {false};
        field.set(db, new QueryRunner(real.getDataSource()) {
            @Override public int update(Connection c, String sql, Object value) throws SQLException {
                if (!fired[0] && sql.startsWith("DELETE FROM " + table + " WHERE path LIKE")) {
                    fired[0] = true;
                    throw new SQLException("injected migration " + table + " delete failure");
                }
                return super.update(c, sql, value);
            }
        });
        return fired;
    }

    static void test(Path base, String fault) throws Exception {
        Path root = Files.createDirectories(base.resolve(fault + "/root"));
        Path db = base.resolve(fault + "/song.db"), archive = root.resolve("ecgolb.zip");
        byte[] bms = ("#TITLE xealcu\n#ARTIST mrklcu\n#BPM 120\n#PLAYER 1\n#RANK 2\n#TOTAL 100\n#WAV01 hlvapf.wav\n#00111:01\n").getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(archive))) {
            ZipEntry e = new ZipEntry("ecgolb/hlvapf.bms"); e.setTime(0); z.putNextEntry(e); z.write(bms); z.closeEntry();
        }
        byte[] original = Files.readAllBytes(archive);
        String[] roots = {root.toString()};
        accessor(db, root, SongArchiveExtractMode.NO_TEMPORARY_FILES).updateSongDatas(null, roots, false, null);
        List<String> before = rows(db);
        check(before.stream().anyMatch(x -> x.contains("ecgolb.zip!")), "virtual row exists");
        SQLiteSongDatabaseAccessor migrate = accessor(db, root, SongArchiveExtractMode.SONG_DIRECTORY);
        boolean[] fired = null;
        if (fault.equals("song") || fault.equals("folder")) fired = failDelete(migrate, fault);
        if (fault.equals("filesystem")) {
            Files.createDirectory(root.resolve("ecgolb"));
            Files.writeString(root.resolve("ecgolb/keep.txt"), "keep");
        }
        migrate.updateSongDatas(null, roots, false, null);
        List<String> after = rows(db);
        if (fault.equals("success")) {
            check(!Files.exists(archive), "archive moved");
            check(Arrays.equals(original, Files.readAllBytes(root.resolve(".beatoraja-archives/ecgolb.zip"))), "stored archive bytes");
            check(Arrays.equals(bms, Files.readAllBytes(root.resolve("ecgolb/hlvapf.bms"))), "chart bytes");
            check(after.stream().noneMatch(x -> x.contains("ecgolb.zip!")), "no stale virtual row");
            SongData[] first = migrate.getSongDatas("title", "xealcu");
            check(first.length == 1, "one imported song");
            String identity = first[0].getSha256(), chart = first[0].getCharthash();
            migrate.updateSongDatas(null, roots, true, null);
            SongData[] second = migrate.getSongDatas("title", "xealcu");
            check(second.length == 1 && identity.equals(second[0].getSha256()) && chart.equals(second[0].getCharthash()), "repeat import identity");
        } else {
            check(Arrays.equals(original, Files.readAllBytes(archive)), "archive stays at original path");
            if (fired != null) {
                check(fired[0], "fault injected");
                check(before.equals(after), "song and folder deletions rolled back");
                check(!Files.exists(root.resolve("ecgolb")), "no extracted directory after SQL fault");
            } else {
                check(after.containsAll(before), "virtual rows retained after filesystem failure");
                check("keep".equals(Files.readString(root.resolve("ecgolb/keep.txt"))), "existing destination preserved");
            }
        }
        System.out.println("PASS " + fault);
    }

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.exists(base)) throw new IllegalArgumentException("Use a fresh isolated directory");
        Files.createDirectories(base);
        for (String fault : List.of("song", "folder", "filesystem", "success")) test(base, fault);
        System.out.println("PASS archive migration rollback 4/4");
    }
}
