package dev.m1le.mjrmanager.ui;

import dev.m1le.mjrmanager.service.DebugLogger;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class DebugConsolePane extends VBox {

    private final TextArea logArea;
    private final DebugLogger logger = DebugLogger.getInstance();
    private boolean autoScroll = true;

    public DebugConsolePane() {
        setSpacing(0);
        setPrefHeight(250);
        setMinHeight(150);

        Label title = new Label("Консоль отладки");
        title.getStyleClass().add("console-title");

        Button btnClear = new Button();
        btnClear.setGraphic(new FontIcon(MaterialDesignT.TRASH_CAN));
        btnClear.getStyleClass().add("console-button");
        btnClear.setTooltip(new Tooltip("Очистить консоль"));
        btnClear.setOnAction(e -> logger.clear());

        Button btnSave = new Button();
        btnSave.setGraphic(new FontIcon(MaterialDesignC.CONTENT_SAVE));
        btnSave.getStyleClass().add("console-button");
        btnSave.setTooltip(new Tooltip("Сохранить лог в файл"));
        btnSave.setOnAction(e -> saveLog());

        CheckBox cbAutoScroll = new CheckBox("Авто-прокрутка");
        cbAutoScroll.setSelected(true);
        cbAutoScroll.getStyleClass().add("console-checkbox");
        cbAutoScroll.selectedProperty().addListener((obs, old, val) -> autoScroll = val);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(8, title, spacer, cbAutoScroll, btnClear, btnSave);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6, 8, 6, 8));
        header.getStyleClass().add("console-header");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.getStyleClass().add("console-text");
        VBox.setVgrow(logArea, Priority.ALWAYS);

        logArea.textProperty().bind(logger.logProperty());

        logArea.textProperty().addListener((obs, old, newText) -> {
            if (autoScroll && newText != null && !newText.isEmpty()) {
                logArea.setScrollTop(Double.MAX_VALUE);
            }
        });

        getChildren().addAll(header, logArea);
        getStyleClass().add("console-pane");
    }

    private void saveLog() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Сохранить лог");
        chooser.setInitialFileName("mjrmanager_log.txt");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Текстовые файлы", "*.txt"));

        Stage stage = (Stage) getScene().getWindow();
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;

        try (FileWriter writer = new FileWriter(file)) {
            writer.write(logger.getLogText());
            logger.info("Лог сохранён в: " + file.getAbsolutePath());
        } catch (IOException ex) {
            logger.error("Ошибка сохранения лога", ex);
        }
    }
}