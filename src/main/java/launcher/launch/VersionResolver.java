package launcher.launch;

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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Собирает ResolvedVersion из ванильного version JSON (Mojang) и профиля Fabric —
 * так же, как это делает официальный лаунчер через механизм "inheritsFrom".
 * <p>
 * Важный нюанс: у Fabric в профиле библиотеки идут в СТАРОМ формате
 * (просто "name" — maven-координаты, и "url" — база репозитория), а не в
 * ванильном формате с готовым "downloads". Поэтому библиотеки разбираются
 * по двум разным путям — см. collectLibraries().
 */
public final class VersionResolver {

    private final HttpClient http = HttpClient.newHttpClient();
    private final VersionManifest manifest = new VersionManifest();

    /** loaderVersion можно передать null — тогда возьмётся последняя стабильная версия Fabric loader. */
    public ResolvedVersion resolveFabric(String gameVersion, String loaderVersion) throws Exception {
        JsonObject vanilla = fetchVanillaVersionJson(gameVersion);

        if (loaderVersion == null) {
            loaderVersion = latestStableFabricLoader(gameVersion);
        }

        String profileUrl = "https://meta.fabricmc.net/v2/versions/loader/"
                + URLEncoder.encode(gameVersion, StandardCharsets.UTF_8) + "/"
                + URLEncoder.encode(loaderVersion, StandardCharsets.UTF_8) + "/profile/json";
        JsonObject fabric = getJson(profileUrl);

        ResolvedVersion result = new ResolvedVersion();
        result.id = fabric.get("id").getAsString();
        result.mainClass = fabric.get("mainClass").getAsString();

        JsonObject assetIndex = vanilla.getAsJsonObject("assetIndex");
        result.assetIndexId = assetIndex.get("id").getAsString();
        result.assetIndexUrl = assetIndex.get("url").getAsString();

        JsonObject clientDownload = vanilla.getAsJsonObject("downloads").getAsJsonObject("client");
        result.clientJarUrl = clientDownload.get("url").getAsString();
        result.clientJarSha1 = clientDownload.has("sha1") ? clientDownload.get("sha1").getAsString() : null;

        if (vanilla.has("javaVersion")) {
            result.javaMajorVersion = vanilla.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
        }

        // сначала ванильные библиотеки (LWJGL и т.д.), потом фабричные (loader, intermediary...) —
        // одна и та же библиотека может быть у обоих с разными версиями (напр. asm 9.6 vs 9.10.1),
        // поэтому дедуплицируем по group/artifact, оставляя версию новее
        List<LibraryEntry> allLibraries = new ArrayList<>();
        collectLibraries(vanilla.getAsJsonArray("libraries"), allLibraries);
        collectLibraries(fabric.getAsJsonArray("libraries"), allLibraries);
        result.libraries.addAll(deduplicateByArtifact(allLibraries));

        // аргументы запуска: ванильные содержат реальные плейсхолдеры вида ${auth_player_name}
        collectArguments(vanilla, result.gameArgs, result.jvmArgs);
        collectArguments(fabric, result.gameArgs, result.jvmArgs);

        return result;
    }

    private JsonObject fetchVanillaVersionJson(String gameVersion) throws Exception {
        for (VersionManifest.VersionEntry v : manifest.fetchVersions()) {
            if (v.id().equals(gameVersion)) {
                return manifest.fetchVersionDetail(v.url());
            }
        }
        throw new RuntimeException("Версия " + gameVersion + " не найдена в манифесте Mojang");
    }

    private String latestStableFabricLoader(String gameVersion) throws Exception {
        String url = "https://meta.fabricmc.net/v2/versions/loader/" + URLEncoder.encode(gameVersion, StandardCharsets.UTF_8);
        JsonArray loaders = JsonParser.parseString(fetchString(url)).getAsJsonArray();
        if (loaders.isEmpty()) {
            throw new RuntimeException("Fabric пока не поддерживает версию " + gameVersion);
        }
        for (JsonElement el : loaders) {
            JsonObject loader = el.getAsJsonObject().getAsJsonObject("loader");
            if (loader.get("stable").getAsBoolean()) {
                return loader.get("version").getAsString();
            }
        }
        // если стабильной нет (бывает для совсем новых снапшотов) — берём первую (самую новую)
        return loaders.get(0).getAsJsonObject().getAsJsonObject("loader").get("version").getAsString();
    }

    private void collectLibraries(JsonArray libs, List<LibraryEntry> out) {
        if (libs == null) return;
        for (JsonElement el : libs) {
            JsonObject lib = el.getAsJsonObject();
            if (lib.has("rules") && !rulesAllow(lib.getAsJsonArray("rules"))) continue;

            if (lib.has("downloads")) {
                // ванильный (Mojang) формат
                JsonObject downloads = lib.getAsJsonObject("downloads");
                if (downloads.has("artifact") && !downloads.get("artifact").isJsonNull()) {
                    out.add(entryFromArtifact(downloads.getAsJsonObject("artifact"), false));
                }
                if (lib.has("natives") && downloads.has("classifiers")) {
                    JsonObject natives = lib.getAsJsonObject("natives");
                    String osKey = currentOsKey();
                    if (natives.has(osKey)) {
                        String classifier = natives.get(osKey).getAsString()
                                .replace("${arch}", System.getProperty("sun.arch.data.model", "64"));
                        JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                        if (classifiers.has(classifier)) {
                            out.add(entryFromArtifact(classifiers.getAsJsonObject(classifier), true));
                        }
                    }
                }
            } else if (lib.has("name")) {
                // старый формат (используется в профиле Fabric): maven-координаты + база репозитория
                out.add(mavenLibraryToEntry(lib));
            }
        }
    }

