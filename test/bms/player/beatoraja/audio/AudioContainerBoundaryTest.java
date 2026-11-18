package bms.player.beatoraja.audio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import bms.player.beatoraja.song.SongResource;

/**
 * Headless WP-D3-2 check: sibling-extension lookup, container signatures and WAV header boundaries.
 * Run from the project directory; temporary files go under the ignored build-gradle/tmp folder.
 */
public final class AudioContainerBoundaryTest {
    private AudioContainerBoundaryTest() { }

    public static void main(String[] args) throws Exception {
        // 16-bit WAV data is copied into direct buffers through libGDX's JNI BufferUtils.
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        siblingPaths();
        caseInsensitiveResources();
        signatures();
        wavHeaders();
        disguisedContainer();
        System.out.println("AudioContainerBoundaryTest passed");
    }

    private static void siblingPaths() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("build-gradle", "tmp")), "d3-audio");
        check(!root.isAbsolute(), "Sibling lookup must be exercised with a relative path");
        try {
            Path songs = Files.createDirectories(root.resolve("a.b"));
            Files.write(songs.resolve("foo.flac"), new byte[] { 1 });
            Files.write(songs.resolve("bar.wav"), new byte[] { 1 });
            Files.write(songs.resolve("dot.ogg"), new byte[] { 1 });

            Path[] paths = AudioDriver.getPaths(songs.resolve("foo.wav").toString());
            check(paths.length == 1 && paths[0].equals(songs.resolve("foo.flac")),
                    "Relative foo.wav must find its foo.flac sibling: " + Arrays.toString(paths));

            paths = AudioDriver.getPaths(songs.resolve("bar.WAV").toString());
            check(paths.length == 1, "Upper-case extension must not list the same file twice: " + Arrays.toString(paths));

            paths = AudioDriver.getPaths(songs.resolve("dot").toString());
            check(paths.length == 1 && paths[0].equals(songs.resolve("dot.ogg")),
                    "Dots in directory names must not be taken as the extension: " + Arrays.toString(paths));

            check(AudioDriver.getPaths(songs.resolve("missing.wav").toString()).length == 0,
                    "Missing sounds must yield no paths");

            // The cached listing is refreshed when the directory changes.
            check(DirectoryListings.files(songs, "late.ogg").isEmpty(), "Not yet created");
            Thread.sleep(20);
            Files.write(songs.resolve("LATE.OGG"), new byte[] { 1 });
            check(DirectoryListings.files(songs, "late.ogg").size() == 1, "A new file must refresh the cached listing");
        } finally {
            try (Stream<Path> files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** Archive entries are case-sensitive even on Windows. */
    private static void caseInsensitiveResources() {
        MemoryDirectory directory = new MemoryDirectory(Map.of("Kick.OGG", new byte[] { 1 }, "kick.txt", new byte[] { 1 }));
        SongResource[] found = AudioDriver.getResources(directory.resolve("kick.wav"));
        check(found.length == 1 && found[0].name().equals("Kick.OGG"),
                "Case-only mismatch must be found: " + Arrays.toString(Arrays.stream(found).map(SongResource::name).toArray()));
        found = AudioDriver.getResources(directory.resolve("snare.wav"));
        check(found.length == 1 && found[0].name().equals("snare.wav"), "Missing resource falls back to the request");
        AudioDriver.getResources(directory.resolve("hat.wav"));
        check(directory.lists == 1, "Unresolved sounds in one directory must list it once, listed " + directory.lists);
    }

    private static void signatures() {
        check(".wav".equals(PCM.PCMLoader.detectFormat(ascii("RIFF\0\0\0\0WAVEfmt "))), "RIFF/WAVE");
        check(PCM.PCMLoader.detectFormat(ascii("RIFF\0\0\0\0WEBPVP8 ")) == null, "RIFF/WEBP is not audio");
        check(".ogg".equals(PCM.PCMLoader.detectFormat(ascii("OggS\0\2\0\0\0\0\0\0"))), "OggS");
        check(".flac".equals(PCM.PCMLoader.detectFormat(ascii("fLaC\0\0\0\"\0\0\0\0"))), "fLaC");
        check(".mp3".equals(PCM.PCMLoader.detectFormat(ascii("ID3\4\0\0\0\0\0\0\0\0"))), "ID3");
        check(".mp3".equals(PCM.PCMLoader.detectFormat(bytes(0xff, 0xfb, 0x90, 0x64))), "MPEG-1 Layer III sync");
        check(PCM.PCMLoader.detectFormat(bytes(0xff, 0xf9, 0x90, 0x64)) == null, "Reserved layer is not MPEG audio");
        check(PCM.PCMLoader.detectFormat(bytes(0xff, 0xfb, 0xf0, 0x64)) == null, "Bad bitrate index is not MPEG audio");
        check(PCM.PCMLoader.detectFormat(new byte[0]) == null, "Empty header");
    }

    private static void wavHeaders() throws IOException {
        byte[] data = bytes(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x08);
        // Odd LIST chunk with and without its pad byte.
        expectSamples(wav(1, 1, 44100, 16, chunk("LIST", ascii("abc"), true), 8, data), 8, "Padded odd chunk");
        expectSamples(wav(1, 1, 44100, 16, chunk("LIST", ascii("abc"), false), 8, data), 8, "Unpadded odd chunk");
        // Streaming writers leave 0xFFFFFFFF as the data size.
        expectSamples(wav(1, 2, 44100, 16, new byte[0], -1, data), 8, "Unknown data length");
        // Truncated data: the declared length is only a hint.
        expectSamples(wav(1, 1, 44100, 16, new byte[0], 1_000_000, data), 8, "Truncated data chunk");
        expectSamples(wav(1, 1, 44100, 8, new byte[0], 8, data), 8, "8-bit data");
        expectFailure(wav(1, 0, 44100, 16, new byte[0], 8, data), "Zero channels");
        expectFailure(wav(1, 2, 0, 16, new byte[0], 8, data), "Zero sample rate");
        expectFailure(wav(1, 2, 44100, 0, new byte[0], 8, data), "Zero bits per sample");
        expectFailure(wav(1, 2, 44100, 12, new byte[0], 8, data), "Unsupported bits per sample");
        expectFailure(Arrays.copyOf(wav(1, 1, 44100, 16, new byte[0], 8, data), 30), "Header cut inside fmt");
    }

    private static void disguisedContainer() {
        byte[] wav = wav(1, 1, 44100, 16, new byte[0], 8, bytes(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x08));
        MemoryDirectory directory = new MemoryDirectory(Map.of("hit.ogg", wav, "noise.ogg", ascii("not audio at all")));
        PCM<?> pcm = PCM.load(directory.resolve("hit.ogg"), null);
        check(pcm != null && pcm.channels == 1 && pcm.sampleRate == 44100, "WAV named .ogg must load by signature");
        check(PCM.load(directory.resolve("noise.ogg"), null) == null, "Unknown content must still fail cleanly");
    }

    private static void expectSamples(byte[] wav, int bytes, String label) throws IOException {
        PCM.PCMLoader loader = new PCM.PCMLoader(null);
        loader.loadPCM(new ByteArrayInputStream(wav), "case.wav");
        byte[] actual = new byte[loader.pcm.limit()];
        loader.pcm.get(0, actual);
        check(actual.length == bytes, label + ": expected " + bytes + " bytes but was " + Arrays.toString(actual)
                + " capacity=" + loader.pcm.capacity());
    }

    private static void expectFailure(byte[] wav, String label) {
        try {
            new PCM.PCMLoader(null).loadPCM(new ByteArrayInputStream(wav), "case.wav");
        } catch (IOException expected) {
            return;
        } catch (RuntimeException e) {
            throw new AssertionError(label + " must fail with IOException, not " + e, e);
        }
        throw new AssertionError(label + " must be rejected");
    }

    private static byte[] wav(int type, int channels, int rate, int bits, byte[] extra, int dataLength, byte[] data) {
        ByteArrayOutputStream fmt = new ByteArrayOutputStream();
        le16(fmt, type);
        le16(fmt, channels);
        le32(fmt, rate);
        le32(fmt, rate * Math.max(1, channels) * Math.max(8, bits) / 8);
        le16(fmt, Math.max(1, channels) * Math.max(8, bits) / 8);
        le16(fmt, bits);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(ascii("WAVE"));
        body.writeBytes(chunk("fmt ", fmt.toByteArray(), true));
        body.writeBytes(extra);
        body.writeBytes(ascii("data"));
        le32(body, dataLength);
        body.writeBytes(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii("RIFF"));
        le32(out, body.size());
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    private static byte[] chunk(String id, byte[] payload, boolean pad) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii(id));
        le32(out, payload.length);
        out.writeBytes(payload);
        if (pad && (payload.length & 1) == 1) out.write(0);
        return out.toByteArray();
    }

    private static void le16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write(value >>> 8 & 0xff);
    }

    private static void le32(ByteArrayOutputStream out, int value) {
        le16(out, value & 0xffff);
        le16(out, value >>> 16);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Case-sensitive in-memory directory, like an archive. */
    private static final class MemoryDirectory implements SongResource {
        private final Map<String, byte[]> files;
        int lists;

        MemoryDirectory(Map<String, byte[]> files) {
            this.files = files;
        }

        @Override public SongResource parent() { return this; }
        @Override public SongResource resolve(String relativePath) { return new MemoryFile(this, relativePath); }
        @Override public String name() { return ""; }
        @Override public String displayPath() { return "memory:/"; }
        // Unique like a real archive path, so separate directories never share a cached listing.
        @Override public String cacheKey() { return "memory:" + System.identityHashCode(this) + "/"; }
        @Override public boolean exists() { return true; }
        @Override public boolean isDirectory() { return true; }
        @Override public long size() { return 0; }
        @Override public InputStream openStream() throws IOException { throw new IOException("directory"); }
        @Override public List<SongResource> list() {
            lists++;
            List<SongResource> children = new ArrayList<>();
            files.keySet().forEach(name -> children.add(new MemoryFile(this, name)));
            return children;
        }
    }

    private record MemoryFile(MemoryDirectory directory, String name) implements SongResource {
        @Override public SongResource parent() { return directory; }
        @Override public SongResource resolve(String relativePath) { return directory.resolve(relativePath); }
        @Override public String displayPath() { return "memory:/" + name; }
        @Override public String cacheKey() { return displayPath(); }
        @Override public boolean exists() { return directory.files.containsKey(name); }
        @Override public boolean isDirectory() { return false; }
        @Override public long size() { return exists() ? directory.files.get(name).length : 0; }
        @Override public InputStream openStream() throws IOException {
            if (!exists()) throw new IOException("missing " + name);
            return new ByteArrayInputStream(directory.files.get(name));
        }
        @Override public List<SongResource> list() { return List.of(); }
    }
}
