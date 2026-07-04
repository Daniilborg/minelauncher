package launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import launcher.auth.MicrosoftAuth;
import launcher.ui.LoginScreen;
import launcher.ui.MainScreen;

public final class App extends Application {

    private StackPane root;

    @Override
    public void start(Stage stage) {
        root = new StackPane();
        root.setAlignment(Pos.CENTER);

        Scene scene = new Scene(root, 960, 600);
        scene.getStylesheets().add(getClass().getResource("/launcher/style.css").toExternalForm());

        stage.setTitle("My Launcher");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(500);
        stage.show();

        showScreen(new LoginScreen(this::onLoginSuccess).getView());
    }

    private void showScreen(Node node) {
        root.getChildren().setAll(node);
    }

    private void onLoginSuccess(MicrosoftAuth.AuthResult result) {
        Platform.runLater(() -> showScreen(new MainScreen(result).getView()));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
