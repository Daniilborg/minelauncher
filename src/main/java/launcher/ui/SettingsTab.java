package launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import launcher.settings.LauncherSettings;

/** Настройки лаунчера: память JVM, свой путь к Java, разрешение окна игры. Сохраняются в settings.json. */
public final class SettingsTab {

    private final VBox view = new VBox(18);
    private final LauncherSettings settings;

    private final TextField minMemField = new TextField();
    private final TextField maxMemField = new TextField();
    private final TextField javaPathField = new TextField();
    private final TextField widthField = new TextField();
    private final TextField heightField = new TextField();
    private final Label statusLabel = new Label("");

    public SettingsTab() {
        this.settings = LauncherSettings.load();
        view.setPadding(new Insets(24));

        Label title = new Label("Настройки");
        title.getStyleClass().add("title");

        minMemField.setText(String.valueOf(settings.minMemoryMb));
        minMemField.setPrefWidth(90);
        maxMemField.setText(String.valueOf(settings.maxMemoryMb));
        maxMemField.setPrefWidth(90);

        javaPathField.setText(settings.javaPath);
        javaPathField.setPromptText("по умолчанию — Java самого лаунчера");
        javaPathField.setPrefWidth(360);

        widthField.setText(settings.windowWidth > 0 ? String.valueOf(settings.windowWidth) : "");
        widthField.setPromptText("авто");
        widthField.setPrefWidth(90);
        heightField.setText(settings.windowHeight > 0 ? String.valueOf(settings.windowHeight) : "");
        heightField.setPromptText("авто");
        heightField.setPrefWidth(90);

        Button saveBtn = new Button("Сохранить");
        saveBtn.getStyleClass().add("primary-button");
        saveBtn.setOnAction(e -> save());

        Label memHint = new Label("Сколько оперативной памяти выделять игре — минимум и максимум для JVM (-Xms/-Xmx)");
        Label javaHint = new Label("Путь к java-исполняемому файлу, если хочешь запускать другой версией Java, "
                + "а не той, на которой работает сам лаунчер. Например, версиям Minecraft 26.x (26.1, 26.2, 26.3...) "
                + "нужна Java 25 — если лаунчер у тебя на более старой Java, укажи здесь путь к java из установленной Java 25");
        Label resHint = new Label("Ширина и высота игрового окна при запуске — оставь пустым, чтобы игра сама решила");
        for (Label hint : new Label[]{memHint, javaHint, resHint}) {
            hint.getStyleClass().add("hint-text");
            hint.setWrapText(true);
            hint.setMaxWidth(480);
        }

        view.getChildren().addAll(
                title,
                sectionLabel("Память JVM, МБ"),
                row(new Label("Минимум:"), minMemField, new Label("Максимум:"), maxMemField),
                memHint,
                sectionLabel("Java"),
                row(javaPathField),
                javaHint,
                sectionLabel("Окно игры"),
                row(new Label("Ширина:"), widthField, new Label("Высота:"), heightField),
                resHint,
                saveBtn,
                statusLabel
        );
    }

    public Node getView() {
        return view;
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("username-label");
        return l;
    }

    private HBox row(javafx.scene.Node... nodes) {
        HBox box = new HBox(8, nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void save() {
        settings.minMemoryMb = parseIntOr(minMemField.getText(), settings.minMemoryMb);
        settings.maxMemoryMb = parseIntOr(maxMemField.getText(), settings.maxMemoryMb);
        settings.javaPath = javaPathField.getText() == null ? "" : javaPathField.getText().trim();
        settings.windowWidth = parseIntOr(widthField.getText(), 0);
        settings.windowHeight = parseIntOr(heightField.getText(), 0);
        settings.save();
        statusLabel.setText("Сохранено ✓ — подтянется при следующем запуске игры");
    }

    private int parseIntOr(String s, int fallback) {
        try {
            return (s == null || s.isBlank()) ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
