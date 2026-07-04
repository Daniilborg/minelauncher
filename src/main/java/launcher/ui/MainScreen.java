package launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import launcher.auth.MicrosoftAuth;
import launcher.launch.GameLauncher;
import launcher.launch.VersionManifest;
import launcher.settings.LauncherSettings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public final class MainScreen {

    private final BorderPane view = new BorderPane();
    private final MicrosoftAuth.AuthResult account;

    // корень данных лаунчера: скачанные версии/библиотеки/ассеты и папка самой игры
    private final Path dataRoot = Path.of(System.getProperty("user.home"), ".my-launcher");
    private final GameLauncher gameLauncher = new GameLauncher(dataRoot);

    private TextField nicknameField;
    private ComboBox<String> versionTypeBox;
    private ComboBox<String> versionBox;
    private List<VersionManifest.VersionEntry> allVersions; // полный список с манифеста Mojang, кэш
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button playBtn;

    // создаются лениво при первом открытии вкладки, дальше переиспользуются —
    // это важно и для "Играть", иначе выбор версии/никнейма сбрасывался бы при каждом переключении вкладок
    private VBox playTabView;
    private ModsTab modsTab;
    private StoreTab storeTab;
    private SettingsTab settingsTab;

    public MainScreen(MicrosoftAuth.AuthResult account) {
        this.account = account;
        view.getStyleClass().add("main-screen");
        view.setLeft(buildSidebar());
        view.setCenter(getPlayTab());
    }

    public Node getView() {
        return view;
    }

    private VBox buildSidebar() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(20));
        box.getStyleClass().add("sidebar");
        box.setPrefWidth(200);

        Label user = new Label(account.username());
        user.getStyleClass().add("username-label");

        ToggleGroup group = new ToggleGroup();
        ToggleButton play = new ToggleButton("Играть");
        ToggleButton store = new ToggleButton("Магазин");
        ToggleButton mods = new ToggleButton("Моды");
        ToggleButton settings = new ToggleButton("Настройки");
        for (ToggleButton b : new ToggleButton[]{play, store, mods, settings}) {
            b.setToggleGroup(group);
            b.setMaxWidth(Double.MAX_VALUE);
            b.getStyleClass().add("nav-button");
        }
        play.setSelected(true);

        play.setOnAction(e -> view.setCenter(getPlayTab()));
        store.setOnAction(e -> view.setCenter(getStoreTab().getView()));
        mods.setOnAction(e -> view.setCenter(getModsTab().getView()));
        settings.setOnAction(e -> view.setCenter(getSettingsTab().getView()));

        box.getChildren().addAll(user, play, store, mods, settings);
        return box;
    }

    private VBox getPlayTab() {
        if (playTabView == null) {
            playTabView = buildPlayTab();
        }
        return playTabView;
    }

    private ModsTab getModsTab() {
        if (modsTab == null) {
            modsTab = new ModsTab(this::currentGameDir);
        } else {
            modsTab.refresh();
        }
        return modsTab;
    }

    private StoreTab getStoreTab() {
        if (storeTab == null) {
            storeTab = new StoreTab(
                    this::currentGameDir,
                    () -> versionBox != null && versionBox.getValue() != null ? versionBox.getValue() : "",
                    () -> { if (modsTab != null) modsTab.refresh(); }
            );
        }
        return storeTab;
    }

    /** Папка игры для ТЕКУЩЕЙ выбранной версии — у каждой версии своя, чтобы моды не пересекались. */
    private Path currentGameDir() {
        String version = versionBox != null && versionBox.getValue() != null ? versionBox.getValue() : "default";
        return gameLauncher.gameDir(version);
    }

    private SettingsTab getSettingsTab() {
        if (settingsTab == null) {
            settingsTab = new SettingsTab();
        }
        return settingsTab;
    }

    private VBox buildPlayTab() {
        VBox box = new VBox(14);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));

        Label welcome = new Label("Готов к игре, " + account.username() + "!");
        welcome.getStyleClass().add("title");

        LauncherSettings settings = LauncherSettings.load();
        nicknameField = new TextField(!settings.nickname.isBlank() ? settings.nickname : account.username());
        nicknameField.setMaxWidth(220);
        HBox nicknameRow = new HBox(8, new Label("Никнейм:"), nicknameField);
        nicknameRow.setAlignment(Pos.CENTER);

        versionTypeBox = new ComboBox<>();
        versionTypeBox.getItems().addAll("Релизы", "Снапшоты");
        versionTypeBox.setValue("Релизы");
        versionTypeBox.setOnAction(e -> populateVersionBox());

        versionBox = new ComboBox<>();
        versionBox.setPrefWidth(160);
        versionBox.getItems().add("Загружаем...");
        versionBox.setValue("Загружаем...");
        versionBox.setDisable(true);

        HBox versionRow = new HBox(8, new Label("Тип:"), versionTypeBox, new Label("Версия:"), versionBox);
        versionRow.setAlignment(Pos.CENTER);

        loadAllVersions();

        playBtn = new Button("Играть (Fabric)");
        playBtn.getStyleClass().add("primary-button");
        playBtn.setOnAction(e -> startGame());

        statusLabel = new Label("");
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(280);
        progressBar.setVisible(false);

        box.getChildren().addAll(welcome, nicknameRow, versionRow, playBtn, statusLabel, progressBar);
        return box;
    }

    /** Тянем весь манифест версий один раз и кэшируем — из него потом отдельно фильтруем релизы и снапшоты. */
    private void loadAllVersions() {
        Thread t = new Thread(() -> {
            try {
                List<VersionManifest.VersionEntry> versions = new VersionManifest().fetchVersions();
                Platform.runLater(() -> {
                    allVersions = versions;
                    populateVersionBox();
                    versionBox.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    versionBox.getItems().setAll("1.21.1");
                    versionBox.setValue("1.21.1");
                    versionBox.setDisable(false);
                    statusLabel.setText("Не удалось получить список версий, оставил 1.21.1 по умолчанию");
                });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void populateVersionBox() {
        if (allVersions == null) return;
        String wantType = "Снапшоты".equals(versionTypeBox.getValue()) ? "snapshot" : "release";
        List<String> ids = allVersions.stream()
                .filter(v -> v.type().equals(wantType))
                .map(VersionManifest.VersionEntry::id)
                .collect(Collectors.toList());
        versionBox.getItems().setAll(ids);
        if (!ids.isEmpty()) versionBox.setValue(ids.get(0));
    }

    private void startGame() {
        String gameVersion = versionBox.getValue();
        String nickname = nicknameField.getText() == null ? "" : nicknameField.getText().trim();
        if (gameVersion == null || gameVersion.isBlank() || nickname.isEmpty()) return;

        // запоминаем никнейм на будущее
        LauncherSettings settings = LauncherSettings.load();
        settings.nickname = nickname;
        settings.save();

        // если это офлайн-профиль — пересчитываем UUID под новый никнейм, как это делает сама игра в офлайн-режиме;
        // для настоящего Microsoft-аккаунта UUID трогать нельзя, он привязан к реальному профилю
        MicrosoftAuth.AuthResult effectiveAccount = nickname.equals(account.username())
                ? account
                : new MicrosoftAuth.AuthResult(
                        account.minecraftAccessToken(),
                        "legacy".equals(account.userType())
                                ? UUID.nameUUIDFromBytes(("OfflinePlayer:" + nickname).getBytes(StandardCharsets.UTF_8)).toString()
                                : account.uuid(),
                        nickname,
                        account.userType());

        GameLauncher.LaunchOptions options = new GameLauncher.LaunchOptions(
                settings.minMemoryMb, settings.maxMemoryMb, settings.javaPath, settings.windowWidth, settings.windowHeight);

        playBtn.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(0);
        statusLabel.setText("Готовим запуск...");

        Thread worker = new Thread(() -> {
            try {
                gameLauncher.launch(gameVersion, effectiveAccount, options, new GameLauncher.ProgressListener() {
                    @Override
                    public void onStage(String stage) {
                        Platform.runLater(() -> statusLabel.setText(stage));
                    }

                    @Override
                    public void onProgress(int done, int total) {
                        if (total > 0) {
                            Platform.runLater(() -> progressBar.setProgress((double) done / total));
                        }
                    }
                });
                Platform.runLater(() -> {
                    statusLabel.setText("Игра запущена!");
                    progressBar.setVisible(false);
                    playBtn.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("Ошибка запуска: " + ex.getMessage());
                    progressBar.setVisible(false);
                    playBtn.setDisable(false);
                });
            }
        });
        worker.setDaemon(true);
        worker.start();
    }
}
