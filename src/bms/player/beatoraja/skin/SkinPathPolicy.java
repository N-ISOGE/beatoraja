package bms.player.beatoraja.skin;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.logging.Logger;

/** Verifies paths below the skin root. スキンルート配下のパスを検査する。 */
public final class SkinPathPolicy {
    private SkinPathPolicy() { }

    public static boolean isSymlinkOrJunction(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        return Files.isSymbolicLink(normalized)
                || (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && !normalized.equals(normalized.toRealPath()));
    }

    public static Path requireInside(Path root, Path path) throws IOException {
        return requireInside(root, path, false);
    }

    public static Path requireInside(Path root, Path path, boolean allowLinks) throws IOException {
        if (root == null) throw new IOException("Skin directory is not specified");
        Path base = root.toAbsolutePath().normalize();
        Path candidate = path.toAbsolutePath().normalize();
        boolean baseIsDir = allowLinks ? Files.isDirectory(base) : Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS);
        if (!candidate.startsWith(base) || !baseIsDir) {
            throw new IOException("Skin path outside the allowed root");
        }
        if (!base.equals(base.toRealPath())) {
            if (!allowLinks) {
                throw new IOException("Skin path outside the allowed root");
            }
            Logger.getGlobal().warning("Skin root uses symlink or junction: " + base);
        }
        Path component = base;
        for (Path name : base.relativize(candidate)) {
            component = component.resolve(name);
            if (Files.isSymbolicLink(component)
                    || (Files.exists(component, LinkOption.NOFOLLOW_LINKS)
                        && !component.equals(component.toRealPath()))) {
                if (!allowLinks) {
                    throw new IOException("Skin link access denied");
                }
                Logger.getGlobal().warning("Skin path uses symlink or junction: " + component);
            }
        }
        return candidate;
    }

    public static Path resolve(Path root, String text) throws IOException {
        if (root == null) throw new IOException("Skin directory is not specified");
        try {
            Path base = root.toAbsolutePath().normalize();
            Path path = Path.of(text);
            Path workingRelative = path.toAbsolutePath().normalize();
            return requireInside(base, workingRelative.startsWith(base) ? workingRelative : base.resolve(path));
        } catch (InvalidPathException e) {
            throw new IOException("Invalid skin path", e);
        }
    }

    public static byte[] read(Path path, int limit) throws IOException {
        return read(path, limit, false);
    }

    public static byte[] read(Path path, int limit, boolean allowLinks) throws IOException {
        try (InputStream stream = open(path, limit, allowLinks)) {
            return stream.readAllBytes();
        }
    }

    public static InputStream open(Path path, int limit) throws IOException {
        return open(path, limit, false);
    }

    public static InputStream open(Path path, int limit, boolean allowLinks) throws IOException {
        boolean regular = allowLinks ? Files.isRegularFile(path) : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        if (!regular || Files.size(path) > limit) {
            throw new IOException("Skin file unavailable or too large");
        }
        if (allowLinks && isSymlinkOrJunction(path)) {
            Logger.getGlobal().warning("Skin file uses symlink or junction: " + path);
        }
        InputStream raw = allowLinks ? Files.newInputStream(path) : Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
        return new FilterInputStream(raw) {
            private int remaining = limit;

            @Override
            public int read() throws IOException {
                int value = in.read();
                if (value != -1) {
                    if (remaining == 0) throw new IOException("Skin file too large");
                    remaining--;
                }
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int size = in.read(buffer, offset, Math.min(length, remaining + 1));
                if (size > remaining) throw new IOException("Skin file too large");
                if (size > 0) remaining -= size;
                return size;
            }
        };
    }
}
