package launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import launcher.mods.ModsManager;
import launcher.mods.ModsManager.InstalledMod;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/** Список установленных модов для ТЕКУЩЕЙ выбранной версии (у каждой версии — своя папка mods/). */
public final class ModsTab {

    private final VBox view = new VBox(14);
    private final Supplier<Path> gameDirSupplier;
    private final VBox listBox = new VBox(6);
    private final Label subtitleLabel = new Label();

    public ModsTab(Supplier<Path> gameDirSupplier) {
        this.gameDirSupplier = gameDirSupplier;
        view.setPadding(new Insets(24));

        Label title = new Label("Установленные моды");
        title.getStyleClass().add("title");

        Button refreshBtn = new Button("Обновить");
        refreshBtn.setOnAction(e -> refresh());

        HBox header = new HBox(12, title, refreshBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        subtitleLabel.getStyleClass().add("hint-text");

        Label note = new Label("Здесь видно всё, что скачано в Магазине — моды, ресурспаки и шейдеры. "
                + "Включать/выключать файлом можно только моды — ресурспаки и шейдеры активируются "
                + "прямо в игре (Настройки → Ресурспаки / Видео → Шейдеры)");
        note.getStyleClass().add("hint-text");
        note.setWrapText(true);
        note.setMaxWidth(480);

        ScrollPane scroll = new ScrollPane(listBox);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(400);

        view.getChildren().addAll(header, subtitleLabel, note, scroll);
        refresh();
    }

    public VBox getView() {
        return view;
    }

    public void refresh() {
        Path gameDir = gameDirSupplier.get();
        subtitleLabel.setText("Версия: " + gameDir.getFileName());

        Thread t = new Thread(() -> {
            try {
                List<InstalledMod> mods = new ModsManager(gameDir).listMods();
                Platform.runLater(() -> renderList(mods));
            } catch (Exception ex) {
                Platform.runLater(() -> listBox.getChildren().setAll(
                        new Label("Не удалось прочитать папку модов: " + ex.getMessage())));
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void renderList(List<InstalledMod> mods) {
        listBox.getChildren().clear();
        if (mods.isEmpty()) {
            listBox.getChildren().add(new Label("Модов для этой версии пока нет — загляни в «Магазин»"));
            return;
        }
        for (InstalledMod mod : mods) {
            listBox.getChildren().add(buildRow(mod));
        }
    }

    private HBox buildRow(InstalledMod mod) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mod-row");

        if (mod.type() == ModsManager.ContentType.MOD) {
            CheckBox enabledBox = new CheckBox();
            enabledBox.setSelected(mod.enabled());
            enabledBox.setOnAction(e -> toggle(mod, enabledBox.isSelected()));
            row.getChildren().add(enabledBox);
        }

        Label typeLabel = new Label(typeTag(mod.type()));
        typeLabel.getStyleClass().add("hint-text");

        Label name = new Label(mod.displayName());
        name.getStyleClass().add(mod.enabled() ? "mod-name" : "mod-name-disabled");
        HBox.setHgrow(name, Priority.ALWAYS);

        Button deleteBtn = new Button("Удалить");
        deleteBtn.setOnAction(e -> delete(mod));

        row.getChildren().addAll(typeLabel, name, deleteBtn);
        return row;
    }

    private String typeTag(ModsManager.ContentType type) {
        return switch (type) {
            case RESOURCEPACK -> "[Ресурспак]";
            case SHADER -> "[Шейдер]";
            default -> "[Мод]";
        };
    }

    private void toggle(InstalledMod mod, boolean enable) {
        Thread t = new Thread(() -> {
            try {
                new ModsManager(gameDirSupplier.get()).setEnabled(mod, enable);
            } catch (Exception ignored) {
                // если не получилось переименовать файл — просто перечитаем список как есть
            }
            Platform.runLater(this::refresh);
        });
        t.setDaemon(true);
        t.start();
    }

    private void delete(InstalledMod mod) {
        Thread t = new Thread(() -> {
            try {
                new ModsManager(gameDirSupplier.get()).delete(mod);
            } catch (Exception ignored) {
            }
            Platform.runLater(this::refresh);
        });
        t.setDaemon(true);
        t.start();
    }
}
