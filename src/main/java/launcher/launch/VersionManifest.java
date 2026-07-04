package launcher.launch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** Список версий Minecraft и подробности конкретной версии, с сайта Mojang. */
public final class VersionManifest {

    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final HttpClient http = HttpClient.newHttpClient();

    public record VersionEntry(String id, String type, String url) {}

    /** Все версии (release и snapshot), в порядке из манифеста Mojang — новые сверху. */
    public List<VersionEntry> fetchVersions() throws Exception {
        JsonObject manifest = getJson(MANIFEST_URL);
        List<VersionEntry> result = new ArrayList<>();
        for (var el : manifest.getAsJsonArray("versions")) {
            JsonObject v = el.getAsJsonObject();
            result.add(new VersionEntry(v.get("id").getAsString(), v.get("type").getAsString(), v.get("url").getAsString()));
        }
        return result;
    }

    public String latestRelease() throws Exception {
        JsonObject manifest = getJson(MANIFEST_URL);
        return manifest.getAsJsonObject("latest").get("release").getAsString();
    }

    /** Полный JSON конкретной версии по ссылке из fetchVersions(). */
    public JsonObject fetchVersionDetail(String url) throws Exception {
        return getJson(url);
    }

    private JsonObject getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }
}
