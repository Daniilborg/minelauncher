package launcher.launch;

import java.util.ArrayList;
import java.util.List;

/**
 * Результат объединения ванильного version JSON и профиля Fabric —
 * всё, что нужно, чтобы скачать файлы и собрать команду запуска.
 */
public final class ResolvedVersion {
    public String id;
    public String mainClass;
    public String assetIndexId;
    public String assetIndexUrl;
    public String clientJarUrl;
    public String clientJarSha1;
    public int javaMajorVersion = 17;

    public final List<LibraryEntry> libraries = new ArrayList<>();
    public final List<String> gameArgs = new ArrayList<>();
    public final List<String> jvmArgs = new ArrayList<>();
}
