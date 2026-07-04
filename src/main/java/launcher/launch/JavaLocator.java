package launcher.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Сканирует стандартные места установки JDK на диске (те же, куда кладут файлы
 * официальные .pkg/.msi инсталляторы Temurin/Oracle/Corretto), чтобы найти подходящую
 * версию Java автоматически — без необходимости руками прописывать путь в настройках.
 */
public final class JavaLocator {

    public record JavaInstall(int majorVersion, Path javaBinary, Path home) {}

    public List<JavaInstall> findInstalledJavas() {
        List<JavaInstall> found = new ArrayList<>();
        for (Path home : candidateHomes()) {
            readMajorVersion(home).ifPresent(major -> {
                Path bin = home.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
                if (Files.isExecutable(bin) && found.stream().noneMatch(j -> j.home().equals(home))) {
                    found.add(new JavaInstall(major, bin, home));
                }
            });
        }
        return found;
    }

    /** Лучшая установленная Java для нужной версии (сама нужная или ближайшая новее). null, если ничего не подходит. */
    public JavaInstall findBestFor(int requiredMajor) {
        return findInstalledJavas().stream()
                .filter(j -> j.majorVersion() >= requiredMajor)
                .min(Comparator.comparingInt(JavaInstall::majorVersion))
                .orElse(null);
    }

    private List<Path> candidateHomes() {
        List<Path> homes = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("mac")) {
                addChildren(homes, Path.of("/Library/Java/JavaVirtualMachines"), "Contents/Home");
                addChildren(homes, Path.of(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines"), "Contents/Home");
            } else if (os.contains("win")) {
                addChildren(homes, Path.of("C:/Program Files/Java"), "");
                addChildren(homes, Path.of("C:/Program Files/Eclipse Adoptium"), "");
                addChildren(homes, Path.of("C:/Program Files/Microsoft"), "");
                addChildren(homes, Path.of("C:/Program Files/Amazon Corretto"), "");
            } else {
                addChildren(homes, Path.of("/usr/lib/jvm"), "");
            }
        } catch (Exception ignored) {
            // папки может не быть вовсе — это нормально, просто не добавляем кандидатов оттуда
        }
        return homes;
    }

    private void addChildren(List<Path> out, Path parent, String suffix) throws IOException {
        if (!Files.isDirectory(parent)) return;
        try (Stream<Path> children = Files.list(parent)) {
            children.forEach(child -> {
                Path home = suffix.isEmpty() ? child : child.resolve(suffix);
                if (Files.isDirectory(home)) out.add(home);
            });
        }
    }

    /** Читает JAVA_VERSION из файла release внутри JAVA_HOME — есть у любого нормального JDK начиная с Java 9. */
    private Optional<Integer> readMajorVersion(Path home) {
        try {
            Path releaseFile = home.resolve("release");
            if (!Files.exists(releaseFile)) return Optional.empty();
            for (String line : Files.readAllLines(releaseFile)) {
                if (line.startsWith("JAVA_VERSION=")) {
                    String version = line.substring("JAVA_VERSION=".length()).replace("\"", "");
                    String major = version.split("[.\\-+]")[0];
                    return Optional.of(Integer.parseInt(major));
                }
            }
        } catch (Exception ignored) {
            // нечитаемый/непонятный release-файл — просто пропускаем этого кандидата
        }
        return Optional.empty();
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
