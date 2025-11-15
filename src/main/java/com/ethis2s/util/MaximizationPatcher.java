package com.ethis2s.util;


import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.lang.reflect.Field;
import java.util.List;

public final class MaximizationPatcher {

    private static Rectangle2D backupBounds = null;
    private static boolean isPatched = false;
    private static boolean isInternalStateChange = false;

    public static void apply(Stage stage, Node draggableArea) {
        stage.maximizedProperty().addListener((obs, oldVal, isMaximized) -> {
            // --- [1] 리스너 진입점 ---
            System.out.printf("\n[DEBUG] Listener Invoked | Maximized: %s -> %s | InternalChange: %s | Patched: %s\n",
                    oldVal, isMaximized, isInternalStateChange, isPatched);

            if (isInternalStateChange) {
                System.out.println("[DEBUG] -> Internal change detected. Ignoring this event and returning.");
                return;
            }

            if (isMaximized && isPatched) {
                System.out.println("[DEBUG] -> Window is already patched. Ignoring redundant maximization event.");
                return;
            }

            if (isMaximized) {
                // --- [2] 최대화 로직 진입 ---
                System.out.println("[DEBUG] -> 'isMaximized' block entered.");

                if (backupBounds == null) {
                    backupBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
                    System.out.printf("[DEBUG]    -> State backed up: x=%.1f, y=%.1f, w=%.1f, h=%.1f\n",
                            backupBounds.getMinX(), backupBounds.getMinY(), backupBounds.getWidth(), backupBounds.getHeight());
                }

                // --- [3] Platform.runLater 제거! 모든 작업을 즉시 실행 ---
                System.out.println("[DEBUG]    -> Executing patch synchronously (no Platform.runLater).");
                Screen screen = getScreenForStage(stage);
                Rectangle2D visualBounds = screen.getVisualBounds();

                stage.setX(visualBounds.getMinX());
                stage.setY(visualBounds.getMinY());
                stage.setWidth(visualBounds.getWidth());
                stage.setHeight(visualBounds.getHeight());
                
                System.out.printf("[DEBUG]    -> Stage repositioned to: x=%.1f, y=%.1f\n", stage.getX(), stage.getY());

                try {
                    Field maximizedField = Stage.class.getDeclaredField("maximized");
                    maximizedField.setAccessible(true);
                    ReadOnlyBooleanWrapper wrapper = (ReadOnlyBooleanWrapper) maximizedField.get(stage);

                    // --- [4] 리플렉션 실행 ---
                    isInternalStateChange = true;
                    wrapper.set(false);
                    
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // --- [5] 리플렉션 실행 직후 ---
                    isInternalStateChange = false;
                }
                
                isPatched = true;
                System.out.printf("[DEBUG]    -> Patch applied. isPatched is now TRUE. Synchronous block finished.\n");
                
            } else { 
                // ... (이하 동일)
                System.out.println("[DEBUG] -> 'isMaximized' is false block entered.");
            }
        });

        // 더블클릭 복원 로직 (이전과 동일)
        draggableArea.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                System.out.printf("\n[DEBUG] Double-click detected! | isPatched: %s\n", isPatched);
                if (isPatched) {
                    if (backupBounds != null) {
                        stage.setX(backupBounds.getMinX());
                        stage.setY(backupBounds.getMinY());
                        stage.setWidth(backupBounds.getWidth());
                        stage.setHeight(backupBounds.getHeight());
                    }
                    isPatched = false;
                    backupBounds = null;
                    System.out.println("[DEBUG] -> Restore complete. isPatched is now FALSE, backupBounds is NULL.");
                }
            }
        });
    }

    private static Screen getScreenForStage(Stage stage) {
        List<Screen> screens = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        return screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
    }
}