package bms.player.beatoraja;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

/** ローカルHTTPと隔離キャッシュで難易度表の契約を確認する。
 * Verify table contracts with loopback HTTP and an isolated cache. */
public final class TableDataBoundaryTest {
    public static void main(String[] args) throws Exception {
        Path fixtures = args.length > 1 ? Path.of(args[1]) : Path.of("test_tables");
        Path run = args.length > 0 ? Path.of(args[0]) : Files.createTempDirectory("table-boundaries-");
        Files.createDirectories(run);
        Set<String> allowed = Set.of("header_test_a.json", "data_test_a.json", "header_only_course.json",
                "header_partial_success.json", "data_partial_valid.json", "header_broken_data.json");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String name = exchange.getRequestURI().getPath().substring(1);
            byte[] bytes = allowed.contains(name) ? Files.readAllBytes(fixtures.resolve(name)) : new byte[0];
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(allowed.contains(name) ? 200 : 404, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            Path cache = Files.createDirectories(run.resolve("cache"));
            TableData normal = read(cache, base + "header_test_a.json");
            check(normal != null && normal.getName().equals("프나조누 (メヒサあ)"), "UTF-8 table name");
            check(Arrays.equals(Arrays.stream(normal.getFolder()).map(TableData.TableFolder::getName).toArray(String[]::new),
                    new String[]{"HBNM_exqfsh", "HBNM_vkgfli", "HBNM_★svqlpj"}), "declared folder order");
            check(normal.getFolder()[0].getSong().length == 2 && normal.getFolder()[1].getSong().length == 2
                    && normal.getFolder()[2].getSong().length == 1, "deduplicate within folders, preserve cross-folder membership and remap");
            check(Arrays.stream(normal.getFolder()).flatMap(f -> Arrays.stream(f.getSong()))
                    .noneMatch(s -> "7911e536467a8c18905b05a2b5dc4ff3".equals(s.getMd5())), "drop rule");
            TableData course = read(cache, base + "header_only_course.json");
            check(course != null && course.getFolder().length == 0 && course.getCourse().length == 1
                    && course.getCourse()[0].getSong().length == 1, "header-only course");
            TableData partial = read(cache, base + "header_partial_success.json");
            check(partial != null && Arrays.stream(partial.getFolder()).mapToInt(f -> f.getSong().length).sum() == 1,
                    "partial success keeps the readable mirror");
            // 全データ失敗時は有効なキャッシュをバイト単位で維持する。
            // Total data failure must preserve the valid cache byte for byte.
            String broken = base + "header_broken_data.json";
            normal.setUrl(broken);
            TableDataAccessor accessor = new TableDataAccessor(cache.toString());
            accessor.write(normal);
            Path saved;
            try (var stream = Files.list(cache)) { saved = stream.filter(p -> p.toString().endsWith(".bmt")).findFirst().orElseThrow(); }
            byte[] previous = Files.readAllBytes(saved);
            accessor.updateTableData(new String[]{broken});
            check(Arrays.equals(previous, Files.readAllBytes(saved)), "all-failed update preserves cache bytes");
            System.out.println("TableDataBoundaryTest PASS (folder membership/order/drop/remap, course-only, partial success, total-failure cache preservation)");
        } finally { server.stop(0); }
    }

    private static TableData read(Path cache, String url) {
        return new TableDataAccessor.DifficultyTableAccessor(cache.toString(), url).read();
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
