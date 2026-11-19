package bms.tool.mdprocessor;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

final class IpfsTransfer {
    private static final Logger LOG = Logger.getLogger(IpfsTransfer.class.getName());
    private static final String DEFAULT_ENDPOINT = "https://gateway.ipfs.io/";
    private static final long MAX_RESPONSE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 16_384;
    private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";

    private final String endpoint;
    private final Path root;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    IpfsTransfer(String endpoint, Path root) {
        this.endpoint = endpoint;
        this.root = root.toAbsolutePath().normalize();
    }

    static String reference(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return null;
        }
        String ref = value.strip();
        if (ref.length() > 2048) {
            throw new IOException("IPFS path is too long");
        }
        String lower = ref.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/ipfs/") || lower.startsWith("ipfs://")) {
            ref = ref.substring(lower.startsWith("/ipfs/") ? 6 : 7);
        }
        String[] parts = ref.split("/", -1);
        String cid = parts[0];
        if (!validCid(cid)) {
            throw new IOException("Invalid IPFS content identifier");
        }
        for (int i = 1; i < parts.length; i++) {
            if (!validName(parts[i]) || parts[i].matches(".*[%?#].*")) {
                throw new IOException("Invalid IPFS relative path");
            }
        }
        if (cid.charAt(0) == 'B') {
            return cid.toLowerCase(Locale.ROOT) + ref.substring(cid.length());
        }
        return ref;
    }

    private static boolean validCid(String cid) {
        if (cid.length() == 46 && cid.startsWith("Qm")) {
            BigInteger value = BigInteger.ZERO;
            for (int i = 0; i < cid.length(); i++) {
                int digit = BASE58.indexOf(cid.charAt(i));
                if (digit < 0) {
                    return false;
                }
                value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
            }
            byte[] hash = value.toByteArray();
            return hash.length == 34 && hash[0] == 0x12 && hash[1] == 0x20;
        }
        if (!cid.matches("b[a-z2-7]{7,127}|B[A-Z2-7]{7,127}")) {
            return false;
        }
        String encoded = cid.toLowerCase(Locale.ROOT);
        byte[] decoded = new byte[(encoded.length() - 1) * 5 / 8];
        int bits = 0;
        int buffer = 0;
        int offset = 0;
        for (int i = 1; i < encoded.length(); i++) {
            buffer = (buffer << 5) | BASE32.indexOf(encoded.charAt(i));
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                decoded[offset++] = (byte) (buffer >> bits);
            }
        }
        if (bits >= 5 || (buffer & ((1 << bits) - 1)) != 0) {
            return false;
        }
        ByteBuffer data = ByteBuffer.wrap(decoded);
        long version = varint(data);
        long codec = varint(data);
        long code = varint(data);
        long length = varint(data);
        return version == 1 && codec > 0 && code >= 0 && length > 0 && length == data.remaining();
    }

    private static long varint(ByteBuffer data) {
        long value = 0;
        for (int shift = 0; data.hasRemaining() && shift < 63; shift += 7) {
            int element = data.get() & 0xff;
            value |= (long) (element & 0x7f) << shift;
            if ((element & 0x80) == 0) {
                return shift == 0 || element != 0 ? value : -1;
            }
        }
        return -1;
    }

    Path install(String base, String diff, String artist, String title, Path parent, Publisher publish) throws IOException {
        if (base == null && parent == null) {
            throw new IOException("The base chart for this IPFS update is unavailable");
        }
        Files.createDirectories(root);
        Path realRoot = realManagedRoot(root);
        String label = String.join(" ", artist == null ? "" : artist, title == null ? "" : title)
                .replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_").strip()
                .replaceFirst("^[. ]+", "").replaceFirst("[. ]+$", "");
        int labelEnd = label.offsetByCodePoints(0, Math.min(48, label.codePointCount(0, label.length())));
        if (!label.isEmpty()) {
            label = label.substring(0, labelEnd);
        }
        String fingerprint = digest(base + "\n" + diff + "\n" + (parent == null ? "" : parent.toRealPath()));
        String directoryName = (label.isEmpty() ? "song" : label) + " [" + fingerprint + "]";
        if (!validName(directoryName)) {
            directoryName = "song [" + fingerprint + "]";
        }
        Path target = realRoot.resolve(directoryName);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("IPFS destination is unavailable");
            }
            return target;
        }
        Path staging = Files.createTempDirectory(realRoot, ".download-");
        Throwable failure = null;
        try {
            Path content = Files.createDirectory(staging.resolve("content"));
            Budget output = new Budget();
            if (parent == null) {
                materialize(base, staging, content, output);
            } else {
                Path source = parent.toRealPath();
                if (staging.startsWith(source)) {
                    throw new IOException("The base chart folder contains the IPFS staging folder");
                }
                copyTree(source, content, output);
            }
            if (diff != null) {
                materialize(diff, staging, content, output);
            }
            try (var files = Files.find(content, 32, (path, attrs) ->
                    attrs.isRegularFile() && isChartName(path.getFileName().toString()))) {
                if (files.noneMatch(path -> isVisible(content.relativize(path)))) {
                    throw new IOException("IPFS download contains no chart file");
                }
            }
            publish.move(content, target);
            return target;
        } catch (IOException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try {
                removeStaging(staging);
            } catch (IOException e) {
                if (failure != null) {
                    failure.addSuppressed(e);
                } else {
                    LOG.log(Level.WARNING, "Unable to remove IPFS staging directory", e);
                }
            }
        }
    }

    static Path realManagedRoot(Path directory) throws IOException {
        Path real = directory.toRealPath();
        Path expected = directory.getParent().toRealPath().resolve(directory.getFileName());
        if (!real.equals(expected) || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new IOException("IPFS download directory must be a local directory");
        }
        return real;
    }

    @FunctionalInterface
    interface Publisher {
        void move(Path staged, Path target) throws IOException;
    }

    private static String digest(String key) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void materialize(String ref, Path staging, Path content, Budget output) throws IOException {
        Path work = Files.createTempDirectory(staging, "object-");
        Path payload = work.resolve("response");
        String type = fetch(ref, payload);
        if (type.startsWith("application/vnd.ipld.")) {
            throw new IOException("IPFS response is not a supported song file");
        }
        Path extracted = Files.createDirectory(work.resolve("extracted"));
        try (BufferedInputStream response = new BufferedInputStream(Files.newInputStream(payload))) {
            response.mark(512);
            byte[] header = response.readNBytes(512);
            response.reset();
            if (gzip(header)) {
                try (InputStream archive = new GzipCompressorInputStream(response)) {
                    extractTar(archive, extracted);
                }
            } else if (header.length >= 4 && header[0] == 'P' && header[1] == 'K'
                    && header[2] == 3 && header[3] == 4) {
                extractZip(response, extracted);
            } else if (TarArchiveInputStream.matches(header, header.length) || type.equals("application/x-tar")) {
                extractTar(response, extracted);
            } else {
                String name = ref.substring(ref.lastIndexOf('/') + 1);
                boolean chart = isChartName(name);
                if (header.length == 0 || type.contains("html") || (!chart && !type.equals("text/plain"))) {
                    throw new IOException("IPFS response is not a supported song file");
                }
                finishFile(response, extracted.resolve(chart ? name : name + ".bms"), new Budget(), false);
            }
        }
        try (var entries = Files.list(extracted)) {
            List<Path> top = entries.limit(2).toList();
            if (top.isEmpty()) {
                throw new IOException("IPFS archive is empty");
            }
            Path source = top.size() == 1 && Files.isDirectory(top.get(0)) ? top.get(0) : extracted;
            copyTree(source, content, output);
        }
    }

    private String fetch(String ref, Path payload) throws IOException {
        URI base = endpointUri();
        String path = base.getPath().replaceFirst("/+$", "");
        boolean rpc = path.endsWith("/api/v0") || path.endsWith("/api/v0/get");
        String prefix = path.replaceFirst("/api/v0(?:/get)?$", "").replaceFirst("/ipfs$", "");
        String host = base.getHost();
        boolean local = host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        String authority = base.getScheme() + "://" + base.getRawAuthority();
        String encodedRef = Arrays.stream(ref.split("/")).map(IpfsTransfer::encode).collect(Collectors.joining("/"));
        if (rpc || (local && !path.endsWith("/ipfs"))) {
            URI url = URI.create(authority + encodePath(prefix) + "/api/v0/get?arg="
                    + encode("/ipfs/" + ref) + "&archive=true&compress=true");
            HttpResponse<Path> response = request(HttpRequest.newBuilder(url)
                    .POST(HttpRequest.BodyPublishers.noBody()), payload);
            if (response.statusCode() == 200) {
                return mediaType(response);
            }
            if (rpc || (response.statusCode() != 404 && response.statusCode() != 405)) {
                throw new IOException("IPFS server returned HTTP " + response.statusCode());
            }
        }
        URI url = URI.create(authority + encodePath(prefix) + "/ipfs/" + encodedRef + "?format=tar");
        HttpResponse<Path> response = request(HttpRequest.newBuilder(url)
                .header("Accept", "application/x-tar, application/octet-stream;q=0.9, text/plain;q=0.8"), payload);
        if (response.statusCode() != 200) {
            throw new IOException("IPFS gateway returned HTTP " + response.statusCode());
        }
        return mediaType(response);
    }

    private URI endpointUri() throws IOException {
        try {
            URI uri = URI.create(endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.strip());
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid IPFS HTTP endpoint");
        }
    }

    private HttpResponse<Path> request(HttpRequest.Builder builder, Path payload) throws IOException {
        var response = client.sendAsync(builder.timeout(Duration.ofSeconds(15)).build(), info -> {
            HttpResponse.BodySubscriber<Path> body = info.statusCode() == 200
                    ? HttpResponse.BodySubscribers.ofFile(payload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    : HttpResponse.BodySubscribers.replacing(null);
            return new LimitedBody(body);
        });
        try {
            return response.get(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("IPFS transfer cancelled", e);
        } catch (TimeoutException e) {
            response.cancel(true);
            throw new IOException("IPFS transfer timed out", e);
        } catch (ExecutionException e) {
            throw new IOException("Unable to receive IPFS response", e.getCause());
        }
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodePath(String path) {
        return Arrays.stream(path.split("/", -1)).map(IpfsTransfer::encode).collect(Collectors.joining("/"));
    }

    private static String mediaType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip()
                .toLowerCase(Locale.ROOT);
    }

    private static boolean gzip(byte[] header) {
        return header.length >= 2 && (header[0] & 0xff) == 0x1f && (header[1] & 0xff) == 0x8b;
    }

    private static boolean isChartName(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(bms|bme|bml|pms|bmson)");
    }

    private static boolean isVisible(Path relative) {
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) {
                return false;
            }
        }
        return true;
    }

    private static void extractTar(InputStream input, Path root) throws IOException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new LimitedInput(input), "UTF-8")) {
            Budget budget = new Budget();
            for (TarArchiveEntry entry; (entry = tar.getNextTarEntry()) != null;) {
                if (!tar.canReadEntryData(entry) || entry.isLink() || entry.isSymbolicLink()
                        || entry.isSparse() || !(entry.isFile() || entry.isDirectory())) {
                    throw new IOException("Unsupported IPFS archive entry");
                }
                writeEntry(tar, root, entry.getName(), entry.isDirectory(), budget);
            }
        }
    }

    private static void extractZip(InputStream input, Path root) throws IOException {
        try (ZipArchiveInputStream zip = new ZipArchiveInputStream(input)) {
            Budget budget = new Budget();
            for (ZipArchiveEntry entry; (entry = zip.getNextZipEntry()) != null;) {
                if (!zip.canReadEntryData(entry) || entry.isUnixSymlink()) {
                    throw new IOException("Unsupported IPFS archive entry");
                }
                writeEntry(zip, root, entry.getName(), entry.isDirectory(), budget);
            }
        }
    }

    private static void writeEntry(InputStream input, Path root, String name, boolean directory, Budget budget)
            throws IOException {
        String relative = directory && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        String[] parts = relative.split("/", -1);
        if (parts.length > 32 || Arrays.stream(parts).anyMatch(part -> !validName(part))) {
            throw new IOException("Invalid IPFS archive path");
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("IPFS archive path is outside its destination");
        }
        budget.entry();
        if (directory) {
            Files.createDirectories(target);
        } else {
            Files.createDirectories(target.getParent());
            finishFile(input, target, budget, false);
        }
    }

    private static boolean validName(String value) {
        return !value.isEmpty() && value.length() <= 255 && !value.equals(".") && !value.equals("..")
                && !value.endsWith(".") && !value.endsWith(" ")
                && !value.matches(".*[\\p{Cntrl}<>:\"/\\\\|?*].*")
                && !value.matches("(?i)(CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[1-9¹²³]|LPT[1-9¹²³])( *\\..*)?");
    }

    private static void copyTree(Path source, Path target, Budget budget) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                budget.entry();
                if (source.relativize(dir).getNameCount() > 32) {
                    throw new IOException("IPFS song directory is too deep");
                }
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) {
                    throw new IOException("Unsupported file in the base chart folder");
                }
                budget.entry();
                try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    finishFile(input, target.resolve(source.relativize(file)), budget, true);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void finishFile(InputStream input, Path path, Budget budget, boolean replace) throws IOException {
        StandardOpenOption[] options = replace
                ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE}
                : new StandardOpenOption[] {StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE};
        try (OutputStream output = Files.newOutputStream(path, options)) {
            byte[] buffer = new byte[32 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) {
                checkInterrupted();
                budget.bytes(read);
                output.write(buffer, 0, read);
            }
        }
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("IPFS download cancelled");
        }
    }

    private static void removeStaging(Path staging) throws IOException {
        Files.walkFileTree(staging, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static final class Budget {
        private long bytes;
        private int entries;

        void bytes(long amount) throws IOException {
            bytes += amount;
            if (bytes > MAX_EXPANDED_BYTES) {
                throw new IOException("IPFS archive exceeds the expanded size limit");
            }
        }

        void entry() throws IOException {
            checkInterrupted();
            if (++entries > MAX_ENTRIES) {
                throw new IOException("IPFS archive has too many entries");
            }
        }
    }

    private static final class LimitedInput extends FilterInputStream {
        private final Budget budget = new Budget();

        private LimitedInput(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            checkInterrupted();
            int value = in.read();
            if (value != -1) {
                budget.bytes(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            checkInterrupted();
            int count = in.read(buffer, off, len);
            if (count > 0) {
                budget.bytes(count);
            }
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            checkInterrupted();
            long skipped = in.skip(Math.min(count, MAX_EXPANDED_BYTES - budget.bytes + 1));
            budget.bytes(skipped);
            return skipped;
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<Path> {
        private final HttpResponse.BodySubscriber<Path> delegate;
        private Flow.Subscription subscription;
        private long bytes;
        private boolean rejected;

        LimitedBody(HttpResponse.BodySubscriber<Path> delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletionStage<Path> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> data) {
            if (rejected) {
                return;
            }
            for (ByteBuffer buffer : data) {
                bytes += buffer.remaining();
            }
            if (bytes > MAX_RESPONSE_BYTES) {
                rejected = true;
                subscription.cancel();
                delegate.onError(new IOException("IPFS response exceeds the size limit"));
            } else {
                delegate.onNext(data);
            }
        }

        @Override
        public void onError(Throwable error) {
            if (!rejected) {
                delegate.onError(error);
            }
        }

        @Override
        public void onComplete() {
            if (!rejected) {
                delegate.onComplete();
            }
        }
    }
}
