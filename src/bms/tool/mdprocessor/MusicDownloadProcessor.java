package bms.tool.mdprocessor;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Downloads IPFS song files on a single background worker.
 *
 * @author LNTakeshi
 */
public class MusicDownloadProcessor {
    private static final Logger LOG = Logger.getLogger(MusicDownloadProcessor.class.getName());
    private static final Path DOWNLOAD_ROOT = Path.of("ipfs");
    private static final Set<String> TABLE_HOSTS = Set.of("lnt.softether.net", "www.ribbit.xyz",
            "rattoto10.jounin.jp", "flowermaster.web.fc2.com", "stellawingroad.web.fc2.com",
            "pmsdifficulty.xxxxxxxx.jp", "walkure.net", "stellabms.xyz", "dpbmsdelta.web.fc2.com",
            "nekokan.dyndns.info");

    private final LinkedBlockingQueue<Request> commands = new LinkedBlockingQueue<>();
    private final Set<Key> pending = new HashSet<>();
    private final ConcurrentLinkedDeque<Completion> completed = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
    private final IpfsTransfer transfer;
    private volatile Thread daemon;
    private volatile boolean disposed;
    private volatile boolean downloading;
    private volatile String message = "";

    public final MusicDatabaseAccessor main;

    public MusicDownloadProcessor(String endpoint, MusicDatabaseAccessor main) {
        this(endpoint, main, DOWNLOAD_ROOT);
    }

    MusicDownloadProcessor(String endpoint, MusicDatabaseAccessor main, Path root) {
        this.main = main;
        transfer = new IpfsTransfer(endpoint, root);
    }

    public static boolean isAllowedTable(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI table = URI.create(url).normalize();
            if (table.getHost() == null || table.getRawUserInfo() != null
                    || !("http".equalsIgnoreCase(table.getScheme()) || "https".equalsIgnoreCase(table.getScheme()))) {
                return false;
            }
            String host = table.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("cgi.geocities.jp")) {
                String path = table.getRawPath();
                return !path.contains("%") && (path.equals("/asahi3jpn") || path.startsWith("/asahi3jpn/"));
            }
            return TABLE_HOSTS.contains(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String[] songScanRoots(String[] configured) {
        return songScanRoots(configured, DOWNLOAD_ROOT);
    }

    static String[] songScanRoots(String[] configured, Path directory) {
        Path root;
        try {
            root = IpfsTransfer.realManagedRoot(directory.toAbsolutePath().normalize());
        } catch (IOException e) {
            return configured;
        }
        List<String> roots = new ArrayList<>(Arrays.asList(configured));
        String managed = root.toString();
        roots.removeIf(managed::equals);
        String prefix = managed + File.separator;
        if (!roots.contains(prefix)) {
            roots.add(prefix);
        }
        return roots.toArray(String[]::new);
    }

    public synchronized void start(IpfsInformation song) {
        if (disposed) {
            return;
        }
        if (!isAlive()) {
            daemon = new Thread(this::downloadSongs, "IPFS download");
            daemon.setDaemon(true);
            daemon.start();
        }
        if (song == null) {
            return;
        }

        try {
            Key key = new Key(IpfsTransfer.reference(song.getIpfs()), IpfsTransfer.reference(song.getAppendIpfs()));
            if (key.base() == null && key.diff() == null) {
                return;
            }
            List<String> hashes = song.getOrg_md5();
            if (pending.add(key)) {
                List<String> validHashes = hashes == null ? List.of() : hashes.stream()
                        .filter(h -> h != null && h.matches("[0-9A-Fa-f]{32}"))
                        .map(h -> h.toLowerCase(Locale.ROOT)).distinct().limit(128).toList();
                commands.add(new Request(key, song.getArtist(), song.getTitle(), validHashes));
            }
        } catch (IOException e) {
            failures.add(e.getMessage());
        }
    }

    public void dispose() {
        Thread worker;
        synchronized (this) {
            disposed = true;
            worker = daemon;
            commands.clear();
            completed.clear();
            pending.clear();
        }
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
            try {
                worker.join(750);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isDownload() {
        return downloading;
    }

    public boolean isAlive() {
        Thread worker = daemon;
        return worker != null && worker.isAlive();
    }

    public String getDownloadpath() {
        Completion completion = completed.peek();
        return completion == null ? null : completion.path().toString();
    }

    public synchronized void setDownloadpath(String path) {
        if (path != null) {
            Completion previous = completed.poll();
            completed.addFirst(new Completion(previous == null ? null : previous.key(), Path.of(path)));
            return;
        }
        Completion accepted = completed.poll();
        if (accepted != null) {
            pending.remove(accepted.key());
        }
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String pollFailure() {
        return failures.poll();
    }

    /** Keep the head of the completion queue until the updater accepts it. */
    public synchronized boolean handoff(Predicate<String> accept) {
        String path = getDownloadpath();
        if (path == null || !accept.test(path)) {
            return false;
        }
        setDownloadpath(null);
        return true;
    }

    private void downloadSongs() {
        while (!disposed) {
            Request request;
            try {
                request = commands.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            downloading = true;
            setMessage("IPFS download in progress");
            try {
                Key key = request.key();
                Path parent = key.diff() == null ? null : findParent(request.hashes());
                Path installed = transfer.install(key.base(), key.diff(), request.artist(), request.title(), parent,
                        this::publishInstallation);
                synchronized (this) {
                    if (!disposed) {
                        completed.add(new Completion(key, installed));
                    }
                }
            } catch (IOException | RuntimeException e) {
                synchronized (this) {
                    if (!disposed) {
                        String reason = e instanceof IOException ? e.getMessage() : null;
                        String title = request.title() == null ? "" : request.title().replaceAll("[\\p{Cntrl}]", " ").strip();
                        String label = title.isEmpty() ? "IPFS" : "IPFS [" + title.substring(0, Math.min(80, title.length())) + "]";
                        failures.add(label + ": " + (reason == null ? "Unable to process the song" : reason));
                        LOG.log(Level.FINE, "IPFS download failed", e);
                    }
                    pending.remove(request.key());
                }
            } finally {
                downloading = false;
            }
        }
    }

    private synchronized void publishInstallation(Path staged, Path target) throws IOException {
        if (disposed || Thread.currentThread().isInterrupted()) {
            throw new IOException("IPFS download cancelled");
        }
        Files.move(staged, target);
    }

    private Path findParent(List<String> hashes) {
        if (hashes.isEmpty()) {
            return null;
        }
        String[] candidates = main.getMusicPaths(hashes.toArray(String[]::new));
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate == null || candidate.contains("!")) {
                continue;
            }
            try {
                Path chart = Path.of(candidate).toAbsolutePath().normalize();
                if (Files.isRegularFile(chart, LinkOption.NOFOLLOW_LINKS)) {
                    return chart.getParent();
                }
            } catch (InvalidPathException e) {
                // Ignore a stale path and try the remaining database matches.
            }
        }
        return null;
    }

    private record Key(String base, String diff) {}
    private record Request(Key key, String artist, String title, List<String> hashes) {}
    private record Completion(Key key, Path path) {}
}
