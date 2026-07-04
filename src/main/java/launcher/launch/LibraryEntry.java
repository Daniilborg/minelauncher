package launcher.launch;

import java.nio.file.Path;

/**
 * Одна библиотека из version JSON: относительный путь внутри папки libraries,
 * URL для скачивания, sha1 (может быть null) и флаг "это натив" (dll/so/dylib,
 * который нужно не класть в classpath, а распаковать в папку natives).
 */
public record LibraryEntry(String path, String url, String sha1, boolean isNative) {

    public Downloader.FileTask toFileTask(Path librariesRoot) {
        return new Downloader.FileTask(url, librariesRoot.resolve(path), sha1);
    }
}
