package bms.player.beatoraja;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.GdxNativesLoader;

/** Regression tests for concurrent resource loading and eviction. */
public final class ResourcePoolTest {
    public static void main(String[] args) throws Exception {
        assertSingleLoad();
        assertParallelLoads();
        assertEvictionWaitsForLoad(false);
        assertEvictionWaitsForLoad(true);
        assertGenerationsAndRetry();
        assertResourceFactory();
        assertImageSignatures();
        assertPixmapLoading();
        System.out.println("ResourcePoolTest passed");
    }

    private static void assertImageSignatures() throws Exception {
        byte[] jpeg = { (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0 };
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
        byte[] wave = "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1);
        byte[] tga = { 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
        byte[] os2Bmp = "BA\0\0\0\0\0\0\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1);
        check(PixmapResourcePool.isJpegSignature(jpeg), "JPEG magic must be detected");
        check(!PixmapResourcePool.isJpegSignature(new byte[] { (byte) 0xff, (byte) 0xd8 }), "Short header is not JPEG");
        check(PixmapResourcePool.isWebpSignature(webp), "RIFF/WEBP magic must be detected");
        check(!PixmapResourcePool.isWebpSignature(wave), "RIFF/WAVE is not WebP");
        for (byte[] kept : new byte[][] { tga, os2Bmp }) {
            check(!PixmapResourcePool.isJpegSignature(kept) && !PixmapResourcePool.isWebpSignature(kept),
                    "Headerless TGA and OS/2 BMP must keep the native decoder");
        }
        check(PixmapResourcePool.readLimited(new ByteArrayInputStream(new byte[10]), 10).length == 10,
                "Image at the byte limit must load");
        try {
            PixmapResourcePool.readLimited(new ByteArrayInputStream(new byte[11]), 10);
            throw new AssertionError("Image over the byte limit must be rejected");
        } catch (IOException expected) {
        }
        check(PixmapResourcePool.maxSpriteFrames(256, 256, 4096) == 256, "256px frames fit 16x16 per texture");
        check(PixmapResourcePool.maxSpriteFrames(8192, 8192, 4096) == 1, "Oversized frame still yields one frame");
    }

    private static void assertPixmapLoading() throws Exception {
        GdxNativesLoader.load();
        Files oldFiles = Gdx.files;
        Gdx.files = new Lwjgl3Files();
        Path directory = java.nio.file.Files.createTempDirectory("d3-pixmap");
        try {
            // A JPEG renamed to .bmp must be decoded through ImageIO, not the native decoder.
            BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
            Path fakeBmp = directory.resolve("fake.bmp");
            check(ImageIO.write(image, "jpg", fakeBmp.toFile()), "JPEG writer must exist");
            Pixmap jpeg = PixmapResourcePool.loadPicture(fakeBmp.toString());
            check(jpeg != null && jpeg.getWidth() == 3 && jpeg.getHeight() == 2, "JPEG disguised as .bmp must load");
            jpeg.dispose();

            // Truevision TGA v1 has no magic bytes: 2x1, 24-bit, top-left origin, BGR pixels.
            byte[] tga = { 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1, 0, 24, 0x20,
                    (byte) 0xff, 0, 0, 0, 0, (byte) 0xff };
            Path tgaFile = directory.resolve("plain.tga");
            java.nio.file.Files.write(tgaFile, tga);
            Pixmap pixmap = PixmapResourcePool.loadPicture(tgaFile.toString());
            check(pixmap != null && pixmap.getWidth() == 2 && pixmap.getHeight() == 1, "Headerless TGA must load");
            check(pixmap.getPixel(0, 0) == 0x0000ffff && pixmap.getPixel(1, 0) == 0xff0000ff,
                    "Headerless TGA pixels must be preserved");
            pixmap.dispose();

            assertConvertKeepsSpriteSheet();
        } finally {
            Gdx.files = oldFiles;
            try (var paths = java.nio.file.Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static void assertConvertKeepsSpriteSheet() {
        var copying = new PixmapResourcePool() {
            @Override
            protected Pixmap convert(Pixmap pixmap) {
                Pixmap copy = new Pixmap(pixmap.getWidth(), pixmap.getHeight(), pixmap.getFormat());
                copy.drawPixmap(pixmap, 0, 0);
                pixmap.dispose();
                return copy;
            }
        };
        var resource = copying.convert(new PixmapResourcePool.PixmapResource(
                new Pixmap(8, 4, Pixmap.Format.RGBA8888), 2, 4, 4, 2, 1));
        check(resource.getFrameCount() == 2 && resource.getColumns() == 2 && resource.getFrameWidth() == 4,
                "Same-size conversion must keep sprite-sheet metadata");
        resource.dispose();

        var resizing = new PixmapResourcePool() {
            @Override
            protected Pixmap convert(Pixmap pixmap) {
                pixmap.dispose();
                return new Pixmap(16, 16, Pixmap.Format.RGBA8888);
            }
        };
        resource = resizing.convert(new PixmapResourcePool.PixmapResource(
                new Pixmap(8, 4, Pixmap.Format.RGBA8888), 2, 4, 4, 2, 1));
        check(resource.getFrameCount() == 1 && resource.getFrameWidth() == 16,
                "Resized conversion must fall back to a single frame");
        resource.dispose();
    }

    private static void assertResourceFactory() {
        var pool = new TestPool(0);
        Resource resource = pool.get("archive-key", Resource::new);
        check(pool.get("archive-key", () -> {
            throw new AssertionError("Cache hit must not invoke the resource factory");
        }) == resource, "Resource-backed loads must use the same cache");
        check(pool.loads.get() == 0, "Resource-backed loads must bypass path loading");
        pool.disposeOld();
        check(resource.disposals.get() == 1, "Resource-backed loads must be evicted normally");
    }

    private static void assertSingleLoad() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        var pool = new TestPool(1) {
            @Override
            protected Resource load(String key) {
                Resource resource = super.load(key);
                entered.countDown();
                await(finish);
                return resource;
            }
        };
        var executor = Executors.newFixedThreadPool(8);
        try {
            var results = new ArrayList<Future<Resource>>();
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> pool.get("same")));
            }
            await(entered);
            finish.countDown();
            Resource first = results.get(0).get(5, TimeUnit.SECONDS);
            for (Future<Resource> result : results) {
                check(result.get(5, TimeUnit.SECONDS) == first, "Same key must share one resource");
            }
            check(pool.loads.get() == 1, "Same key must be loaded only once");
            pool.dispose();
            pool.dispose();
            check(first.disposals.get() == 1, "Resource must be disposed exactly once");
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertParallelLoads() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        var pool = new TestPool(1) {
            @Override
            protected Resource load(String key) {
                entered.countDown();
                await(entered);
                return super.load(key);
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Resource> first = executor.submit(() -> pool.get("first"));
            Future<Resource> second = executor.submit(() -> pool.get("second"));
            check(first.get(5, TimeUnit.SECONDS) != second.get(5, TimeUnit.SECONDS),
                    "Different keys must load concurrently");
            pool.dispose();
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertEvictionWaitsForLoad(boolean oldOnly) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch evictionStarted = new CountDownLatch(1);
        var pool = new TestPool(0) {
            @Override
            protected Resource load(String key) {
                Resource resource = super.load(key);
                entered.countDown();
                await(finish);
                return resource;
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Resource> loading = executor.submit(() -> pool.get("loading"));
            await(entered);
            Future<?> eviction = executor.submit(() -> {
                evictionStarted.countDown();
                if (oldOnly) {
                    pool.disposeOld();
                } else {
                    pool.dispose();
                }
            });
            await(evictionStarted);
            try {
                eviction.get(100, TimeUnit.MILLISECONDS);
                throw new AssertionError("Eviction must wait for resource registration");
            } catch (TimeoutException expected) {
            }
            finish.countDown();
            Resource resource = loading.get(5, TimeUnit.SECONDS);
            eviction.get(5, TimeUnit.SECONDS);
            check(pool.size() == 0, "In-flight resource must not survive eviction");
            check(resource.disposals.get() == 1, "In-flight resource must be disposed once");
            pool.dispose();
            check(resource.disposals.get() == 1, "Repeated eviction must not double-dispose");
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertGenerationsAndRetry() {
        var pool = new TestPool(1);
        Resource resource = pool.get("generation");
        pool.disposeOld();
        check(pool.get("generation") == resource, "Cache hit must reset the generation");
        pool.disposeOld();
        check(resource.disposals.get() == 0, "Recently used resource must survive");
        pool.disposeOld();
        check(resource.disposals.get() == 1 && !pool.exists("generation"),
                "Expired resource must be removed and disposed");
        Resource replacement = pool.get("generation");
        check(replacement != resource, "Expired resource must be reloaded");
        check(pool.getStatistics(value -> 16).estimatedBytes() == 16, "Statistics must reflect live entries");
        pool.dispose();
        check(replacement.disposals.get() == 1, "Reloaded resource must be disposed");

        var retryPool = new TestPool(1) {
            @Override
            protected Resource load(String key) {
                int attempt = loads.incrementAndGet();
                if (attempt == 1) {
                    return null;
                }
                if (attempt == 2) {
                    throw new IllegalStateException("Expected test failure");
                }
                return new Resource();
            }
        };
        check(retryPool.get("retry") == null, "Null loads must not be cached");
        try {
            retryPool.get("retry");
            throw new AssertionError("Load exception must propagate");
        } catch (IllegalStateException expected) {
        }
        check(retryPool.get("retry") != null, "Failed loads must be retryable");
        retryPool.dispose();
    }

    private static void await(CountDownLatch latch) {
        try {
            check(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for concurrent operation");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static class TestPool extends ResourcePool<String, Resource> {
        final AtomicInteger loads = new AtomicInteger();

        TestPool(int generations) {
            super(generations);
        }

        @Override
        protected Resource load(String key) {
            loads.incrementAndGet();
            return new Resource();
        }

        @Override
        protected void dispose(Resource resource) {
            resource.disposals.incrementAndGet();
        }
    }

    private static final class Resource {
        final AtomicInteger disposals = new AtomicInteger();
    }
}
