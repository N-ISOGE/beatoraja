package bms.player.beatoraja.stream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

import bms.player.beatoraja.MessageRenderer;
import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.stream.command.StreamCommand;
import bms.player.beatoraja.stream.command.StreamRequestCommand;

/**
 * beatoraja パイプで受け取った文字列処理
 */
public class StreamController {
    private static final String DEFAULT_PIPE_PATH = "\\\\.\\pipe\\beatoraja";
    static final int MAX_LINE_BYTES = 64 * 1024;

    @FunctionalInterface
    private interface PipeOpener {
        InputStream open() throws IOException;
    }

    private static final class LineTooLongException extends IOException {
    }

    StreamCommand[] commands;
    Thread polling;
    volatile boolean isActive = false;
    MusicSelector selector;
    MessageRenderer notifier;

    private final PipeOpener pipeOpener;
    private final boolean liveWindowsPipe;
    private final AtomicBoolean commandsDisposed = new AtomicBoolean();
    private volatile InputStream activeStream;
    private volatile boolean disposed = false;

    public StreamController(MusicSelector selector, MessageRenderer notifier) {
        this.selector = selector;
        this.notifier = notifier;
        this.commands = new StreamCommand[] { new StreamRequestCommand(this.selector, this.notifier) };
        this.pipeOpener = () -> new FileInputStream(DEFAULT_PIPE_PATH);
        this.liveWindowsPipe = true;
    }

    StreamController(MusicSelector selector, MessageRenderer notifier,
            Supplier<InputStream> pipeOpener, StreamCommand... commands) {
        this.selector = selector;
        this.notifier = notifier;
        this.commands = commands != null ? commands : new StreamCommand[0];
        this.pipeOpener = pipeOpener != null ? pipeOpener::get : () -> null;
        this.liveWindowsPipe = false;
    }

    public synchronized void run() {
        if (disposed || polling != null) {
            return;
        }
        polling = new Thread(this::readLoop, "stream-pipe-reader");
        polling.setDaemon(true);
        polling.start();
    }

    private void readLoop() {
        InputStream raw;
        try {
            if (disposed) {
                disposeCommandsOnce();
                return;
            }
            raw = pipeOpener.open();
        } catch (IOException | RuntimeException e) {
            Logger.getGlobal().info("Stream pipe unavailable: " + e.getClass().getSimpleName());
            disposeCommandsOnce();
            return;
        }
        if (raw == null) {
            disposeCommandsOnce();
            return;
        }
        synchronized (this) {
            if (disposed) {
                closeQuietly(raw);
                disposeCommandsOnce();
                return;
            }
            activeStream = raw;
            isActive = true;
        }
        try (BufferedInputStream in = new BufferedInputStream(raw)) {
            while (!disposed && !Thread.currentThread().isInterrupted()) {
                String line;
                try {
                    line = readLineBounded(in, MAX_LINE_BYTES);
                } catch (LineTooLongException e) {
                    Logger.getGlobal().warning("Stream pipe line exceeded " + MAX_LINE_BYTES + " bytes; skipping line");
                    continue;
                }
                if (line == null) {
                    break;
                }
                Logger.getGlobal().info("受信:" + line);
                execute(line);
            }
        } catch (IOException | RuntimeException e) {
            if (!disposed) {
                Logger.getGlobal().warning("Stream pipe read error: " + e.getClass().getSimpleName());
            }
        } finally {
            isActive = false;
            synchronized (this) {
                if (activeStream == raw) {
                    activeStream = null;
                }
            }
        }
    }

    public void dispose() {
        Thread reader;
        InputStream stream;
        synchronized (this) {
            if (disposed) {
                return;
            }
            disposed = true;
            isActive = false;
            reader = polling;
            polling = null;
            stream = activeStream;
            activeStream = null;
        }
        if (reader != null) {
            reader.interrupt();
        }
        if (stream != null) {
            if (liveWindowsPipe) {
                Thread closer = new Thread(() -> closeQuietly(stream), "stream-pipe-closer");
                closer.setDaemon(true);
                closer.start();
            } else {
                closeQuietly(stream);
            }
        }
        disposeCommandsOnce();
        Logger.getGlobal().info("パイプリソース破棄完了");
    }

    private void disposeCommandsOnce() {
        if (!commandsDisposed.compareAndSet(false, true) || commands == null) {
            return;
        }
        for (StreamCommand command : commands) {
            if (command != null) {
                try {
                    command.dispose();
                } catch (RuntimeException e) {
                    Logger.getGlobal().warning("Stream command dispose failed: " + e.getClass().getSimpleName());
                }
            }
        }
    }

    void execute(String line) {
        if (line == null || commands == null) {
            return;
        }
        for (StreamCommand command : commands) {
            if (command == null || command.COMMAND_STRING == null) {
                continue;
            }
            String prefix = command.COMMAND_STRING + " ";
            if (line.startsWith(prefix)) {
                command.run(line.substring(prefix.length()));
            }
        }
    }

    static String readLineBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = out.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.UTF_8);
            }
            if (out.size() >= maxBytes) {
                while ((b = in.read()) != -1 && b != '\n') {
                    // discard remainder of overlong line
                }
                throw new LineTooLongException();
            }
            out.write(b);
        }
        if (out.size() == 0) {
            return null;
        }
        byte[] bytes = out.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
            len--;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

}
