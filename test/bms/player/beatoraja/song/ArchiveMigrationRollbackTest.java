package bms.player.beatoraja.song;

import bms.player.beatoraja.Config.SongArchiveExtractMode;
import org.apache.commons.dbutils.QueryRunner;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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

    static Field findField(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new AssertionError("Field not found: " + name);
    }

    static boolean[] failDelete(SQLiteSongDatabaseAccessor db, String table) throws Exception {
        return failDelete(db, table, 1);
    }

    static boolean[] failDelete(SQLiteSongDatabaseAccessor db, String table, int failOnCall) throws Exception {
        Field field = findField(db, "qr");
        QueryRunner real = (QueryRunner) field.get(db);
        boolean[] fired = {false};
        int[] calls = {0};
        field.set(db, new QueryRunner(real.getDataSource()) {
            @Override public int update(Connection c, String sql, Object value) throws SQLException {
                if (sql.startsWith("DELETE FROM " + table + " WHERE path LIKE") && ++calls[0] == failOnCall) {
                    fired[0] = true;
                    throw new SQLException("injected migration " + table + " delete failure");
                }
                return super.update(c, sql, value);
            }
        });
        return fired;
    }

    static boolean[] failCommit(SQLiteSongDatabaseAccessor db) throws Exception {
        Field field = findField(db, "ds");
        org.sqlite.SQLiteDataSource real = (org.sqlite.SQLiteDataSource) field.get(db);
        boolean[] fired = {false};
        org.sqlite.SQLiteDataSource proxyDs = new org.sqlite.SQLiteDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection realConn = real.getConnection();
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class},
                        (cProxy, cMethod, cArgs) -> {
                            if ("commit".equals(cMethod.getName()) && !fired[0]) {
                                fired[0] = true;
                                throw new SQLException("injected outer commit failure");
                            }
                            try {
                                return cMethod.invoke(realConn, cArgs);
                            } catch (InvocationTargetException ite) {
                                throw ite.getCause();
                            }
                        });
            }
        };
        proxyDs.setUrl(real.getUrl());
        field.set(db, proxyDs);
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

    static void testOuterCommitFailure(Path base) throws Exception {
        Path root = Files.createDirectories(base.resolve("outer-commit/root"));
        Path db = base.resolve("outer-commit/song.db"), archive = root.resolve("ecgolb.zip");
        byte[] bms = ("#TITLE xealcu\n#ARTIST mrklcu\n#BPM 120\n#PLAYER 1\n#RANK 2\n#TOTAL 100\n#WAV01 hlvapf.wav\n#00111:01\n").getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(archive))) {
            ZipEntry e = new ZipEntry("ecgolb/hlvapf.bms"); e.setTime(0); z.putNextEntry(e); z.write(bms); z.closeEntry();
        }
        byte[] original = Files.readAllBytes(archive);
        String[] roots = {root.toString()};
        accessor(db, root, SongArchiveExtractMode.NO_TEMPORARY_FILES).updateSongDatas(null, roots, false, null);
        List<String> before = rows(db);
        SQLiteSongDatabaseAccessor migrate = accessor(db, root, SongArchiveExtractMode.SONG_DIRECTORY);
        boolean[] fired = failCommit(migrate);
        migrate.updateSongDatas(null, roots, false, null);
        List<String> after = rows(db);
        check(fired[0], "outer commit fault injected");
        check(before.equals(after), "DB rolled back on outer commit failure");
        check(Arrays.equals(original, Files.readAllBytes(archive)), "moved archive restored on outer commit failure");
        check(!Files.exists(root.resolve("ecgolb")), "expanded dir removed on outer commit failure");
        check(!Files.exists(root.resolve(".beatoraja-archives/ecgolb.zip")), "stored archive removed on outer commit failure");
        System.out.println("PASS outer-commit");
    }

    static void testTwoArchivesPartialScanAbort(Path base) throws Exception {
        Path root = Files.createDirectories(base.resolve("two-archives/root"));
        Path db = base.resolve("two-archives/song.db");
        Path archive1 = root.resolve("a_first.zip");
        Path archive2 = root.resolve("b_second.zip");
        byte[] bms1 = ("#TITLE first_song\n#ARTIST artist1\n#BPM 120\n#PLAYER 1\n#RANK 2\n#TOTAL 100\n#WAV01 a.wav\n#00111:01\n").getBytes(StandardCharsets.UTF_8);
        byte[] bms2 = ("#TITLE second_song\n#ARTIST artist2\n#BPM 120\n#PLAYER 1\n#RANK 2\n#TOTAL 100\n#WAV01 b.wav\n#00111:01\n").getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(archive1))) {
            ZipEntry e = new ZipEntry("a_first/a.bms"); e.setTime(0); z.putNextEntry(e); z.write(bms1); z.closeEntry();
        }
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(archive2))) {
            ZipEntry e = new ZipEntry("b_second/b.bms"); e.setTime(0); z.putNextEntry(e); z.write(bms2); z.closeEntry();
        }
        byte[] orig1 = Files.readAllBytes(archive1);
        byte[] orig2 = Files.readAllBytes(archive2);
        String[] roots = {root.toString()};
        accessor(db, root, SongArchiveExtractMode.NO_TEMPORARY_FILES).updateSongDatas(null, roots, false, null);
        List<String> before = rows(db);
        check(before.stream().anyMatch(x -> x.contains("a_first.zip!")) && before.stream().anyMatch(x -> x.contains("b_second.zip!")), "both virtual rows exist");
        SQLiteSongDatabaseAccessor migrate = accessor(db, root, SongArchiveExtractMode.SONG_DIRECTORY);
        boolean[] fired = failDelete(migrate, "song", 2);
        migrate.updateSongDatas(null, roots, false, null);
        List<String> after = rows(db);
        check(fired[0], "second archive SQL fault injected");
        check(before.equals(after), "partial deletion of first archive must not be committed");
        check(Arrays.equals(orig1, Files.readAllBytes(archive1)), "first archive restored to original path");
        check(!Files.exists(root.resolve("a_first")), "first archive expanded dir cleaned up");
        check(Arrays.equals(orig2, Files.readAllBytes(archive2)), "second archive remains at original path");
        check(!Files.exists(root.resolve("b_second")), "second archive not expanded");
        System.out.println("PASS two-archives");
    }

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.exists(base)) throw new IllegalArgumentException("Use a fresh isolated directory");
        Files.createDirectories(base);
        for (String fault : List.of("song", "folder", "filesystem", "success")) test(base, fault);
        testOuterCommitFailure(base);
        testTwoArchivesPartialScanAbort(base);
        System.out.println("PASS archive migration rollback 6/6");
    }
}
