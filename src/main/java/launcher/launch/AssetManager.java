package launcher.launch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Ассеты (звуки, языковые файлы и часть текстур) хранятся отдельно от client.jar,
 * в папке assets/objects/xx/xxhash, и адресуются по sha1-хэшу через индекс ассетов.
 * Так было устроено с версии 1.7.2 — это НЕ дублирует содержимое client.jar.
 */
public final class AssetManager {

    private final HttpClient http = HttpClient.newHttpClient();
    private final Downloader downloader = new Downloader();
    private final Path assetsRoot;

    public AssetManager(Path assetsRoot) {
        this.assetsRoot = assetsRoot;
    }

    public void ensureAssets(String assetIndexId, String assetIndexUrl, BiConsumer<Integer, Integer> onProgress) throws Exception {
        Path indexFile = assetsRoot.resolve("indexes").resolve(assetIndexId + ".json");
        Files.createDirectories(indexFile.getParent());

        String indexBody;
        if (!Files.exists(indexFile)) {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(assetIndexUrl)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            indexBody = resp.body();
            Files.writeString(indexFile, indexBody);
        } else {
            indexBody = Files.readString(indexFile);
        }

        JsonObject index = JsonParser.parseString(indexBody).getAsJsonObject();
        JsonObject objects = index.getAsJsonObject("objects");

        List<Downloader.FileTask> tasks = new ArrayList<>();
        for (String key : objects.keySet()) {
            String hash = objects.getAsJsonObject(key).get("hash").getAsString();
            String prefix = hash.substring(0, 2);
            Path dest = assetsRoot.resolve("objects").resolve(prefix).resolve(hash);
            tasks.add(new Downloader.FileTask("https://resources.download.minecraft.net/" + prefix + "/" + hash, dest, hash));
        }
        downloader.downloadAll(tasks, onProgress);
    }
}
