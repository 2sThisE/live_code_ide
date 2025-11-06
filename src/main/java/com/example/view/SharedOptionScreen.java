package com.example.view;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.json.JSONArray;
import com.example.controller.MainController;
import com.example.controller.ProjectController;
import com.example.model.UserProjectsInfo;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

public class SharedOptionScreen {

    public GridPane createShareOptionView(
            MainController mainController,
            ProjectController projectController,
            UserProjectsInfo userProjectsInfo,
            BiConsumer<String, Consumer<JSONArray>> dataRequester,
            QuadConsumer<String, String, String, Consumer<Boolean>> shareRequester) {

        GridPane grid = new GridPane();
        grid.setAlignment(Pos.TOP_LEFT);
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(25, 25, 25, 25));

        Label title = new Label(userProjectsInfo.getProjectName() + " 공유 옵션");
        title.setId("properties-title");
        grid.add(title, 0, 0, 6, 1);

        // --- Shared Users List ---
        grid.add(new Label("공유된 사용자 목록"), 0, 1, 2, 1);
        ListView<String> sharedListView = new ListView<>();
        ObservableList<String> items = FXCollections.observableArrayList("불러오는 중...");
        sharedListView.setItems(items);
        grid.add(sharedListView, 0, 2, 2, 4);

        Consumer<JSONArray> dataReceiver = sharedList -> {
            items.clear();
            if (sharedList != null && sharedList.length() > 0) {
                for (int i = 0; i < sharedList.length(); i++) {
                    items.add(sharedList.getJSONObject(i).getString("nickname") + "#" + sharedList.getJSONObject(i).getString("tag"));
                }
            } else {
                items.add("공유된 사용자가 없습니다.");
            }
        };
        
        // Initial data load
        dataRequester.accept(userProjectsInfo.getProjectID(), dataReceiver);

        sharedListView.setCellFactory(lv -> {
            ListCell<String> cell = new ListCell<>();
            ContextMenu contextMenu = new ContextMenu();
            MenuItem deleteItem = new MenuItem("공유 해제");
            deleteItem.setOnAction(event -> {
                String userIdentifier = cell.getItem();
                if (userIdentifier == null || userIdentifier.contains(" ") || userIdentifier.isEmpty()) {
                    return;
                }
                String[] parts = userIdentifier.split("#");
                projectController.shareDeleteRequest(userProjectsInfo.getProjectID(), parts[0], parts[1]);
            });
            contextMenu.getItems().add(deleteItem);

            cell.textProperty().bind(cell.itemProperty());
            cell.emptyProperty().addListener((obs, wasEmpty, isNowEmpty) -> {
                cell.setContextMenu(isNowEmpty || cell.getText().contains(" ") ? null : contextMenu);
            });
            return cell;
        });

        // --- Add User Section ---
        grid.add(new Label("유저 추가하기"), 3, 1, 3, 1);
        TextField nick = new TextField();
        TextField tag = new TextField();
        nick.setMaxWidth(100);
        tag.setMaxWidth(70);
        nick.setPromptText("닉네임");
        tag.setPromptText("태그");
        grid.add(nick, 3, 2);
        grid.add(new Label("#"), 4, 2);
        grid.add(tag, 5, 2);
        
        Label resultLabel = new Label();
        grid.add(resultLabel, 3, 4, 3, 1);

        Button addUserButton = new Button("추가");
        addUserButton.setOnAction(e -> {
            String nickname = nick.getText().trim();
            String tagValue = tag.getText().trim();
            if (nickname.isEmpty() || tagValue.isEmpty()) {
                resultLabel.setText("닉네임과 태그를 입력하세요.");
                resultLabel.setStyle("-fx-text-fill: #ff5555;");
                return;
            }
            resultLabel.setText("추가 중...");
            resultLabel.setStyle("-fx-text-fill: #d4d4d4;");

            Consumer<Boolean> resultCallback = isSuccess -> {
                Platform.runLater(() -> {
                    if (isSuccess) {
                        resultLabel.setText("사용자 추가 성공!");
                        resultLabel.setStyle("-fx-text-fill: #55ff55;");
                        dataRequester.accept(userProjectsInfo.getProjectID(), dataReceiver);
                        nick.clear();
                        tag.clear();
                    } else {
                        resultLabel.setText("추가 실패 (사용자 없음 등)");
                        resultLabel.setStyle("-fx-text-fill: #ff5555;");
                    }
                });
            };

            shareRequester.accept(userProjectsInfo.getProjectID(), nickname, tagValue, resultCallback);
        });

        HBox buttonBox = new HBox();
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.getChildren().add(addUserButton);
        grid.add(buttonBox, 3, 3, 3, 1);

        return grid;
    }
}