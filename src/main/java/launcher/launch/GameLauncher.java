package launcher.launch;

import launcher.auth.MicrosoftAuth;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/** Скачивает всё необходимое для версии (Fabric) и запускает Minecraft отдельным процессом. */
public final class GameLauncher {

    private final Path root; // корень данных лаунчера, например ~/.my-launcher
    private final VersionResolver resolver = new VersionResolver();
    private final Downloader downloader = new Downloader();
    private final JavaLocator javaLocator = new JavaLocator();

    public GameLauncher(Path root) {
        this.root = root;
    }

    public interface ProgressListener {
        void onStage(String stage);
        void onProgress(int done, int total);
    }

    /** javaPath пустой/null — подбираем сами (см. resolveJava). windowWidth/Height 0 — не указывать игре. */
    public record LaunchOptions(int minMemoryMb, int maxMemoryMb, String javaPath, int windowWidth, int windowHeight) {
        public static LaunchOptions defaults() {
            return new LaunchOptions(1024, 4096, "", 0, 0);
        }
    }

    private record JavaResolution(String javaBin, boolean sufficient, int detectedMajor) {}

    /**
     * Папка игры (мир, mods/, resourcepacks/, shaderpacks/) — отдельная под каждую версию,
     * чтобы мод под Fabric 26.3 не оказался в mods/ при запуске 1.21.1 и не сломал загрузку
     * (Fabric сам проверяет совместимость мода с версией игры и Java, и совершенно правильно
     * откажется стартовать при несовпадении).
     */
    public Path gameDir(String gameVersion) {
        return root.resolve("instances").resolve(sanitize(gameVersion));
    }

    private String sanitize(String version) {
        return version.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public Process launch(String gameVersion, MicrosoftAuth.AuthResult account, LaunchOptions options,
                           ProgressListener progress) throws Exception {

        progress.onStage("Получаем информацию о версии " + gameVersion + " и Fabric...");
        ResolvedVersion version = resolver.resolveFabric(gameVersion, null);

        JavaResolution java = resolveJava(options.javaPath(), version.javaMajorVersion);
        String javaWarning = null;
        if (!java.sufficient()) {
            javaWarning = "версии " + gameVersion + " нужна Java " + version.javaMajorVersion
                    + " или новее, а подходящей не нашлось на компьютере (доступна только Java " + java.detectedMajor() + ")";
            progress.onStage("Внимание: " + javaWarning);
        } else if (java.detectedMajor() != Runtime.version().feature()) {
            progress.onStage("Нашёл на диске подходящую Java " + java.detectedMajor() + " и использую её для запуска");
        }

        Path librariesDir = root.resolve("libraries");
        Path versionsDir = root.resolve("versions").resolve(version.id);
        Path assetsDir = root.resolve("assets");
        Path nativesDir = versionsDir.resolve("natives");
        Path gameDir = gameDir(gameVersion);
        Files.createDirectories(librariesDir);
        Files.createDirectories(versionsDir);
        Files.createDirectories(nativesDir);
        Files.createDirectories(gameDir);

        Path clientJar = versionsDir.resolve(version.id + ".jar");

        progress.onStage("Скачиваем библиотеки и клиент игры...");
        List<Downloader.FileTask> libTasks = new ArrayList<>();
        for (LibraryEntry lib : version.libraries) {
            libTasks.add(lib.toFileTask(librariesDir));
        }
        libTasks.add(new Downloader.FileTask(version.clientJarUrl, clientJar, version.clientJarSha1));
        downloader.downloadAll(libTasks, progress::onProgress);

        progress.onStage("Скачиваем ассеты (звуки, текстуры)...");
        new AssetManager(assetsDir).ensureAssets(version.assetIndexId, version.assetIndexUrl, progress::onProgress);

        progress.onStage("Распаковываем нативные библиотеки...");
        extractNatives(version, librariesDir, nativesDir);

        progress.onStage("Запускаем игру...");
        List<String> command = buildCommand(version, clientJar, librariesDir, nativesDir, gameDir, assetsDir,
                account, options, java.javaBin());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(gameDir.toFile());
        pb.redirectErrorStream(true);
        // логи игры пока просто видно в консоли самого лаунчера (в IntelliJ — во вкладке Run);
        // позже это стоит завести в отдельное окно/панель с логами в UI
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        Process process = pb.start();

        // если процесс уже умер практически сразу — это почти всегда несовместимость
        // (несовпадение версии Java или мод, оставшийся от другой версии игры),
        // а не успешный запуск с медленной отрисовкой окна
        Thread.sleep(3000);
        if (!process.isAlive()) {
            int exitCode = process.exitValue();
            String reason;
            if (javaWarning != null) {
                reason = "скорее всего дело в Java: " + javaWarning;
            } else if ("legacy".equals(account.userType())) {
                reason = "похоже, дело в офлайн-профиле («Пропустить»): начиная примерно с 1.19 клиент "
                        + "при старте сам обращается к серверам Mojang для проверки сессии, и с фейковым "
                        + "токеном офлайн-профиля это падает с MinecraftClientHttpException: Status 401 прямо "
                        + "на старте. Это поведение самой игры, лаунчер не может это обойти — офлайн-профиль "
                        + "годится только для проверки скачивания/Fabric/модов, а не для реального запуска "
                        + "на таких версиях. Для игры нужен настоящий вход через Microsoft.";
            } else {
                reason = "смотри полный вывод в консоли лаунчера (Run-вкладка IntelliJ) — там настоящая причина "
                      + "(например, несовместимый мод в текущей версии — проверь вкладку «Моды»)";
            }
            throw new RuntimeException("Игра завершилась сразу после запуска (код " + exitCode + ") — " + reason);
        }
        return process;
    }

    /**
     * Порядок выбора Java: (1) явно указанный путь в настройках, (2) Java самого лаунчера,
     * если она уже подходит по версии, (3) автопоиск подходящей JDK на диске (стандартные
     * места установки .pkg/.msi), (4) если ничего не нашли — всё равно Java лаунчера, но с предупреждением.
     */
    private JavaResolution resolveJava(String customJavaPath, int requiredMajorVersion) {
        if (customJavaPath != null && !customJavaPath.isBlank() && Files.exists(Path.of(customJavaPath))) {
            return new JavaResolution(customJavaPath, true, requiredMajorVersion);
        }

        int runningJava = Runtime.version().feature();
        if (runningJava >= requiredMajorVersion) {
            return new JavaResolution(defaultJavaBin(), true, runningJava);
        }

        JavaLocator.JavaInstall found = javaLocator.findBestFor(requiredMajorVersion);
        if (found != null) {
            return new JavaResolution(found.javaBinary().toString(), true, found.majorVersion());
        }

        return new JavaResolution(defaultJavaBin(), false, runningJava);
    }

    private String defaultJavaBin() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }

