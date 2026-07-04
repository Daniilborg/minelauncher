package launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import launcher.auth.MicrosoftAuth;

import java.awt.Desktop;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

public final class LoginScreen {

    private final VBox view = new VBox(16);
    private final MicrosoftAuth auth = new MicrosoftAuth();
    private final Consumer<MicrosoftAuth.AuthResult> onSuccess;

    public LoginScreen(Consumer<MicrosoftAuth.AuthResult> onSuccess) {
        this.onSuccess = onSuccess;
        view.setAlignment(Pos.CENTER);
        view.setPadding(new Insets(40));
        showStart();
    }

    public Node getView() {
        return view;
    }

    private void showStart() {
        Label title = new Label("Добро пожаловать");
        title.getStyleClass().add("title");

        Button loginBtn = new Button("Войти через Microsoft");
        loginBtn.getStyleClass().add("primary-button");
        loginBtn.setOnAction(e -> startLogin());

        Button skipBtn = new Button("Пропустить (офлайн, для разработки)");
        skipBtn.setOnAction(e -> skipLogin());

        Label hint = new Label("Без входа скачивание версии, Fabric и моды всё равно работают —\n"
                + "но сам запуск игры (начиная примерно с 1.19) требует настоящей сессии Mojang,\n"
                + "так что офлайн-профиль годится только для разработки лаунчера, не для игры");
        hint.getStyleClass().add("hint-text");
        hint.setWrapText(true);
        hint.setMaxWidth(380);
        hint.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        view.getChildren().setAll(title, loginBtn, skipBtn, hint);
    }

    /** Офлайн-профиль для разработки: без Microsoft/Xbox, чтобы можно было тестировать всё остальное. */
    private void skipLogin() {
        String username = "DevPlayer";
        // так же, как ванильный клиент считает UUID для офлайн-режима (LAN-игра без входа)
        UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        onSuccess.accept(new MicrosoftAuth.AuthResult("0", offlineUuid.toString(), username, "legacy"));
    }

    private void startLogin() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(32, 32);
        Label status = new Label("Запрашиваем код входа...");
        view.getChildren().setAll(spinner, status);

        Thread worker = new Thread(() -> {
            try {
                MicrosoftAuth.AuthResult result = auth.login(info -> Platform.runLater(() -> showCode(info)));
                onSuccess.accept(result);
            } catch (Exception ex) {
                Platform.runLater(() -> showError(ex.getMessage()));
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private void showCode(MicrosoftAuth.DeviceCodeInfo info) {
        Label instruction = new Label("Открой ссылку и введи код на странице Microsoft:");

        Hyperlink link = new Hyperlink(info.verificationUri());
        link.setOnAction(e -> openInBrowser(info.verificationUri()));

        Label code = new Label(info.userCode());
        code.getStyleClass().add("device-code");

        Button copyBtn = new Button("Скопировать код");
        copyBtn.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(info.userCode());
            Clipboard.getSystemClipboard().setContent(content);
        });

        HBox codeRow = new HBox(10, code, copyBtn);
        codeRow.setAlignment(Pos.CENTER);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(24, 24);
        Label waiting = new Label("Ожидаем подтверждения входа в браузере...");

        view.getChildren().setAll(instruction, link, codeRow, spinner, waiting);

        // пробуем сразу открыть браузер, чтобы не заставлять пользователя кликать лишний раз
        openInBrowser(info.verificationUri());
    }

    private void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            // не получилось открыть автоматически — пользователь перейдёт по ссылке сам
        }
    }

    private void showError(String message) {
        Label error = new Label("Ошибка входа: " + message);
        error.getStyleClass().add("error-text");
        error.setWrapText(true);
        error.setMaxWidth(480);
        Button retry = new Button("Повторить");
        retry.setOnAction(e -> showStart());
        view.getChildren().setAll(error, retry);
    }
}
