package bms.player.beatoraja.song;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import bms.player.beatoraja.Config.SongArchiveExtractMode;
import bms.player.beatoraja.song.archive.SongArchives;
import bms.player.beatoraja.song.archive.ZipSongArchive;

public final class D1CompatibilityTest {
    private static final String BMS = "#TITLE PMS fixture\n#SUBTITLE Extra\n#ARTIST Unit\n#SUBARTIST Guest\n"
            + "#GENRE Demo\n#BPM 120\n#PLAYER 1\n#WAV01 one.wav\n#00111:01\n";

    public static void main(String[] args) throws Exception {
        Path run = Files.createDirectories(Path.of(args[0]));
        checkDecoder();
        checkZipNames(run);
        checkArchiveRepair(run);
        System.out.println("D1CompatibilityTest passed: charset boundary, canonical names, PMS and BMSON repair");
    }

    private static void checkDecoder() {
        var decoder = new ExtendedBMSDecoder();
        check(decoder.detectEncoding(BMS.getBytes(StandardCharsets.US_ASCII)).equals("UTF-8"), "ASCII charset");
        for (Charset encoding : List.of(StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            byte[] bytes = ("\n \t#0\n" + BMS).getBytes(encoding);
            check(decoder.detectEncoding(bytes).equals(encoding.name()), "UTF-16 header and byte order");
        }
        String afterChannel = BMS + "#CHARSET MS949\n";
        check(decoder.detectEncoding(afterChannel.getBytes(StandardCharsets.US_ASCII)).equals("UTF-8"),
                "late charset directive ignored");
        String beforeChannel = "#CHARSET MS949\n" + BMS;
        check(decoder.detectEncoding(beforeChannel.getBytes(StandardCharsets.US_ASCII)).equals("MS949"),
                "header charset directive honored");
        byte[] kana = BMS.replace("PMS fixture", "ｦ").getBytes(Charset.forName("MS932"));
        check(decoder.detectEncoding(kana).equals("MS932"), "exact MS932 outranks lossy Korean");
        check(!ExtendedBMSDecoder.isLegacyShiftJisMojibake("ｶﾀｶﾅ"), "unmarked half-width kana preserved");
        check(SongUtils.crc32("same", new String[0], "same").equals(SongUtils.crc32("", new String[0], "same")),
                "CRC identical root uses empty relative path");
    }

    private static void checkZipNames(Path run) throws Exception {
        var reader = new ZipSongArchive();
        Path cp949 = run.resolve("korean.zip");
        String korean = "음악/음악.bms";
        try (var zip = new ZipOutputStream(Files.newOutputStream(cp949), Charset.forName("MS949"))) {
            zip.putNextEntry(new ZipEntry(korean));
            zip.write(BMS.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        check(reader.listEntries(cp949).equals(List.of(korean)), "MS949 entry name");
        check(reader.entrySize(cp949, korean) > 0, "MS949 name opens");
        Path unicode = run.resolve("canonical.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(unicode))) {
            zip.putNextEntry(new ZipEntry("cafe\u0301/chart.bms"));
            zip.write(BMS.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        check(reader.listEntries(unicode).equals(List.of("cafe\u0301/chart.bms")), "stored spelling preserved");
        var resource = SongArchives.resource(SongArchives.virtualPath(unicode, "café/chart.bms"));
        check(resource.exists() && resource.size() == BMS.getBytes(StandardCharsets.UTF_8).length, "NFC resource lookup");
        var executor = Executors.newFixedThreadPool(2);
        try {
            int before = SongResources.getMaterializedStatistics().fileCount();
            var first = executor.submit(resource::materialize);
            var second = executor.submit(resource::materialize);
            Path cached = first.get(5, TimeUnit.SECONDS);
            check(cached.equals(second.get(5, TimeUnit.SECONDS)) && Files.readString(cached).equals(BMS),
                    "concurrent requests share the completed materialization");
            check(SongResources.getMaterializedStatistics().fileCount() == before + 1, "one published cache entry");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void checkArchiveRepair(Path run) throws Exception {
        Path root = Files.createDirectories(run.resolve("charts"));
        Path archive = root.resolve("set.zip");
        String bmson = """
                {"version":"1.0.0","info":{"title":"BMSON fixture","subtitle":"Extra","artist":"Unit",
                "subartists":["Guest"],"genre":"Demo","mode_hint":"beat-7k","init_bpm":120,"judge_rank":100,
                "total":100,"resolution":240},"lines":[],"bpm_events":[],"stop_events":[],
                "sound_channels":[{"name":"one.wav","notes":[{"x":1,"y":240,"l":0,"c":false}]}],
                "bga":{"bga_header":[],"bga_events":[],"layer_events":[],"poor_events":[]}}
                """;
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("set/chart.PMS"));
            zip.write(BMS.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("set/chart.BMSON"));
            zip.write(bmson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path db = run.resolve("fixture.db");
        String[] roots = {root.toString()};
        var accessor = new SQLiteSongDatabaseAccessor(db.toString(), roots, null,
                SQLiteSongDatabaseAccessor.SongUpdaterType.BATCHED, true, SongArchiveExtractMode.NO_TEMPORARY_FILES);
        accessor.updateSongDatas(null, roots, false, null);
        SongData pms = song(accessor, "PMS fixture");
        SongData original = song(accessor, "BMSON fixture");
        check(pms.getMode() == 9 && pms.getPath().contains("!-"), "collapsed PMS path and POPN mode");
        check(!original.getSubtitle().isBlank() && !original.getSubartist().isBlank(), "BMSON subtags were scanned");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var update = connection.prepareStatement("UPDATE song SET subtitle = ?, subartist = ?, genre = ? WHERE path = ?")) {
            update.setString(1, "\ufffd");
            update.setString(2, "\ufffd");
            update.setString(3, "User genre");
            update.setString(4, original.getPath());
            check(update.executeUpdate() == 1, "damage seeded in isolated DB");
        }
        check(accessor.rescanCorruptedSongs() == 1, "one BMSON row repaired");
        SongData repaired = song(accessor, "BMSON fixture");
        check(repaired.getSubtitle().equals(original.getSubtitle()) && repaired.getSubartist().equals(original.getSubartist()),
                "BMSON subtags repaired");
        check(repaired.getGenre().equals("User genre") && repaired.getPath().equals(original.getPath())
                && repaired.getSha256().equals(original.getSha256()) && repaired.getCharthash().equals(original.getCharthash()),
                "clean metadata and identity preserved");
        check(accessor.rescanCorruptedSongs() == 0, "repair is idempotent");
    }

    private static SongData song(SQLiteSongDatabaseAccessor db, String title) {
        var result = db.getSongDatas("title", title);
        check(result.length == 1, "expected one row for " + title);
        return result[0];
    }

    private static void check(boolean result, String message) {
        if (!result) {
            throw new AssertionError(message);
        }
    }
}
