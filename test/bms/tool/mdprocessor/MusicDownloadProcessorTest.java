package bms.tool.mdprocessor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MusicDownloadProcessorTest {
    private static final String CID = "QmSoLju6m7xTh3DuokvT3886QMy6dTXuo4SVhFNDdaYoJN";
    private static final String CID_V1 = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3uudwisfnf7235qkelnjzcjvm";
    private static final String MD5 = "0123456789abcdef0123456789abcdef";
    private static final byte[] CHART = "#TITLE Loopback chart\n#BPM 120\n#00111:01\n".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Specify an isolated test directory");
        Path root = Files.createTempDirectory(Path.of(args[0]), "ipfs-regression-");
        validateInputsAndLimits();
        runtimeScanRoots(root.resolve("scan"));
        transferAndHandoff(root.resolve("transfer"));
        extensionlessTarEntries(root.resolve("extensionless-tar"));
        localhostRedirectAndStall(root.resolve("redirect-stall"));
        diffsAndFailure(root.resolve("diffs"));
        disposeActiveDownload(root.resolve("dispose"));
        System.out.println("D2 IPFS regression passed: input limits, RPC and gateway, FIFO, diffs, failures, disposal");
    }

    private static void runtimeScanRoots(Path root) throws IOException {
        String[] noManagedRoot = {"configured"};
        require(Arrays.equals(MusicDownloadProcessor.songScanRoots(noManagedRoot, root), noManagedRoot),
                "missing managed root leaves configured roots in place");
        Path directory = Files.createDirectory(root).toRealPath();
        String[] configured = {"configured", directory.toString()};
        String prefix = directory + java.io.File.separator;
        require(Arrays.equals(MusicDownloadProcessor.songScanRoots(configured, root), new String[] {"configured", prefix}),
                "runtime root uses one native path prefix with a separator");
        require(configured[1].equals(directory.toString()), "configured roots unchanged");
    }

    private static void validateInputsAndLimits() throws Exception {
        require(CID.equals(IpfsTransfer.reference("/ipfs/" + CID)), "CIDv0 prefix");
        require((CID_V1 + "/曲.bms").equals(IpfsTransfer.reference("ipfs://" + CID_V1 + "/曲.bms")), "CIDv1 path");
        require(MusicDownloadProcessor.isAllowedTable("https://STELLABMS.XYZ/table"), "known table host");
        require(!MusicDownloadProcessor.isAllowedTable("https://stellabms.example/table"), "unlisted table host");
        require(MusicDownloadProcessor.isAllowedTable("http://cgi.geocities.jp/asahi3jpn/table"), "legacy table path");
        require(!MusicDownloadProcessor.isAllowedTable("http://cgi.geocities.jp/other"), "unlisted legacy table path");
        expectIo(() -> IpfsTransfer.reference("/ipfs/invalid"));
        expectIo(() -> IpfsTransfer.reference(CID + "/NUL.bms"));
        IpfsTransfer.Budget bytes = new IpfsTransfer.Budget();
        bytes.bytes(2L * 1024 * 1024 * 1024);
        expectIo(() -> bytes.bytes(1));
        IpfsTransfer.Budget entries = new IpfsTransfer.Budget();
        for (int i = 0; i < 16_384; i++) {
            entries.entry();
        }
        expectIo(entries::entry);
    }

    private static void transferAndHandoff(Path root) throws Exception {
        byte[] tar = tar("wrapped/chart.bms", false);
        byte[] compressedTar = tar("wrapped/chart.bms", true);
        byte[] zip = zip("wrapped/chart.bms");
        AtomicInteger rpc = new AtomicInteger();
        AtomicInteger gateway = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/api/v0/get", exchange -> {
            rpc.incrementAndGet();
            if (exchange.getRequestMethod().equals("POST") && exchange.getRequestURI().getQuery().contains("/ipfs/" + CID)) {
                reply(exchange, 200, "application/gzip", compressedTar);
            } else {
                reply(exchange, 400, "text/plain", new byte[0]);
            }
        });
        server.createContext("/ipfs/", exchange -> {
            gateway.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            if (!exchange.getRequestMethod().equals("GET") || !"format=tar".equals(exchange.getRequestURI().getQuery())) {
                reply(exchange, 400, "text/plain", new byte[0]);
            } else if (path.endsWith("/曲.bms")) {
                reply(exchange, 200, "application/octet-stream", CHART);
            } else if (path.endsWith("/song.zip")) {
                reply(exchange, 200, "application/zip", zip);
            } else {
                reply(exchange, 200, "application/x-tar", tar);
            }
        });
        server.start();

        MusicDownloadProcessor kubo = new MusicDownloadProcessor(url(server), hashes -> new String[0], root.resolve("kubo"));
        MusicDownloadProcessor http = new MusicDownloadProcessor(url(server) + "/ipfs", hashes -> new String[0],
                root.resolve("gateway"));
        try {
            kubo.start(song("/ipfs/" + CID, null, "Kubo"));
            await(() -> kubo.getDownloadpath() != null, "Kubo install");
            require(rpc.get() == 1 && gateway.get() == 0, "Kubo uses one POST");
            require(Files.readString(Path.of(kubo.getDownloadpath()).resolve("chart.bms")).contains("#TITLE"), "Kubo chart");

            http.start(song(CID_V1, null, "Tar"));
            http.start(song("ipfs://" + CID_V1, null, "Duplicate"));
            http.start(song(CID_V1 + "/曲.bms", null, "Raw"));
            http.start(song(CID_V1 + "/song.zip", null, "Zip"));
            await(() -> http.getDownloadpath() != null && gateway.get() == 3, "gateway requests");
            String first = http.getDownloadpath();
            require(!http.handoff(path -> false) && first.equals(http.getDownloadpath()), "busy updater retains first result");
            require(http.handoff(path -> first.equals(path) && Files.isRegularFile(Path.of(path).resolve("chart.bms"))),
                    "accept first result");
            await(() -> http.getDownloadpath() != null, "second completion");
            require(http.handoff(path -> Files.isRegularFile(Path.of(path).resolve("曲.bms"))), "accept second result");
            await(() -> http.getDownloadpath() != null, "third completion");
            require(http.handoff(path -> Files.isRegularFile(Path.of(path).resolve("chart.bms"))), "accept ZIP result");
            require(http.getDownloadpath() == null && gateway.get() == 3, "each distinct request published once");
            requireNoStaging(root.resolve("kubo"));
            requireNoStaging(root.resolve("gateway"));
        } finally {
            kubo.dispose();
            http.dispose();
            server.stop(0);
        }
    }

    private static void extensionlessTarEntries(Path root) throws Exception {
        Files.createDirectories(root);
        Path local = Files.createDirectory(root.resolve("local"));
        Path baseChart = Files.write(local.resolve("base.bms"), CHART);
        byte[] bareTar = tar(CID, false);
        byte[] leafNamedTar = tar("blob", false);
        byte[] diffTar = tar("diff_entry", false);
        byte[] htmlTar = tarBytes("index", "<!DOCTYPE html><html></html>".getBytes(StandardCharsets.UTF_8), false);

        HttpServer server = server();
        server.createContext("/ipfs/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/" + CID)) {
                reply(exchange, 200, "application/x-tar", bareTar);
            } else if (path.endsWith("/chart.bms")) {
                reply(exchange, 200, "application/x-tar", leafNamedTar);
            } else if (path.endsWith("/diff")) {
                reply(exchange, 200, "application/x-tar", diffTar);
            } else if (path.endsWith("/html")) {
                reply(exchange, 200, "application/x-tar", htmlTar);
            } else {
                reply(exchange, 404, "text/plain", new byte[0]);
            }
        });
        server.start();

        MusicDownloadProcessor processor = new MusicDownloadProcessor(url(server) + "/ipfs",
                hashes -> new String[] {baseChart.toString()}, root.resolve("managed"));
        try {
            processor.start(song(CID, null, "Bare TAR"));
            await(() -> processor.getDownloadpath() != null, "bare extensionless TAR");
            require(processor.handoff(p -> Files.isRegularFile(Path.of(p).resolve(CID + ".bms"))),
                    "bare extensionless TAR renamed to .bms");

            processor.start(song(CID_V1 + "/chart.bms", null, "Leaf named TAR"));
            await(() -> processor.getDownloadpath() != null, "refLeaf TAR");
            require(processor.handoff(p -> Files.isRegularFile(Path.of(p).resolve("chart.bms"))),
                    "extensionless TAR uses refLeaf chart name");

            processor.start(new Song(null, CID + "/diff", null, "Diff TAR", List.of(MD5)));
            await(() -> processor.getDownloadpath() != null, "diff extensionless TAR");
            require(processor.handoff(p -> !Files.exists(Path.of(p).resolve("base.bms"))
                    && Files.isRegularFile(Path.of(p).resolve("diff_entry.bms"))),
                    "diff extensionless TAR renamed to .bms and base chart excluded");

            processor.start(song(CID + "/html", null, "HTML TAR"));
            require(awaitFailure(processor).contains("no chart file"), "HTML TAR entry not renamed to .bms");
        } finally {
            processor.dispose();
            server.stop(0);
        }
    }

    private static void localhostRedirectAndStall(Path root) throws Exception {
        Files.createDirectories(root);
        IpfsTransfer.validateRedirectTarget(
                URI.create("http://" + CID_V1 + ".ipfs.localhost:8080/ipfs/" + CID_V1), true);
        expectIo(() -> IpfsTransfer.validateRedirectTarget(URI.create("https://example.com/ipfs/" + CID_V1), true));
        expectIo(() -> IpfsTransfer.validateRedirectTarget(URI.create("http://127.0.0.1:8080/ipfs/" + CID_V1), false));
        expectIo(() -> IpfsTransfer.validateRedirectTarget(URI.create("http://10.0.0.1/ipfs/" + CID_V1), false));
        IpfsTransfer.validateRedirectScheme(URI.create("http://gateway.example/ipfs/"), URI.create("https://gateway.example/ipfs/"));
        expectIo(() -> IpfsTransfer.validateRedirectScheme(URI.create("https://gateway.example/ipfs/"),
                URI.create("http://gateway.example/ipfs/")));
        expectIo(() -> IpfsTransfer.validateRedirectTarget(URI.create("http://user:pass@localhost:8080/"), true));

        byte[] tarPayload = tar("wrapped/chart.bms", false);
        AtomicInteger hops = new AtomicInteger();
        HttpServer redirectServer = server();
        redirectServer.createContext("/ipfs/", exchange -> {
            String host = exchange.getRequestHeaders().getFirst("Host");
            String path = exchange.getRequestURI().getPath();
            int port = redirectServer.getAddress().getPort();
            if (path.contains("/cross")) {
                try (exchange) {
                    exchange.getResponseHeaders().set("Location", "https://example.invalid/ipfs/" + CID_V1);
                    exchange.sendResponseHeaders(301, -1);
                }
            } else if (path.contains("/temporary")) {
                try (exchange) {
                    exchange.getResponseHeaders().set("Location",
                            "http://127.0.0.1:" + port + "/ipfs/" + CID_V1 + "?format=tar");
                    exchange.sendResponseHeaders(307, -1);
                }
            } else if (path.contains("/loop")) {
                try (exchange) {
                    exchange.getResponseHeaders().set("Location",
                            "http://127.0.0.1:" + port + "/ipfs/loop?n=" + hops.incrementAndGet());
                    exchange.sendResponseHeaders(302, -1);
                }
            } else if (host != null && host.toLowerCase(java.util.Locale.ROOT).startsWith("localhost:")) {
                try (exchange) {
                    exchange.getResponseHeaders().set("Location",
                            "http://127.0.0.1:" + port + "/ipfs/" + CID_V1 + "?format=tar");
                    exchange.sendResponseHeaders(301, -1);
                }
            } else if (host != null && host.startsWith("127.0.0.1:")) {
                reply(exchange, 200, "application/x-tar", tarPayload);
            } else {
                reply(exchange, 400, "text/plain", new byte[0]);
            }
        });
        redirectServer.start();

        MusicDownloadProcessor processor = new MusicDownloadProcessor(
                "http://localhost:" + redirectServer.getAddress().getPort() + "/ipfs",
                hashes -> new String[0], root.resolve("redirect"));
        try {
            processor.start(song(CID_V1, null, "Localhost redirect"));
            await(() -> processor.getDownloadpath() != null, "localhost -> 127.0.0.1 301 redirect");
            require(processor.handoff(p -> Files.isRegularFile(Path.of(p).resolve("chart.bms"))),
                    "redirected chart installed");

            processor.start(song(CID_V1 + "/cross", null, "Cross boundary"));
            require(awaitFailure(processor).contains("trust boundary"), "cross-boundary redirect rejected");

            processor.start(song(CID_V1 + "/temporary", null, "Temporary redirect"));
            require(awaitFailure(processor).contains("HTTP 307"), "only 301 and 302 redirects are followed");

            processor.start(song(CID_V1 + "/loop", null, "Redirect loop"));
            require(awaitFailure(processor).contains("redirects"), "excessive redirects rejected");
        } finally {
            processor.dispose();
            redirectServer.stop(0);
        }

        CountDownLatch releaseHandler = new CountDownLatch(1);
        HttpServer stallServer = server();
        stallServer.createContext("/ipfs/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(CHART, 0, 1);
                exchange.getResponseBody().flush();
                try {
                    releaseHandler.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        stallServer.start();
        try {
            IpfsTransfer transfer = new IpfsTransfer(url(stallServer) + "/ipfs", root.resolve("stall"),
                    Duration.ofMillis(250));
            long start = System.nanoTime();
            IOException thrown = null;
            try {
                transfer.install(CID + "/stalled.bms", null, null, "Stalled", null, (s, t) -> Files.move(s, t));
            } catch (IOException e) {
                thrown = e;
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            require(thrown != null && thrown.getMessage().contains("stalled"),
                    "stalled body aborted with stalled error");
            require(elapsedMs < 3000, "stalled body aborted promptly: " + elapsedMs + " ms");
            requireNoStaging(root.resolve("stall"));
        } finally {
            releaseHandler.countDown();
            stallServer.stop(0);
        }
    }

    private static void diffsAndFailure(Path root) throws Exception {
        Files.createDirectories(root);
        Path local = Files.createDirectory(root.resolve("local"));
        Path chart = Files.write(local.resolve("base.bms"), CHART);
        Files.writeString(local.resolve("asset.txt"), "original");
        AtomicReference<String[]> lookedUp = new AtomicReference<>();
        byte[] diff = tarEntries(false,
                "wrapped/diff.bms", CHART,
                "wrapped/asset.txt", "overlaid".getBytes(StandardCharsets.UTF_8));
        HttpServer server = server();
        server.createContext("/api/v0/get", exchange -> reply(exchange, 405, "text/plain", new byte[0]));
        server.createContext("/ipfs/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/unavailable")) {
                reply(exchange, 404, "text/plain", new byte[0]);
            } else if (path.endsWith("/error")) {
                reply(exchange, 500, "text/plain", new byte[0]);
            } else if (path.endsWith("/empty")) {
                reply(exchange, 200, "application/x-tar", new byte[0]);
            } else if (path.endsWith("/dag.bms")) {
                reply(exchange, 200, "application/vnd.ipld.car", CHART);
            } else {
                reply(exchange, 200, "application/x-tar", diff);
            }
        });
        server.start();

        Path output = root.resolve("managed");
        MusicDownloadProcessor processor = new MusicDownloadProcessor(url(server), hashes -> {
            lookedUp.set(hashes);
            return new String[] {null, "archive.zip!/base.bms", chart.toString()};
        }, output);
        try {
            processor.start(new Song(null, CID + "/diff", null, "Local parent",
                    Arrays.asList(null, "invalid", MD5.toUpperCase(java.util.Locale.ROOT))));
            await(() -> processor.getDownloadpath() != null, "diff-only install");
            Path installed = Path.of(processor.getDownloadpath());
            require(Arrays.equals(lookedUp.get(), new String[] {MD5}), "valid MD5s only");
            require(!Files.exists(installed.resolve("base.bms")) && Files.isRegularFile(installed.resolve("diff.bms")),
                    "base chart excluded and diff chart installed");
            require(Files.readString(installed.resolve("asset.txt")).equals("overlaid")
                    && Files.readString(local.resolve("asset.txt")).equals("original")
                    && !Files.exists(local.resolve("diff.bms")), "diff unlinks hardlink and leaves source untouched");
            IpfsTransfer.linkOrCopy(local.resolve("asset.txt"), root.resolve("fallback-io.txt"),
                    new IpfsTransfer.Budget(), (link, existing) -> { throw new IOException("cross-device"); });
            IpfsTransfer.linkOrCopy(local.resolve("asset.txt"), root.resolve("fallback-uoe.txt"),
                    new IpfsTransfer.Budget(), (link, existing) -> { throw new UnsupportedOperationException(); });
            IpfsTransfer.linkOrCopy(local.resolve("asset.txt"), root.resolve("fallback-sec.txt"),
                    new IpfsTransfer.Budget(), (link, existing) -> { throw new SecurityException(); });
            require(Files.readString(root.resolve("fallback-io.txt")).equals("original")
                    && Files.readString(root.resolve("fallback-uoe.txt")).equals("original")
                    && Files.readString(root.resolve("fallback-sec.txt")).equals("original"),
                    "hardlink fallback copies on IO/Unsupported/Security exceptions");
            processor.setDownloadpath(null);

            processor.start(song(CID + "/unavailable", null, "Missing"));
            require(awaitFailure(processor).contains("404"), "missing response");
            processor.start(song(CID + "/base", CID + "/error", "Partial"));
            require(awaitFailure(processor).contains("500"), "failed diff reports error");
            processor.start(song(CID + "/empty", null, "Empty archive"));
            require(awaitFailure(processor).contains("empty"), "empty archive rejected");
            processor.start(song(CID + "/dag.bms", null, "DAG stream"));
            require(awaitFailure(processor).contains("supported song file"), "CAR payload is not installed as a chart");
            processor.start(song(null, CID + "/only-diff", "No parent"));
            require(awaitFailure(processor).contains("base chart"), "no parent rejected");
            require(processor.getDownloadpath() == null, "no failed request published");
            requireNoStaging(output);
            try (var entries = Files.list(output)) {
                require(entries.count() == 1, "failed jobs did not publish files");
            }

            processor.start(song(CID + "/base", CID + "/recovered", "Downloaded parent"));
            await(() -> processor.getDownloadpath() != null, "worker recovers after errors");
            require(Files.isRegularFile(Path.of(processor.getDownloadpath()).resolve("diff.bms")), "recovered install");
        } finally {
            processor.dispose();
            server.stop(0);
        }
    }

    private static void disposeActiveDownload(Path output) throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        HttpServer server = server();
        server.createContext("/ipfs/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(CHART, 0, 1);
                exchange.getResponseBody().flush();
                requestStarted.countDown();
                try {
                    releaseHandler.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();

        MusicDownloadProcessor processor = new MusicDownloadProcessor(url(server) + "/ipfs", hashes -> new String[0],
                output);
        try {
            processor.start(song(CID + "/slow.bms", null, "Slow"));
            require(requestStarted.await(5, TimeUnit.SECONDS), "slow response started");
            long start = System.nanoTime();
            processor.dispose();
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            require(millis < 1000 && !processor.isAlive(), "active worker stopped within 1 sec: " + millis + " ms");
            require(processor.getDownloadpath() == null, "cancelled request not published");
            requireNoStaging(output);
            System.out.println("Active disposal: " + millis + " ms");
        } finally {
            releaseHandler.countDown();
            processor.dispose();
            server.stop(0);
        }
    }

    private static HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void reply(HttpExchange exchange, int status, String type, byte[] payload) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            if (payload.length != 0) {
                exchange.getResponseBody().write(payload);
            }
        }
    }

    private static byte[] tar(String filename, boolean gzip) throws IOException {
        return tarBytes(filename, CHART, gzip);
    }

    private static byte[] tarBytes(String filename, byte[] data, boolean gzip) throws IOException {
        return tarEntries(gzip, filename, data);
    }

    private static byte[] tarEntries(boolean gzip, Object... nameDataPairs) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream archive = new TarArchiveOutputStream(gzip ? new GZIPOutputStream(bytes) : bytes)) {
            for (int i = 0; i < nameDataPairs.length; i += 2) {
                String filename = (String) nameDataPairs[i];
                byte[] data = (byte[]) nameDataPairs[i + 1];
                TarArchiveEntry entry = new TarArchiveEntry(filename);
                entry.setSize(data.length);
                archive.putArchiveEntry(entry);
                archive.write(data);
                archive.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] zip(String filename) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
            archive.putNextEntry(new ZipEntry(filename));
            archive.write(CHART);
            archive.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void requireNoStaging(Path root) throws IOException {
        try (var entries = Files.list(root)) {
            require(entries.noneMatch(path -> path.getFileName().toString().startsWith(".download-")), "staging cleaned");
        }
    }

    private static Song song(String base, String diff, String title) {
        return new Song(base, diff, null, title, List.of());
    }

    private static String awaitFailure(MusicDownloadProcessor processor) throws InterruptedException {
        AtomicReference<String> message = new AtomicReference<>();
        await(() -> {
            message.set(processor.pollFailure());
            return message.get() != null;
        }, "failure notification");
        return message.get();
    }

    private static void await(BooleanSupplier done, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (done.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(description + " timed out");
    }

    private static void expectIo(IoAction action) throws IOException {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected an IOException");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new AssertionError(reason);
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private record Song(String getIpfs, String getAppendIpfs, String getArtist, String getTitle, List<String> getOrg_md5)
            implements IpfsInformation {}
}
