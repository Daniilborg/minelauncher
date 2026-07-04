package launcher.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

/** Настройки лаунчера: память JVM, свой путь к Java, разрешение окна, запомненный никнейм. Хранится в settings.json. */
public final class LauncherSettings {

    public int minMemoryMb = 1024;
    public int maxMemoryMb = 4096;
    public String javaPath = "";      // пусто = использовать Java, на которой запущен сам лаунчер
    public int windowWidth = 0;       // 0 = не указывать, пусть игра решает сама
    public int windowHeight = 0;
    public String nickname = "";      // пусто = использовать имя из аккаунта как есть

    private static final Path FILE = Path.of(System.getProperty("user.home"), ".my-launcher", "settings.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static LauncherSettings load() {
        try {
            if (Files.exists(FILE)) {
                LauncherSettings loaded = GSON.fromJson(Files.readString(FILE), LauncherSettings.class);
                if (loaded != null) return loaded;
            }
        } catch (Exception ignored) {
            // повреждённый или нечитаемый файл настроек — просто начнём с настроек по умолчанию
        }
        return new LauncherSettings();
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this));
        } catch (Exception ignored) {
            // не критично — при следующем запуске просто попробуем снова
        }
    }
}
