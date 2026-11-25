package bms.player.beatoraja.skin;

import com.badlogic.gdx.utils.Json;
import bms.player.beatoraja.skin.json.JsonSkinSerializer;
import bms.player.beatoraja.skin.json.JsonSkin;
import bms.player.beatoraja.skin.json.JSONSkinLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class D4SkinPolicyTest {
    public static void main(String[] args) throws Exception {
        check(SkinTextReader.decode("\ufeff\u97d3".getBytes(StandardCharsets.UTF_8)).equals("\u97d3"));
        check(SkinTextReader.decode("\ufeff\u97d3".getBytes(StandardCharsets.UTF_16LE)).equals("\u97d3"));
        check(SkinTextReader.decode("\u65e5\u672c".getBytes(Charset.forName("windows-31j"))).equals("\u65e5\u672c"));
        check(SkinTextReader.decode("\ubc1d".getBytes(Charset.forName("x-windows-949"))).equals("\ubc1d"));

        Path root = Files.createTempDirectory("d4-skin-policy");
        Path entry = root.resolve("main.json");
        Path image = root.resolve("Bg-final.PnG");
        Path unrelated = root.resolve("icon.png");
        Path savedInclude = root.resolve("saved.json");
        Path parts = Files.createDirectory(root.resolve("parts"));
        try {
            Files.createFile(image);
            Files.createFile(unrelated);
            String glob = root.toString().replace('\\', '/') + "/bg-*.png";
            check(SkinLoader.getPath(glob, new com.badlogic.gdx.utils.ObjectMap<>()).toPath().equals(image));
            Files.writeString(entry, """
                    {"type":0,"name":"D4","w":640,"h":480,
                     "property":[{"include":"parts/props.json"},{"name":"local","item":[]}]}
                    """);
            Files.writeString(parts.resolve("props.json"), """
                    [{"include":"nested.json"},{"include":"props.json"}]
                    """);
            Files.writeString(parts.resolve("nested.json"), """
                    [{"name":"child","item":[]}]
                    """);
            var header = new JSONSkinLoader().loadHeader(entry);
            check(header != null && header.getCustomOptions().length == 2);
            check(header.getCustomOptions()[0].name.equals("child"));
            check(header.getCustomOptions()[1].name.equals("local"));
            check(SkinPathPolicy.read(entry, (int) Files.size(entry)).length == Files.size(entry));

            Files.writeString(savedInclude, """
                    [{"name":"saved","item":[]}]
                    """);
            Path currentDirectory = Path.of("").toAbsolutePath();
            Path relativeEntry = currentDirectory.relativize(entry);
            String expected = relativeEntry.getParent().resolve("parts/props.json").toString().replace('\\', '/');
            var json = new Json();
            new JsonSkinSerializer(null, name -> name.equals(expected) ? savedInclude.toFile() : Path.of(name).toFile())
                    .setSerializers(json, null, relativeEntry);
            try (var reader = SkinTextReader.open(entry)) {
                var model = json.fromJson(JsonSkin.Skin.class, reader);
                check(model.property.length == 2 && model.property[0].name.equals("saved"));
            }

            // 1. SkinTextReader.decode fallback: invalid UTF-8/MS932/MS949 -> WARNING + MS932 REPLACE
            var warnings = new java.util.ArrayList<String>();
            var handler = new java.util.logging.Handler() {
                @Override public void publish(java.util.logging.LogRecord r) {
                    if (r.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                        warnings.add(r.getMessage());
                    }
                }
                @Override public void flush() { }
                @Override public void close() { }
            };
            java.util.logging.Logger.getGlobal().addHandler(handler);
            try {
                // 0x81 0x20 is invalid in UTF-8, MS932 (0x20 is invalid trail byte), and MS949
                String replaced = SkinTextReader.decode(new byte[] { (byte) 0x81, 0x20, 'A' });
                check(replaced.endsWith("A") && replaced.contains("\ufffd"));
                check(warnings.stream().anyMatch(w -> w.contains("MS932")));

                // 2. '|' custom file glob: *|x|.png matches *.png
                String pipeGlob = root.toString().replace('\\', '/') + "/*|x|.png";
                var choices = SkinCustomization.fileChoices(pipeGlob);
                check(choices.contains("Bg-final.PnG") && choices.contains("icon.png"));

                // 3. JSON '../' include: normal play warns & allows, preview mode strictly blocks
                Path subSkin = Files.createDirectory(root.resolve("subskin"));
                Path subEntry = subSkin.resolve("sub.json");
                Path parentInclude = root.resolve("parent_props.json");
                Files.writeString(parentInclude, "[{\"name\":\"parentOpt\",\"item\":[]}]");
                Files.writeString(subEntry, "{\"type\":0,\"name\":\"Sub\",\"w\":640,\"h\":480,\"property\":[{\"include\":\"../parent_props.json\"}]}");
                try {
                    warnings.clear();
                    var normalHeader = new JSONSkinLoader().loadHeader(subEntry);
                    check(normalHeader != null && normalHeader.getCustomOptions().length == 1
                            && normalHeader.getCustomOptions()[0].name.equals("parentOpt"));
                    check(warnings.stream().anyMatch(w -> w.contains("outside skin root")));

                    var strictJson = new Json();
                    strictJson.setIgnoreUnknownFields(true);
                    new JsonSkinSerializer(null, name -> Path.of(name).toFile(), subSkin)
                            .setSerializers(strictJson, null, subEntry);
                    try (var r = SkinTextReader.open(subEntry)) {
                        var strictModel = strictJson.fromJson(JsonSkin.Skin.class, r);
                        check(strictModel.property == null || strictModel.property.length == 0
                                || strictModel.property[0] == null);
                    }
                } finally {
                    Files.deleteIfExists(subEntry);
                    Files.deleteIfExists(parentInclude);
                    Files.deleteIfExists(subSkin);
                }

                // 4. Symlink / junction: normal play warns & allows, preview strict blocks
                Path targetDir = Files.createDirectory(root.resolve("link_target"));
                Path targetSkin = targetDir.resolve("linked.json");
                Files.writeString(targetSkin, "{\"type\":0,\"name\":\"Linked\",\"w\":640,\"h\":480}");
                Path linkDir = root.resolve("link_alias");
                boolean linkCreated = false;
                try {
                    try {
                        Files.createSymbolicLink(linkDir, targetDir);
                        linkCreated = true;
                    } catch (UnsupportedOperationException | java.io.IOException e) {
                        if (java.io.File.separatorChar == '\\') {
                            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                                    linkDir.toAbsolutePath().toString(),
                                    targetDir.toAbsolutePath().toString())
                                    .redirectErrorStream(true).start();
                            if (p.waitFor() == 0 && Files.exists(linkDir)) {
                                linkCreated = true;
                            }
                        }
                    }
                    if (linkCreated) {
                        warnings.clear();
                        Path linkedFile = linkDir.resolve("linked.json");
                        check(SkinPathPolicy.requireInside(root, linkedFile, true).equals(linkedFile.toAbsolutePath().normalize()));
                        check(SkinPathPolicy.read(linkedFile, 4096, true).length > 0);
                        check(SkinCatalog.scanSkinFiles(root).stream().anyMatch(p -> p.endsWith("linked.json")));
                        check(warnings.stream().anyMatch(w -> w.contains("symlink or junction")));

                        boolean blocked = false;
                        try {
                            SkinPathPolicy.requireInside(root, linkedFile);
                        } catch (java.io.IOException expectedBlocked) {
                            blocked = true;
                        }
                        check(blocked);
                    } else {
                        System.out.println("D4SkinPolicyTest: link case skipped (symlink/junction could not be created)");
                    }
                } finally {
                    if (linkCreated) {
                        Files.deleteIfExists(linkDir);
                    }
                    Files.deleteIfExists(targetSkin);
                    Files.deleteIfExists(targetDir);
                }
            } finally {
                java.util.logging.Logger.getGlobal().removeHandler(handler);
            }
        } finally {
            Files.deleteIfExists(parts.resolve("nested.json"));
            Files.deleteIfExists(parts.resolve("props.json"));
            Files.deleteIfExists(parts);
            Files.deleteIfExists(image);
            Files.deleteIfExists(unrelated);
            Files.deleteIfExists(savedInclude);
            Files.deleteIfExists(entry);
            Files.deleteIfExists(root);
        }
        System.out.println("D4SkinPolicyTest passed");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("D4 skin policy expectation failed");
    }
}
