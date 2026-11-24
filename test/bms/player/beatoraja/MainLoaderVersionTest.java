package bms.player.beatoraja;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Verifies GithubVersionChecker tag_name fallback and v-prefix normalization.
 */
public final class MainLoaderVersionTest {
    public static void main(String[] args) throws Exception {
        check("0.8.9".equals(MainLoader.GithubVersionChecker.parseReleaseName("{\"name\":\"0.8.9\",\"tag_name\":\"v0.8.9\"}")),
                "valid name preferred");
        check("0.8.9".equals(MainLoader.GithubVersionChecker.parseReleaseName("{\"name\":\"beatoraja 0.8.9\",\"tag_name\":\"v0.8.9\"}")),
                "invalid name falls back to tag_name and strips v");
        check("0.8.10".equals(MainLoader.GithubVersionChecker.parseReleaseName("{\"name\":\"   \",\"tag_name\":\"V0.8.10\"}")),
                "blank name falls back to tag_name and strips V");
        check("0.8.9".equals(MainLoader.GithubVersionChecker.parseReleaseName("{\"name\":\"v0.8.9\"}")),
                "v-prefix stripped from name");

        boolean rejectedInvalid = false;
        try {
            MainLoader.GithubVersionChecker.parseReleaseName("{\"name\":\"../evil\",\"tag_name\":\"bad/tag\"}");
        } catch (IOException expected) {
            rejectedInvalid = true;
        }
        check(rejectedInvalid, "both invalid name and tag_name must throw IOException");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/current", exchange -> {
            byte[] body = "{\"name\":\"beatoraja 0.8.9\",\"tag_name\":\"v0.8.9\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/newer", exchange -> {
            byte[] body = "{\"name\":\"beatoraja 0.9.0\",\"tag_name\":\"v0.9.0\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            MainLoader.GithubVersionChecker current = new MainLoader.GithubVersionChecker(base + "/current");
            if (MainController.getVersion().contains("0.8.9")) {
                check("最新版を利用中です".equals(current.getMessage()), "current version recognized: " + current.getMessage());
                check(current.getDownloadURL() == null, "current version has no download URL");
            }

            MainLoader.GithubVersionChecker newer = new MainLoader.GithubVersionChecker(base + "/newer");
            check("最新版[0.9.0]を利用可能です。".equals(newer.getMessage()), "newer version message: " + newer.getMessage());
            check("https://mocha-repository.info/download/beatoraja0.9.0.zip".equals(newer.getDownloadURL()),
                    "normalized download URL: " + newer.getDownloadURL());
            System.out.println("MainLoaderVersionTest PASS");
        } finally {
            server.stop(0);
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
