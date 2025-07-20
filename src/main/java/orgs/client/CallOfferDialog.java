package orgs.client;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.scene.image.Image;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

// Note: This is a nested class or a separate file for a cleaner design
public class CallOfferDialog {

    public enum CallChoice {
        ACCEPT,
        REJECT
    }

    public static CompletableFuture<CallChoice> display(String callerUsername) {
        CompletableFuture<CallChoice> future = new CompletableFuture<>();

        // It is critical to run this on the JavaFX Application Thread.
        Platform.runLater(() -> {
            Stage dialogStage = new Stage();
            dialogStage.initModality(Modality.APPLICATION_MODAL); // Blocks the main window
            dialogStage.setTitle("Incoming Video Call");

            // UI Components (Leveraging a simple, clean design)
            Label messageLabel = new Label("Incoming video call from " + callerUsername + ".");
            messageLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

            // Optional: Add an icon for better UI/UX
            // try {
            //     Image callIcon = new Image(getClass().getResourceAsStream("/path/to/call_icon.png"));
            //     ImageView iconView = new ImageView(callIcon);
            //     iconView.setFitHeight(32);
            //     iconView.setFitWidth(32);
            //     messageLabel.setGraphic(iconView);
            // } catch (Exception e) {
            //     // Handle case where resource is not found
            // }


            Button acceptButton = new Button("Accept");
            Button rejectButton = new Button("Reject");

            // Define button actions
            acceptButton.setOnAction(e -> {
                dialogStage.close();
                future.complete(CallChoice.ACCEPT);
            });

            rejectButton.setOnAction(e -> {
                dialogStage.close();
                future.complete(CallChoice.REJECT);
            });

            HBox buttonBox = new HBox(10, acceptButton, rejectButton);
            buttonBox.setAlignment(Pos.CENTER);

            VBox layout = new VBox(15, messageLabel, buttonBox);
            layout.setAlignment(Pos.CENTER);
            layout.setPadding(new Insets(20));

            Scene scene = new Scene(layout);
            dialogStage.setScene(scene);
            dialogStage.setResizable(false);
            dialogStage.showAndWait();
        });

        return future;
    }
}