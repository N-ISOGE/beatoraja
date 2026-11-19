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