    private void extractNatives(ResolvedVersion version, Path librariesDir, Path nativesDir) throws IOException {
        for (LibraryEntry lib : version.libraries) {
            if (!lib.isNative()) continue;
            Path jarPath = librariesDir.resolve(lib.path());
            if (!Files.exists(jarPath)) continue;

            try (JarFile jar = new JarFile(jarPath.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || name.startsWith("META-INF/")) continue;
                    if (!(name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib") || name.endsWith(".jnilib"))) {
                        continue;
                    }
                    Path outFile = nativesDir.resolve(Path.of(name).getFileName().toString());
                    try (var in = jar.getInputStream(entry)) {
                        Files.copy(in, outFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private List<String> buildCommand(ResolvedVersion version, Path clientJar, Path librariesDir, Path nativesDir,
                                       Path gameDir, Path assetsDir, MicrosoftAuth.AuthResult account,
                                       LaunchOptions options, String javaBin) {

        String classpath = buildClasspath(version, librariesDir, clientJar);

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("auth_player_name", account.username());
        placeholders.put("version_name", version.id);
        placeholders.put("game_directory", gameDir.toString());
        placeholders.put("assets_root", assetsDir.toString());
        placeholders.put("assets_index_name", version.assetIndexId);
        placeholders.put("auth_uuid", account.uuid());
        placeholders.put("auth_access_token", account.minecraftAccessToken());
        placeholders.put("clientid", "my-launcher");
        placeholders.put("auth_xuid", account.uuid());
        placeholders.put("user_type", account.userType());
        placeholders.put("version_type", "release");
        placeholders.put("natives_directory", nativesDir.toString());
        placeholders.put("launcher_name", "my-launcher");
        placeholders.put("launcher_version", "0.1.0");
        placeholders.put("classpath", classpath);

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-Xms" + options.minMemoryMb() + "M");
        command.add("-Xmx" + options.maxMemoryMb() + "M");

        for (String jvmArg : version.jvmArgs) {
            command.add(substitute(jvmArg, placeholders));
        }
        // подстраховки на случай, если в JSON версии почему-то не нашлось нужных флагов
        if (version.jvmArgs.stream().noneMatch(a -> a.contains("java.library.path"))) {
            command.add("-Djava.library.path=" + nativesDir);
        }
        if (VersionResolver.currentOsKey().equals("osx")
                && version.jvmArgs.stream().noneMatch(a -> a.contains("XstartOnFirstThread"))) {
            command.add("-XstartOnFirstThread");
        }
        if (version.jvmArgs.stream().noneMatch(a -> a.equals("-cp") || a.equals("${classpath}"))) {
            command.add("-cp");
            command.add(classpath);
        }

        command.add(version.mainClass);

        for (String gameArg : version.gameArgs) {
            command.add(substitute(gameArg, placeholders));
        }

        // ширина/высота окна не зависят от плейсхолдеров версии — добавляем сами, если задано в настройках
        if (options.windowWidth() > 0 && options.windowHeight() > 0) {
            command.add("--width");
            command.add(String.valueOf(options.windowWidth()));
            command.add("--height");
            command.add(String.valueOf(options.windowHeight()));
        }

        return command;
    }

    private String buildClasspath(ResolvedVersion version, Path librariesDir, Path clientJar) {
        String separator = File.pathSeparator;
        return version.libraries.stream()
                .filter(lib -> !lib.isNative())
                .map(lib -> librariesDir.resolve(lib.path()).toString())
                .distinct()
                .collect(Collectors.joining(separator)) + separator + clientJar;
    }

    private String substitute(String template, Map<String, String> placeholders) {
        String result = template;
        for (var e : placeholders.entrySet()) {
            result = result.replace("${" + e.getKey() + "}", e.getValue());
        }
        return result;
    }
}
