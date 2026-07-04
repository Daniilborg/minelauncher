package launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import launcher.launch.Downloader;
import launcher.mods.ModrinthClient;
import launcher.mods.ModrinthProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Магазин модов/ресурспаков/шейдеров поверх Modrinth API. Список — browseRoot,
 * карточка с подробностями подменяет его в view и возвращается назад через back-кнопку.
 */
public final class StoreTab {

    private final VBox view = new VBox();
    private final VBox browseRoot;
    private final VBox resultsBox = new VBox(8);
    private final TextField searchField = new TextField();
    private final ComboBox<String> categoryBox = new ComboBox<>();
    private final Label statusLabel = new Label("");

    private final ModrinthClient client = new ModrinthClient();
    private final Supplier<Path> gameDirSupplier;
    private final Supplier<String> gameVersionSupplier;
    private final Runnable onModsChanged;

    public StoreTab(Supplier<Path> gameDirSupplier, Supplier<String> gameVersionSupplier, Runnable onModsChanged) {
        this.gameDirSupplier = gameDirSupplier;
        this.gameVersionSupplier = gameVersionSupplier;
        this.onModsChanged = onModsChanged;

        Label title = new Label("Магазин");
        title.getStyleClass().add("title");

        categoryBox.getItems().addAll("Моды", "Ресурспаки", "Шейдеры");
        categoryBox.setValue("Моды");
        categoryBox.setOnAction(e -> search());

        searchField.setPromptText("Поиск...");
        searchField.setOnAction(e -> search());
        HBox.setHgrow(searchField, Priority.ALWAYS);

        Button searchBtn = new Button("Найти");
        searchBtn.setOnAction(e -> search());

        HBox searchRow = new HBox(8, categoryBox, searchField, searchBtn);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(resultsBox);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(420);

        browseRoot = new VBox(12, title, searchRow, statusLabel, scroll);
        browseRoot.setPadding(new Insets(24));

        view.getChildren().add(browseRoot);
        search();
    }

    public VBox getView() {
        return view;
    }

    private String currentProjectType() {
        return switch (categoryBox.getValue()) {
            case "Ресурспаки" -> "resourcepack";
            case "Шейдеры" -> "shader";
            default -> "mod";
        };
    }

    private void search() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim();
        String projectType = currentProjectType();
        String gameVersion = gameVersionSupplier.get();

        statusLabel.setText("Ищем...");
        resultsBox.getChildren().clear();

        Thread t = new Thread(() -> {
            try {
                List<ModrinthProject> results = client.search(query, projectType, gameVersion, 30);
                Platform.runLater(() -> {
                    statusLabel.setText(results.isEmpty() ? "Ничего не нашлось" : "");
                    resultsBox.getChildren().clear();
                    for (ModrinthProject p : results) {
                        resultsBox.getChildren().add(buildCard(p));
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> statusLabel.setText("Ошибка поиска: " + ex.getMessage()));
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private HBox buildCard(ModrinthProject project) {
        ImageView icon = new ImageView();
        icon.setFitWidth(48);
        icon.setFitHeight(48);
        icon.setPreserveRatio(true);
        if (project.iconUrl() != null) {
            icon.setImage(new Image(project.iconUrl(), 48, 48, true, true, true));
        }

        Label titleLabel = new Label(project.title());
        titleLabel.getStyleClass().add("mod-name");

        Label descLabel = new Label(project.description());
        descLabel.setWrapText(true);
        descLabel.getStyleClass().add("hint-text");

        Label metaLabel = new Label(formatDownloads(project.downloads()) + " скачиваний · " + project.author());
        metaLabel.getStyleClass().add("hint-text");

        VBox textBox = new VBox(4, titleLabel, descLabel, metaLabel);
        textBox.setMaxWidth(440);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Button openBtn = new Button("Подробнее");
        openBtn.setOnAction(e -> showDetail(project));

        HBox card = new HBox(14, icon, textBox, openBtn);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("mod-row");
        return card;
    }

    private String formatDownloads(int downloads) {
        if (downloads >= 1_000_000) return String.format("%.1fM", downloads / 1_000_000.0);
        if (downloads >= 1_000) return String.format("%.1fK", downloads / 1_000.0);
        return String.valueOf(downloads);
    }

    private void showDetail(ModrinthProject project) {
        VBox detail = new VBox(14);
        detail.setPadding(new Insets(24));

        Button backBtn = new Button("← Назад к списку");
        backBtn.setOnAction(e -> view.getChildren().setAll(browseRoot));

        HBox headerRow = new HBox(16);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        ImageView icon = new ImageView();
        icon.setFitWidth(64);
        icon.setFitHeight(64);
        icon.setPreserveRatio(true);
        if (project.iconUrl() != null) {
            icon.setImage(new Image(project.iconUrl(), 64, 64, true, true, true));
        }
        Label titleLabel = new Label(project.title());
        titleLabel.getStyleClass().add("title");
        headerRow.getChildren().addAll(icon, titleLabel);

        Label byLabel = new Label("Автор: " + project.author() + " · " + formatDownloads(project.downloads()) + " скачиваний");
        byLabel.getStyleClass().add("hint-text");

        Label descLabel = new Label(project.description());
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(560);

        Button installBtn = new Button("Установить");
        installBtn.getStyleClass().add("primary-button");
        installBtn.setOnAction(e -> install(project, installBtn));

        Label shaderHint = new Label("Шейдерам нужен мод Iris (или OptiFine) — без него они не появятся "
                + "в настройках видео Minecraft");
        shaderHint.getStyleClass().add("hint-text");
        shaderHint.setWrapText(true);
        shaderHint.setMaxWidth(480);
        shaderHint.setVisible("shader".equals(project.projectType()));
        shaderHint.setManaged("shader".equals(project.projectType()));

        detail.getChildren().addAll(backBtn, headerRow, byLabel, descLabel, installBtn, shaderHint);
        view.getChildren().setAll(detail);

        // подробное описание (может быть длинным markdown-текстом) подгружаем отдельно,
        // чтобы список открывался сразу, не дожидаясь этого запроса
        Thread t = new Thread(() -> {
            try {
                String fullDesc = client.getFullDescription(project.projectId());
                if (!fullDesc.isBlank()) {
                    Platform.runLater(() -> descLabel.setText(fullDesc));
                }
            } catch (Exception ignored) {
                // если не получилось — оставляем короткое описание, уже показанное
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void install(ModrinthProject project, Button installBtn) {
        installBtn.setDisable(true);
        installBtn.setText("Устанавливаем...");

        Thread t = new Thread(() -> {
            try {
                String gameVersion = gameVersionSupplier.get();
                ModrinthClient.DownloadFile file = client.findBestFile(project.projectId(), project.projectType(), gameVersion);

                String folder = switch (project.projectType()) {
                    case "resourcepack" -> "resourcepacks";
                    case "shader" -> "shaderpacks";
                    default -> "mods";
                };
                Path targetDir = gameDirSupplier.get().resolve(folder);
                Files.createDirectories(targetDir);
                Path targetFile = targetDir.resolve(file.filename());

                new Downloader().downloadAll(
                        List.of(new Downloader.FileTask(file.url(), targetFile, file.sha1())),
                        (done, total) -> {}
                );

                Platform.runLater(() -> {
                    installBtn.setText("Установлено ✓");
                    if (onModsChanged != null) onModsChanged.run();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    installBtn.setDisable(false);
                    installBtn.setText("Установить");
                    statusLabel.setText("Не удалось установить: " + ex.getMessage());
                });
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
