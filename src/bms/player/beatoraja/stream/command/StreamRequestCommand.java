package bms.player.beatoraja.stream.command;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import bms.player.beatoraja.MessageRenderer;
import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.select.bar.HashBar;
import bms.player.beatoraja.song.SongData;

/**
 * reqコマンドの処理
 */
public class StreamRequestCommand extends StreamCommand {
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final int MAX_PENDING_REQUESTS = 1024;
    private static final long DISPOSE_JOIN_MILLIS = 500L;

    MusicSelector selector;
    MessageRenderer notifier;
    int maxLength = 30;
    Thread updaterThread;
    UpdateBar updater;
    private final Consumer<Runnable> uiDispatcher;
    private volatile boolean disposed;

    public StreamRequestCommand(MusicSelector selector, MessageRenderer notifier) {
        this(selector, notifier, resolveMaxLength(selector), StreamRequestCommand::postToRenderThread);
    }

    StreamRequestCommand(MusicSelector selector, MessageRenderer notifier, int maxLength,
            Consumer<Runnable> uiDispatcher) {
        COMMAND_STRING = "!!req";
        this.selector = selector;
        this.notifier = notifier;
        this.maxLength = Math.max(0, maxLength);
        this.uiDispatcher = uiDispatcher != null ? uiDispatcher : StreamRequestCommand::postToRenderThread;
        this.updater = new UpdateBar();
        this.updaterThread = new Thread(updater, "stream-request-updater");
        this.updaterThread.setDaemon(true);
        this.updaterThread.start();
    }

    private static int resolveMaxLength(MusicSelector selector) {
        if (selector != null && selector.main != null && selector.main.getPlayerConfig() != null) {
            return Math.max(0, selector.main.getPlayerConfig().getMaxRequestCount());
        }
        return 30;
    }

    private static void postToRenderThread(Runnable task) {
        if (Gdx.app != null) {
            Gdx.app.postRunnable(task);
        }
    }

    static boolean isValidSha256(String data) {
        return data != null && SHA256_PATTERN.matcher(data).matches();
    }

    @Override
    public void run(String data) {
        if (disposed || !isValidSha256(data)) {
            return;
        }
        updater.enqueue(data.toLowerCase(Locale.ROOT));
    }

    @Override
    public void dispose() {
        disposed = true;
        Thread thread = updaterThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(DISPOSE_JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    class UpdateBar implements Runnable {
        final int MESSAGE_TIME = 3000;

        final HashBar bar;
        final List<SongData> songDatas = new ArrayList<>();
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(MAX_PENDING_REQUESTS);

        UpdateBar() {
            this.bar = new HashBar(selector, "Stream Request", new SongData[0]);
            this.bar.setSortable(false);
        }

        void enqueue(String sha256) {
            queue.offer(sha256);
        }

        private void processRequest(String sha256) {
            for (SongData existing : songDatas) {
                if (existing != null && existing.getSha256() != null
                        && existing.getSha256().equalsIgnoreCase(sha256)) {
                    if (notifier != null) {
                        notifier.addMessage(existing.getFullTitle() + " はリクエスト済です", MESSAGE_TIME, Color.ORANGE, 0);
                    }
                    return;
                }
            }
            if (maxLength <= 0 || selector == null || selector.getSongDatabase() == null) {
                return;
            }
            SongData[] found = selector.getSongDatabase().getSongDatas(new String[] { sha256 });
            if (found == null || found.length == 0 || found[0] == null) {
                if (notifier != null) {
                    notifier.addMessage("リクエストされた譜面を所持していません", MESSAGE_TIME, Color.ORANGE, 0);
                }
                return;
            }
            SongData song = found[0];
            songDatas.add(song);
            while (songDatas.size() > maxLength) {
                songDatas.remove(0);
            }
            if (notifier != null) {
                notifier.addMessage("リクエスト追加: " + song.getFullTitle(), MESSAGE_TIME, Color.LIME, 0);
            }
            SongData[] snapshot = songDatas.toArray(new SongData[0]);
            uiDispatcher.accept(() -> {
                if (disposed) {
                    return;
                }
                bar.setElements(snapshot);
                try {
                    if (selector.getBarManager() != null) {
                        selector.getBarManager().setAppendDirectoryBar("Stream Request", bar);
                        if (selector.main != null && selector.main.getCurrentState() instanceof MusicSelector) {
                            selector.getBarManager().updateBar();
                        }
                    }
                } catch (Exception e) {
                    Logger.getGlobal().warning("Stream request bar update failed: " + e.getClass().getSimpleName());
                }
            });
        }

        @Override
        public void run() {
            while (!disposed && !Thread.currentThread().isInterrupted()) {
                try {
                    String sha256 = queue.take();
                    if (disposed) {
                        break;
                    }
                    processRequest(sha256);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException e) {
                    Logger.getGlobal().warning("Stream request processing failed: " + e.getClass().getSimpleName());
                }
            }
        }
    }
}
