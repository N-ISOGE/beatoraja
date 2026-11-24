package bms.player.beatoraja.skin;

import bms.player.beatoraja.PixmapResourcePool;
import bms.player.beatoraja.SkinConfig;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static bms.player.beatoraja.skin.SkinProperty.OPTION_RANDOM_VALUE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

public final class SkinCustomization {
    public static final String RANDOM_FILE = "Random";

    private SkinCustomization() { }

    private static Path directory(String definition) {
        int slash = definition.lastIndexOf('/');
        return slash < 0 ? Path.of(".") : Path.of(definition.substring(0, Math.max(1, slash)));
    }

    public static List<String> fileChoices(SkinHeader.CustomFile file) {
        if (file.path == null || file.path.isEmpty()) return List.of();
        return fileChoices(file.path.replace('\\', '/'));
    }

    static List<String> fileChoices(String definition) {
        String pattern = definition.substring(definition.lastIndexOf('/') + 1);
        int firstPipe = pattern.indexOf('|');
        int lastPipe = pattern.lastIndexOf('|');
        String glob = firstPipe >= 0 ? pattern.substring(0, firstPipe) + pattern.substring(lastPipe + 1) : pattern;
        if (glob.isEmpty()) return List.of();
        try {
            Path directory = directory(definition);
            SkinLoader.requirePreviewPath(directory);
            boolean allowLinks = SkinLoader.previewSkinRoot() == null;
            var matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + glob.toLowerCase(Locale.ROOT));
            var result = new ArrayList<String>();
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
                for (Path path : paths) {
                    String name = path.getFileName().toString();
                    boolean isRegular = allowLinks ? Files.isRegularFile(path) : Files.isRegularFile(path, NOFOLLOW_LINKS);
                    if (!name.equals(RANDOM_FILE) && isRegular
                            && matcher.matches(Path.of(name.toLowerCase(Locale.ROOT)))) {
                        if (allowLinks && SkinPathPolicy.isSymlinkOrJunction(path)) {
                            java.util.logging.Logger.getGlobal().warning("Skin file uses symlink or junction: " + path);
                        }
                        result.add(name);
                    }
                }
            }
            result.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder()));
            return result;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    public static String defaultFile(SkinHeader.CustomFile file, List<String> choices) {
        if (file.def != null) {
            for (String name : choices) {
                int dot = name.lastIndexOf('.');
                if (name.equalsIgnoreCase(file.def) || (dot >= 0 && name.substring(0, dot).equalsIgnoreCase(file.def)))
                    return name;
            }
        }
        return choices.isEmpty() ? null : choices.get(0);
    }

    /** Resolve and validate changed choices before the loader uses them to map paths. */
    static Map<String, String> resolveRequiredFiles(SkinHeader header, SkinConfig.Selection selection) {
        if (selection == null) return Map.of();
        for (var change : selection.options()) {
            boolean allowed = Arrays.stream(header.getCustomOptions()).anyMatch(option ->
                    change.name.equals(option.name) && option.option.length > 0 &&
                            (change.value == OPTION_RANDOM_VALUE || Arrays.stream(option.option).anyMatch(id -> id == change.value)));
            if (!allowed) throw new IllegalArgumentException("Skin option no longer available: " + change.name);
        }
        var resolved = new HashMap<String, String>();
        for (var change : selection.files()) {
            var file = Arrays.stream(header.getCustomFiles()).filter(item -> change.name.equals(item.name))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Skin file option no longer available: " + change.name));
            var choices = fileChoices(file);
            String name = change.path;
            if (RANDOM_FILE.equals(name) && !choices.isEmpty())
                name = choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
            if (!choices.contains(name))
                throw new IllegalArgumentException("Skin file no longer available: " + change.name + " / " + change.path);
            var path = directory(file.path.replace('\\', '/')).resolve(name);
            if (!Files.isReadable(path))
                throw new IllegalArgumentException("Skin file is not readable: " + path);
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.matches(".*\\.(png|jpe?g|bmp|gif|webp|tga|cim)$")) {
                var image = PixmapResourcePool.loadPictureResource(path.toString());
                if (image == null) throw new IllegalArgumentException("Skin image could not be decoded: " + path);
                image.dispose();
            }
            resolved.put(change.name, name);
        }
        return resolved;
    }
}