    private LibraryEntry entryFromArtifact(JsonObject artifact, boolean isNative) {
        return new LibraryEntry(
                artifact.get("path").getAsString(),
                artifact.get("url").getAsString(),
                artifact.has("sha1") ? artifact.get("sha1").getAsString() : null,
                isNative
        );
    }

    /**
     * Одна и та же библиотека (group+artifact) может встретиться дважды с разными версиями —
     * например, ASM нужен и Fabric loader'у, и отдельно указан у ванильной версии. Наличие
     * на classpath двух версий одного класса ломает загрузку ("duplicate ASM classes found"),
     * поэтому оставляем только более новую версию каждой библиотеки. native и не-native варианты
     * одной библиотеки (обычный jar и natives-классификатор) — это разные вещи, не дубликаты.
     */
    private List<LibraryEntry> deduplicateByArtifact(List<LibraryEntry> libraries) {
        Map<String, LibraryEntry> byKey = new LinkedHashMap<>();
        for (LibraryEntry lib : libraries) {
            String key = (lib.isNative() ? "native:" : "jar:") + groupArtifactOf(lib.path());
            LibraryEntry existing = byKey.get(key);
            if (existing == null || compareVersions(versionOf(lib.path()), versionOf(existing.path())) > 0) {
                byKey.put(key, lib);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** group/artifactId часть maven-пути вида group/with/slashes/artifactId/version/artifactId-version.jar */
    private String groupArtifactOf(String path) {
        String[] parts = path.split("/");
        if (parts.length < 3) return path; // неожиданный формат — не рискуем группировать вслепую
        return String.join("/", Arrays.asList(parts).subList(0, parts.length - 2));
    }

    private String versionOf(String path) {
        String[] parts = path.split("/");
        return parts.length >= 2 ? parts[parts.length - 2] : "";
    }

    /** Числовое сравнение версий по сегментам (иначе "9.10.1" окажется "меньше" "9.6" как строка). */
    private int compareVersions(String v1, String v2) {
        String[] p1 = v1.split("[.\\-]");
        String[] p2 = v2.split("[.\\-]");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            long n1 = i < p1.length ? leadingDigitsAsLong(p1[i]) : 0;
            long n2 = i < p2.length ? leadingDigitsAsLong(p2[i]) : 0;
            int cmp = Long.compare(n1, n2);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private long leadingDigitsAsLong(String segment) {
        StringBuilder digits = new StringBuilder();
        for (char c : segment.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
            else break;
        }
        try {
            return digits.length() > 0 ? Long.parseLong(digits.toString()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private LibraryEntry mavenLibraryToEntry(JsonObject lib) {
        String name = lib.get("name").getAsString(); // напр. "net.fabricmc:fabric-loader:0.16.9"
        String repo = lib.has("url") ? lib.get("url").getAsString() : "https://repo1.maven.org/maven2/";
        if (!repo.endsWith("/")) repo += "/";

        String[] parts = name.split(":");
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String path = group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
        return new LibraryEntry(path, repo + path, null, false);
    }

    private void collectArguments(JsonObject versionJson, List<String> gameOut, List<String> jvmOut) {
        if (!versionJson.has("arguments")) return;
        JsonObject args = versionJson.getAsJsonObject("arguments");

        if (args.has("game")) {
            for (JsonElement el : args.getAsJsonArray("game")) {
                // строковые элементы берём как есть; объекты с условиями (демо-режим,
                // произвольное разрешение окна и т.п.) пропускаем — для обычного запуска не нужны
                if (el.isJsonPrimitive()) gameOut.add(el.getAsString());
            }
        }
        if (args.has("jvm")) {
            for (JsonElement el : args.getAsJsonArray("jvm")) {
                if (el.isJsonPrimitive()) {
                    jvmOut.add(el.getAsString());
                } else {
                    JsonObject obj = el.getAsJsonObject();
                    if (obj.has("rules") && !rulesAllow(obj.getAsJsonArray("rules"))) continue;
                    JsonElement value = obj.get("value");
                    if (value.isJsonArray()) {
                        for (JsonElement v : value.getAsJsonArray()) jvmOut.add(v.getAsString());
                    } else {
                        jvmOut.add(value.getAsString());
                    }
                }
            }
        }
    }

    /** Стандартная логика Mojang: идём по правилам по порядку, последнее подходящее — решающее. */
    private boolean rulesAllow(JsonArray rules) {
        boolean allowed = false;
        for (JsonElement el : rules) {
            JsonObject rule = el.getAsJsonObject();
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name") && !os.get("name").getAsString().equals(currentOsKey())) matches = false;
            }
            if (rule.has("features")) matches = false; // демо/своё разрешение окна и т.п. не поддерживаем
            if (matches) {
                allowed = rule.get("action").getAsString().equals("allow");
            }
        }
        return allowed;
    }

    static String currentOsKey() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        return "linux";
    }

    private String fetchString(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private JsonObject getJson(String url) throws Exception {
        return JsonParser.parseString(fetchString(url)).getAsJsonObject();
    }
}
