package bms.player.beatoraja.video;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import bms.player.beatoraja.play.bga.BGAProcessor;
import bms.player.beatoraja.play.bga.BGImageProcessor;
import bms.player.beatoraja.song.SongResource;

/** Headless WP-D3-4 check: video extension matching, BGA lookup, movie byte cap and stream probing. */
public final class VideoBoundaryTest {
    private VideoBoundaryTest() { }

    public static void main(String[] args) throws Exception {
        extensions();
        bgaLookup();
        movieByteLimit();
        streamProbe();
        System.out.println("VideoBoundaryTest passed");
    }

    private static void extensions() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            check(VideoFormat.getFormat("MOVIE.AVI") == VideoFormat.AVI, "Upper-case AVI under the Turkish locale");
        } finally {
            Locale.setDefault(original);
        }
        check(VideoFormat.getFormat("clip.WebM") == VideoFormat.WEBM, "Mixed-case WebM");
        check(VideoFormat.getFormat("bravi") == null, "Dotless name ending in 'avi' is not a movie");
        check(VideoFormat.getFormat("kawaii.png") == null, "Image is not a movie");
        String[] all = VideoFormat.getAllExtensions();
        check(Arrays.asList(all).contains("mp4") && Arrays.stream(all).noneMatch(e -> e.startsWith(".")),
                "getAllExtensions() stays dotless: " + Arrays.toString(all));
        check(Arrays.asList(BGImageProcessor.pic_extension).contains("webp"), "WebP BGA images are searched");
    }

    private static void bgaLookup() throws Exception {
        Method find = BGAProcessor.class.getDeclaredMethod("findIgnoringCase", List.class, String.class);
        find.setAccessible(true);
        List<SongResource> listing = List.of(new Entry("BG.PNG", null, new byte[0]), new Entry("Bg.Mp4", null, new byte[0]),
                new Entry("other.bmp", null, new byte[0]));
        SongResource found = (SongResource) find.invoke(null, listing, "bg");
        check(found != null && found.name().equals("Bg.Mp4"), "Case-only mismatch finds the movie first");
        found = (SongResource) find.invoke(null, List.of(new Entry("BG.PNG", null, new byte[0])), "bg");
        check(found != null && found.name().equals("BG.PNG"), "Case-only mismatch finds the image");
        check(find.invoke(null, listing, "missing") == null, "Missing BGA stays missing");
    }

    private static void movieByteLimit() throws IOException {
        check(FFmpegProcessor.readMovieBytes(new Entry("ok.mp4", null, new byte[10]), 10).length == 10, "At the limit");
        expectRejected(new Entry("big.mp4", null, new byte[11]), "Unknown-size remote movie over the limit");
        expectRejected(new Entry("big.mp4", null, new byte[0]) {
            @Override public long size() { return 100; }
            @Override public InputStream openStream() { throw new AssertionError("Declared size must reject before reading"); }
        }, "Declared remote size over the limit");
        Path local = Path.of("local.mp4");
        check(FFmpegProcessor.readMovieBytes(new Entry("local.mp4", local, new byte[11]), 10).length == 11,
                "Local files keep the existing unbounded read");
    }

    private static void expectRejected(SongResource resource, String label) {
        try {
            FFmpegProcessor.readMovieBytes(resource, 10);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(label + " must be rejected");
    }

    /** Matroska stores no video bitrate, so FFmpeg reports 0 for this valid MPEG-4 stream. */
    private static void streamProbe() throws Exception {
        Path directory = Files.createTempDirectory("d3-video");
        try {
            Path movie = directory.resolve("crf.mkv");
            record(movie, true);
            try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(movie.toFile())) {
                grabber.start();
                System.out.println("VideoBoundaryTest mkv bitrate=" + grabber.getVideoBitrate() + " size="
                        + grabber.getImageWidth() + "x" + grabber.getImageHeight() + " fps=" + grabber.getFrameRate());
                check(FFmpegProcessor.hasPicture(grabber), "Valid MPEG-4 in Matroska has pictures");
                grabber.stop();
            }
            Path audio = directory.resolve("audio.mkv");
            record(audio, false);
            try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(audio.toFile())) {
                grabber.start();
                check(!FFmpegProcessor.hasPicture(grabber), "Audio-only stream has no pictures");
                grabber.stop();
            }
        } finally {
            try (var files = Files.walk(directory)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static void record(Path path, boolean video) throws Exception {
        var recorder = video ? new FFmpegFrameRecorder(path.toFile(), 64, 48) : new FFmpegFrameRecorder(path.toFile(), 2);
        recorder.setFormat("matroska");
        if (video) {
            recorder.setVideoCodec(AV_CODEC_ID_MPEG4);
            recorder.setPixelFormat(AV_PIX_FMT_YUV420P);
            recorder.setFrameRate(20);
        } else {
            recorder.setSampleRate(48_000);
            recorder.setAudioCodec(AV_CODEC_ID_PCM_S16LE);
        }
        try {
            recorder.start();
            if (video) {
                int plane = 64 * 48;
                ByteBuffer data = ByteBuffer.allocateDirect(plane * 3 / 2 + 64);
                for (int i = 0; i < plane * 3 / 2; i++) data.put(i, (byte) (i < plane ? 81 : 128));
                for (int i = 0; i < 6; i++) {
                    data.position(0);
                    recorder.recordImage(64, 48, Frame.DEPTH_UBYTE, 1, 64, AV_PIX_FMT_YUV420P, data);
                }
            } else {
                recorder.recordSamples(48_000, 2, ShortBuffer.allocate(4800 * 2));
            }
            recorder.stop();
        } finally {
            recorder.release();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static class Entry implements SongResource {
        private final String name;
        private final Path local;
        private final byte[] bytes;

        Entry(String name, Path local, byte[] bytes) {
            this.name = name;
            this.local = local;
            this.bytes = bytes;
        }

        @Override public SongResource parent() { return this; }
        @Override public SongResource resolve(String relativePath) { return new Entry(relativePath, null, new byte[0]); }
        @Override public String name() { return name; }
        @Override public String displayPath() { return "memory:/" + name; }
        @Override public String cacheKey() { return displayPath(); }
        @Override public boolean exists() { return true; }
        @Override public boolean isDirectory() { return false; }
        @Override public long size() { return -1; }
        @Override public InputStream openStream() { return new ByteArrayInputStream(bytes); }
        @Override public List<SongResource> list() { return List.of(); }
        @Override public Optional<Path> localPath() { return Optional.ofNullable(local); }
    }
}
