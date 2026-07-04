package launcher.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Реализация входа через Microsoft (device code flow), как это делают
 * официальный лаунчер и большинство сторонних (Prism Launcher, HeliosLauncher и т.д.).
 * <p>
 * Цепочка: Microsoft OAuth -> Xbox Live -> XSTS -> Minecraft Services -> профиль игрока.
 * <p>
 * ВАЖНО: чтобы это заработало, нужен собственный client_id из Azure Portal,
 * и с 2023 года новые Azure-приложения дополнительно требуют одобрения Microsoft
 * для доступа к Xbox Live / Minecraft API (иначе будет ошибка "Invalid app registration",
 * см. https://aka.ms/AppRegInfo). Подробности — в README.md.
 * <p>
 * Каждый шаг разбирает ответ через parseJsonOrThrow/requireField, чтобы при сбое
 * в UI попадало реальное сообщение сервера, а не голый NullPointerException.
 */
public final class MicrosoftAuth {

    private static final String CLIENT_ID = "9f6fecc9-3583-4549-87a0-a735ffc23e56";
    private static final String SCOPE = "XboxLive.signin offline_access";

    private final HttpClient http = HttpClient.newHttpClient();

    public record DeviceCodeInfo(String deviceCode, String userCode, String verificationUri, int interval, int expiresIn) {}

    public record AuthResult(String minecraftAccessToken, String uuid, String username, String userType) {}

    /** Шаг 1: запрашиваем код устройства, который пользователь введёт в браузере. */
    public DeviceCodeInfo requestDeviceCode() throws Exception {
        String body = "client_id=" + CLIENT_ID + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject json = parseJsonOrThrow(resp, "запрос кода устройства");

        return new DeviceCodeInfo(
                requireField(json, "device_code"),
                requireField(json, "user_code"),
                requireField(json, "verification_uri"),
                json.has("interval") ? json.get("interval").getAsInt() : 5,
                json.has("expires_in") ? json.get("expires_in").getAsInt() : 900
        );
    }

    /** Шаг 2: опрашиваем Microsoft, пока пользователь не подтвердит вход в браузере. */
    public String pollForMicrosoftToken(DeviceCodeInfo info) throws Exception {
        long deadline = System.currentTimeMillis() + info.expiresIn() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(info.interval() * 1000L);

            String body = "client_id=" + CLIENT_ID
                    + "&grant_type=urn:ietf:params:oauth:grant-type:device_code"
                    + "&device_code=" + info.deviceCode();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();

            if (json.has("access_token")) {
                return json.get("access_token").getAsString();
            }
            String error = json.has("error") ? json.get("error").getAsString() : "unknown_error";
            if (error.equals("authorization_pending") || error.equals("slow_down")) {
                continue; // это ожидаемо во время ожидания — просто ждём дальше
            }
            String description = json.has("error_description") ? json.get("error_description").getAsString() : "";
            throw new RuntimeException("Ошибка входа Microsoft: " + error + (description.isEmpty() ? "" : " — " + description));
        }
        throw new RuntimeException("Время ожидания входа истекло, попробуй ещё раз");
    }

    /** Шаг 3: меняем Microsoft-токен на токен Xbox Live. Возвращает [token, uhs]. */
    private String[] authenticateXboxLive(String msAccessToken) throws Exception {
        String payload = """
                {
                  "Properties": {
                    "AuthMethod": "RPS",
                    "SiteName": "user.auth.xboxlive.com",
                    "RpsTicket": "d=%s"
                  },
                  "RelyingParty": "http://auth.xboxlive.com",
                  "TokenType": "JWT"
                }
                """.formatted(msAccessToken);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://user.auth.xboxlive.com/user/authenticate"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject json = parseJsonOrThrow(resp, "вход в Xbox Live");

        String token = requireField(json, "Token");
        if (!json.has("DisplayClaims") || !json.getAsJsonObject("DisplayClaims").has("xui")) {
            throw new RuntimeException("Xbox Live вернул неожиданный ответ на шаге \"вход в Xbox Live\": " + json);
        }
        String uhs = json.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject()
                .get("uhs").getAsString();
        return new String[]{token, uhs};
    }

    /** Шаг 4: получаем XSTS-токен, нужный именно для api.minecraftservices.com. */
    private String authenticateXSTS(String xblToken) throws Exception {
        String payload = """
                {
                  "Properties": {
                    "SandboxId": "RETAIL",
                    "UserTokens": ["%s"]
                  },
                  "RelyingParty": "rp://api.minecraftservices.com/",
                  "TokenType": "JWT"
                }
                """.formatted(xblToken);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://xsts.auth.xboxlive.com/xsts/authorize"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() == 401) {
            throw new RuntimeException(
                    "XSTS отказал во входе — часто это значит, что аккаунту нужна доп. проверка на xbox.com "
                            + "(например, детский аккаунт без семьи, или регион без Xbox Live). Ответ: " + resp.body());
        }
        JsonObject json = parseJsonOrThrow(resp, "получение XSTS-токена");
        return requireField(json, "Token");
    }

    /** Шаг 5: логинимся в Minecraft Services и получаем игровой access token. */
    private String loginToMinecraft(String xstsToken, String uhs) throws Exception {
        String payload = "{\"identityToken\": \"XBL3.0 x=" + uhs + ";" + xstsToken + "\"}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.minecraftservices.com/authentication/login_with_xbox"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject json = parseJsonOrThrow(resp, "вход в Minecraft Services");
        return requireField(json, "access_token");
    }

    /** Шаг 6: получаем профиль игрока (ник и UUID). 404 значит, что игра не куплена на этом аккаунте. */
    public AuthResult getProfile(String mcAccessToken) throws Exception {
        HttpRequest profileReq = HttpRequest.newBuilder()
                .uri(URI.create("https://api.minecraftservices.com/minecraft/profile"))
                .header("Authorization", "Bearer " + mcAccessToken)
                .GET().build();
        HttpResponse<String> resp = http.send(profileReq, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() == 404) {
            throw new RuntimeException("На этом аккаунте не куплена Minecraft (или профиль ещё не создан)");
        }
        JsonObject json = parseJsonOrThrow(resp, "получение профиля");
        return new AuthResult(mcAccessToken, requireField(json, "id"), requireField(json, "name"), "msa");
    }

    /**
     * Разбирает JSON-ответ и сразу проверяет два формата ошибок, которые встречаются
     * в этой цепочке: "error"/"error_description" (Microsoft identity platform, шаги 1-2)
     * и "errorMessage" (Minecraft Services — в частности та самая "Invalid app registration",
     * если Azure-приложение ещё не одобрено для Minecraft API).
     */
    private JsonObject parseJsonOrThrow(HttpResponse<String> resp, String stepName) {
        JsonObject json;
        try {
            json = JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("Не удалось разобрать ответ на шаге \"" + stepName + "\" (HTTP "
                    + resp.statusCode() + "): " + resp.body());
        }
        if (json.has("error")) {
            JsonElement errEl = json.get("error");
            String error = errEl.isJsonPrimitive() ? errEl.getAsString() : errEl.toString();
            String description = json.has("error_description") ? json.get("error_description").getAsString() : "";
            throw new RuntimeException("Ошибка на шаге \"" + stepName + "\" (HTTP " + resp.statusCode() + "): " + error
                    + (description.isEmpty() ? "" : " — " + description));
        }
        if (json.has("errorMessage")) {
            throw new RuntimeException("Ошибка на шаге \"" + stepName + "\" (HTTP " + resp.statusCode() + "): "
                    + json.get("errorMessage").getAsString());
        }
        return json;
    }

    /** Достаёт обязательное строковое поле; если его нет — кидает ошибку с полным телом ответа. */
    private String requireField(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            throw new RuntimeException("В ответе сервера нет ожидаемого поля \"" + field + "\". Полный ответ: " + json);
        }
        return json.get(field).getAsString();
    }

    /**
     * Полный цикл входа от начала до конца.
     * onCodeReady вызывается один раз, как только код готов и его нужно показать пользователю.
     */
    public AuthResult login(Consumer<DeviceCodeInfo> onCodeReady) throws Exception {
        DeviceCodeInfo info = requestDeviceCode();
        onCodeReady.accept(info);

        String msToken = pollForMicrosoftToken(info);
        String[] xbl = authenticateXboxLive(msToken);
        String xsts = authenticateXSTS(xbl[0]);
        String mcToken = loginToMinecraft(xsts, xbl[1]);
        return getProfile(mcToken);
    }
}
