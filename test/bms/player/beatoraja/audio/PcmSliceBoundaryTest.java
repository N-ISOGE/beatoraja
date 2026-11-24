package bms.player.beatoraja.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.IntFunction;

/** Headless WP-D3-3 check: PCM slice ranges stay inside the sample buffer for any chart time. */
public final class PcmSliceBoundaryTest {
    private PcmSliceBoundaryTest() { }

    public static void main(String[] args) {
        check("ShortPCM", channels -> {
            short[] samples = new short[1000 * channels];
            Arrays.fill(samples, (short) 1);
            return new ShortPCM(channels, 1000, 0, samples.length, samples);
        });
        check("FloatPCM", channels -> {
            float[] samples = new float[1000 * channels];
            Arrays.fill(samples, 1f);
            return new FloatPCM(channels, 1000, 0, samples.length, samples);
        });
        check("BytePCM", channels -> {
            byte[] samples = new byte[1000 * channels];
            Arrays.fill(samples, (byte) 1);
            return new BytePCM(channels, 1000, 0, samples.length, samples);
        });
        check("ShortDirectPCM", channels -> {
            ByteBuffer samples = ByteBuffer.allocateDirect(1000 * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
            while (samples.hasRemaining()) samples.putShort((short) 1);
            return new ShortDirectPCM(channels, 1000, 0, 1000 * channels, samples.flip());
        });
        System.out.println("PcmSliceBoundaryTest passed");
    }

    /** One second of audio at 1000 Hz, mono and stereo. */
    private static void check(String name, IntFunction<PCM<?>> factory) {
        for (int channels = 1; channels <= 2; channels++) {
            PCM<?> pcm = factory.apply(channels);
            expect(name, pcm.slice(250_000, 500_000), 250 * channels, 500 * channels);
            expect(name, pcm.slice(500_000, 0), 500 * channels, 500 * channels);
            expect(name, pcm.slice(-5_000_000, 100_000), 0, 100 * channels);
            expect(name, pcm.slice(900_000, Long.MAX_VALUE), 900 * channels, 100 * channels);
            expect(name, pcm.slice(Long.MIN_VALUE, Long.MAX_VALUE), 0, 1000 * channels);
            expectNull(name, pcm.slice(Long.MAX_VALUE, 1));
            expectNull(name, pcm.slice(2_000_000, 100_000));
            expectNull(name, pcm.slice(100_000, -1));
        }
    }

    private static void expect(String name, PCM<?> slice, int start, int length) {
        if (slice == null || slice.start != start || slice.len != length) {
            throw new AssertionError(name + " slice expected start=" + start + " len=" + length + " but was "
                    + (slice == null ? "null" : "start=" + slice.start + " len=" + slice.len));
        }
    }

    private static void expectNull(String name, PCM<?> slice) {
        if (slice != null) {
            throw new AssertionError(name + " out-of-range slice must be empty, was start=" + slice.start
                    + " len=" + slice.len);
        }
    }
}
