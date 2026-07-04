package launcher.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Клиент модринтовского API (api.modrinth.com/v2) — бесплатный, без ключа,
 * покрывает моды, ресурспаки и шейдеры в одном поиске (project_type).
 * Modrinth просит уникальный User-Agent на каждый запрос — иначе могут блокировать трафик.
 */
public final class ModrinthClient {

    private static final String BASE = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "my-launcher/0.1.0 (personal Fabric launcher project)";

    private final HttpClient http = HttpClient.newHttpClient();

    public record DownloadFile(String url, String filename, String sha1) {}

    /** Поиск проектов. projectType — "mod" / "resourcepack" / "shader". gameVersion можно передать null/пустым. */
    public List<ModrinthProject> search(String query, String projectType, String gameVersion, int limit) throws Exception {
        StringBuilder facets = new StringBuilder("[[\"project_type:").append(projectType).append("\"]");
        if (gameVersion != null && !gameVersion.isBlank() && Character.isDigit(gameVersion.charAt(0))) {
            facets.append(",[\"versions:").append(gameVersion).append("\"]");
        }
        facets.append("]");

        String url = BASE + "/search?query=" + enc(query)
                + "&facets=" + enc(facets.toString())
                + "&index=downloads&limit=" + limit;

        JsonObject json = getJson(url);
        List<ModrinthProject> out = new ArrayList<>();
        for (JsonElement el : json.getAsJsonArray("hits")) {
            JsonObject hit = el.getAsJsonObject();
            out.add(new ModrinthProject(
                    str(hit, "project_id", ""),
                    str(hit, "slug", ""),
                    str(hit, "title", "Без названия"),
                    str(hit, "description", ""),
                    hit.has("icon_url") && !hit.get("icon_url").isJsonNull() ? hit.get("icon_url").getAsString() : null,
                    hit.has("downloads") ? hit.get("downloads").getAsInt() : 0,
                    str(hit, "author", ""),
                    str(hit, "project_type", projectType)
            ));
        }
        return out;
    }

    /** Полное описание (markdown-текст) — для карточки с подробностями. */
    public String getFullDescription(String projectId) throws Exception {
        JsonObject json = getJson(BASE + "/project/" + enc(projectId));
        return str(json, "body", "");
    }

    /** Подбирает лучший файл для скачивания под конкретную версию игры (release приоритетнее, иначе первая версия). */
    public DownloadFile findBestFile(String projectId, String projectType, String gameVersion) throws Exception {
        String url = BASE + "/project/" + enc(projectId) + "/version?game_versions=" + enc("[\"" + gameVersion + "\"]")
                + loaderFilter(projectType) + "&include_changelog=false";
        JsonArray versions = getJsonArray(url);
        if (versions.isEmpty()) {
            throw new RuntimeException("Нет файла под версию " + gameVersion + " у этого проекта");
        }

        JsonObject chosen = null;
        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            if ("release".equals(str(v, "version_type", ""))) {
                chosen = v;
                break;
            }
        }
        if (chosen == null) chosen = versions.get(0).getAsJsonObject();

        JsonArray files = chosen.getAsJsonArray("files");
        if (files == null || files.isEmpty()) {
            throw new RuntimeException("У найденной версии нет файлов для скачивания");
        }
        JsonObject file = null;
        for (JsonElement el : files) {
            JsonObject f = el.getAsJsonObject();
            if (f.has("primary") && f.get("primary").getAsBoolean()) {
                file = f;
                break;
            }
        }
        if (file == null) file = files.get(0).getAsJsonObject();

        String sha1 = null;
        if (file.has("hashes") && file.getAsJsonObject("hashes").has("sha1")) {
            sha1 = file.getAsJsonObject("hashes").get("sha1").getAsString();
        }
        return new DownloadFile(str(file, "url", ""), str(file, "filename", projectId + ".jar"), sha1);
    }

    /** У ресурспаков и шейдеров нет привязки к загрузчику модов — фильтр по loaders нужен только для модов. */
    private String loaderFilter(String projectType) {
        return "mod".equals(projectType) ? "&loaders=" + enc("[\"fabric\"]") : "";
    }

    private String str(JsonObject obj, String field, String fallback) {
        return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsString() : fallback;
    }

    private String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private JsonObject getJson(String url) throws Exception {
        return JsonParser.parseString(fetch(url)).getAsJsonObject();
    }

    private JsonArray getJsonArray(String url) throws Exception {
        return JsonParser.parseString(fetch(url)).getAsJsonArray();
    }

    private String fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Modrinth ответил HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }
}
