package launcher.mods;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Управляет всем, что установлено из Магазина: моды (mods/), ресурспаки (resourcepacks/)
 * и шейдеры (shaderpacks/) под текущей версией. "Выключить" по-настоящему поддерживают
 * только моды — переименование в .jar.disabled, чтобы Fabric их не подхватил.
 * Ресурспаки и шейдеры так не переключаются (игра сама хранит список активных
 * в options.txt/optionsshaders.txt), поэтому здесь их можно только увидеть и удалить —
 * включают их прямо в игре, в её собственных меню.
 */
public final class ModsManager {

    public enum ContentType { MOD, RESOURCEPACK, SHADER }

    private final Path modsDir;
    private final Path resourcepacksDir;
    private final Path shaderpacksDir;

    public ModsManager(Path gameDir) {
        this.modsDir = gameDir.resolve("mods");
        this.resourcepacksDir = gameDir.resolve("resourcepacks");
        this.shaderpacksDir = gameDir.resolve("shaderpacks");
    }

    public record InstalledMod(String displayName, Path path, boolean enabled, ContentType type) {}

    public List<InstalledMod> listMods() throws IOException {
        List<InstalledMod> result = new ArrayList<>();
        collect(modsDir, ContentType.MOD, result);
        collect(resourcepacksDir, ContentType.RESOURCEPACK, result);
        collect(shaderpacksDir, ContentType.SHADER, result);
        return result;
    }

    private void collect(Path dir, ContentType type, List<InstalledMod> out) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> type == ContentType.MOD
                            ? (p.toString().endsWith(".jar") || p.toString().endsWith(".jar.disabled"))
                            : Files.isRegularFile(p))
                    .sorted()
                    .forEach(p -> {
                        boolean enabled = !p.toString().endsWith(".disabled");
                        String name = p.getFileName().toString().replace(".disabled", "");
                        out.add(new InstalledMod(name, p, enabled, type));
                    });
        }
    }

    /** Переключение файлом доступно только модам — ресурспаки/шейдеры так не работают (см. класс). */
    public void setEnabled(InstalledMod mod, boolean enabled) throws IOException {
        if (mod.type() != ContentType.MOD) return;
        String pathStr = mod.path().toString();
        boolean currentlyDisabled = pathStr.endsWith(".disabled");
        if (enabled == !currentlyDisabled) {
            return; // уже в нужном состоянии
        }
        Path target = enabled
                ? Path.of(pathStr.substring(0, pathStr.length() - ".disabled".length()))
                : Path.of(pathStr + ".disabled");
        Files.move(mod.path(), target, StandardCopyOption.REPLACE_EXISTING);
    }

    public void delete(InstalledMod mod) throws IOException {
        Files.deleteIfExists(mod.path());
    }
}
