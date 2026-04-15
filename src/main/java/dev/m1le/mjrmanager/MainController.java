package dev.m1le.mjrmanager;

import dev.m1le.mjrmanager.editor.JavaSyntaxHighlighter;
import dev.m1le.mjrmanager.model.JarEntryNode;
import dev.m1le.mjrmanager.model.OpenedTab;
import dev.m1le.mjrmanager.service.*;
import dev.m1le.mjrmanager.ui.DebugConsolePane;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.io.File;
import java.net.URL;
import java.util.*;

public class MainController implements Initializable {

    @FXML private TreeView<JarEntryNode> fileTree;
    @FXML private TabPane editorTabPane;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Label jarNameLabel;
    @FXML private VBox welcomePane;
    @FXML private Button btnSaveJar;
    @FXML private Button btnSaveAs;
    @FXML private Button btnCompile;
    @FXML private TextField searchField;
    @FXML private Menu menuRecent;
    @FXML private SplitPane mainSplit;
    @FXML private BorderPane rootPane;
    @FXML private HBox statusBar;
    @FXML private RadioMenuItem menuCompilerEclipse;
    @FXML private RadioMenuItem menuCompilerJavac;

    private final JarService        jarService        = new JarService();
    private final DecompilerService decompilerService = new DecompilerService();
    private final CompilerService   compilerService   = new CompilerService();
    private final SettingsService   settingsService   = new SettingsService();
    private final DebugLogger       logger            = DebugLogger.getInstance();

    private final Map<String, OpenedTab> openedTabs = new HashMap<>();

    private DebugConsolePane consolePane;
    private boolean consoleVisible = false;

    private final List<TaskInfo> activeTasks = new ArrayList<>();

    private static class TaskInfo {
        String name;
        String status;
        boolean running;

        TaskInfo(String name, String status) {
            this.name = name;
            this.status = status;
            this.running = true;
        }
    }

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        setupFileTree();
        setupTabPane();
        setupConsole();
        updateButtonStates(false);
        refreshRecentMenu();

        logger.info("MJRManager запущен");
    }

    private void setupFileTree() {
        fileTree.setShowRoot(true);
        fileTree.setCellFactory(tv -> new JarTreeCell());
        fileTree.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null && sel.getValue() != null && sel.getValue().isClass()) {
                openClassInEditor(sel.getValue());
            }
        });
    }

    private void setupTabPane() {
        editorTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
    }

    private void setupConsole() {
        consolePane = new DebugConsolePane();
        consolePane.setManaged(false);
        consolePane.setVisible(false);

        progressBar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 1) {
                showTasksDialog();
            }
        });
        progressBar.setCursor(javafx.scene.Cursor.HAND);
    }

    @FXML
    private void onToggleConsole() {
        consoleVisible = !consoleVisible;

        if (consoleVisible) {
            if (rootPane.getBottom() == statusBar) {
                VBox bottomBox = new VBox(consolePane, statusBar);
                VBox.setVgrow(consolePane, Priority.ALWAYS);
                rootPane.setBottom(bottomBox);
            }
            consolePane.setManaged(true);
            consolePane.setVisible(true);
            logger.info("Консоль отладки открыта");
        } else {

            consolePane.setManaged(false);
            consolePane.setVisible(false);
            rootPane.setBottom(statusBar);
            logger.info("Консоль отладки закрыта");
        }
    }

    @FXML
    private void onOpenJar() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Открыть JAR файл");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JAR файлы", "*.jar", "*.war", "*.ear"));
        Stage stage = (Stage) fileTree.getScene().getWindow();
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;
        loadJar(file);
    }

    private void loadJar(File file) {
        setStatus("Загрузка: " + file.getName() + "...", true);
        logger.info("Начало загрузки JAR: " + file.getAbsolutePath());

        String taskName = "Загрузка JAR: " + file.getName();
        addTask(taskName, "Чтение файла...");

        Task<TreeItem<JarEntryNode>> task = new Task<>() {
            @Override
            protected TreeItem<JarEntryNode> call() throws Exception {
                long start = System.currentTimeMillis();
                updateTaskStatus(taskName, "Парсинг структуры...");
                TreeItem<JarEntryNode> result = jarService.openJar(file);
                long elapsed = System.currentTimeMillis() - start;
                logger.success("JAR загружен за " + elapsed + " мс");
                return result;
            }
        };

        task.setOnSucceeded(e -> {
            fileTree.setRoot(task.getValue());
            jarNameLabel.setText(file.getName());
            welcomePane.setVisible(false);
            welcomePane.setManaged(false);
            updateButtonStates(true);
            setStatus("Открыт: " + file.getName(), false);
            openedTabs.clear();
            editorTabPane.getTabs().clear();
            settingsService.addRecentJar(file.getAbsolutePath());
            refreshRecentMenu();
            logger.info("JAR успешно открыт: " + file.getName());
            removeTask(taskName);
        });

        task.setOnFailed(e -> {
            setStatus("Ошибка: " + task.getException().getMessage(), false);
            logger.error("Ошибка загрузки JAR: " + file.getName(), task.getException());
            showError("Ошибка открытия JAR", task.getException().getMessage());
            removeTask(taskName);
        });

        new Thread(task, "jar-loader").start();
    }

    private void refreshRecentMenu() {
        if (menuRecent == null) return;
        menuRecent.getItems().clear();
        List<String> recent = settingsService.getRecentJars();
        if (recent.isEmpty()) {
            MenuItem empty = new MenuItem("(пусто)");
            empty.setDisable(true);
            menuRecent.getItems().add(empty);
        } else {
            for (String path : recent) {
                MenuItem item = new MenuItem(path);
                item.setOnAction(e -> {
                    File f = new File(path);
                    if (f.exists()) {
                        loadJar(f);
                    } else {
                        showError("Файл не найден", path);
                    }
                });
                menuRecent.getItems().add(item);
            }
            menuRecent.getItems().add(new SeparatorMenuItem());
            MenuItem clear = new MenuItem("Очистить список");
            clear.setOnAction(e -> {
                settingsService.clearRecentJars();
                refreshRecentMenu();
            });
            menuRecent.getItems().add(clear);
        }
    }

    @FXML
    private void onSaveJar() {
        if (jarService.getCurrentJarFile() == null) return;

        logger.info("Начало сохранения JAR: " + jarService.getCurrentJarFile().getName());
        setStatus("Сохранение JAR...", true);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                long start = System.currentTimeMillis();
                jarService.saveJarInPlace();
                long elapsed = System.currentTimeMillis() - start;
                logger.success("JAR сохранён за " + elapsed + " мс (создан бэкап)");
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            setStatus("Сохранено: " + jarService.getCurrentJarFile().getName(), false);
            showInfo("Сохранено", "JAR сохранён. Создан бэкап с суффиксом _backup.jar");
        });

        task.setOnFailed(e -> {
            setStatus("Ошибка сохранения", false);
            logger.error("Ошибка сохранения JAR", task.getException());
            showError("Ошибка сохранения", task.getException().getMessage());
        });

        new Thread(task, "jar-saver").start();
    }

    @FXML
    private void onSaveAs() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Сохранить JAR как...");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JAR файлы", "*.jar"));
        if (jarService.getCurrentJarFile() != null) {
            chooser.setInitialDirectory(jarService.getCurrentJarFile().getParentFile());
            chooser.setInitialFileName("modified_" + jarService.getCurrentJarFile().getName());
        }
        Stage stage = (Stage) fileTree.getScene().getWindow();
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;

        logger.info("Сохранение JAR как: " + file.getAbsolutePath());
        setStatus("Сохранение JAR...", true);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                long start = System.currentTimeMillis();
                jarService.saveJar(file);
                long elapsed = System.currentTimeMillis() - start;
                logger.success("JAR сохранён как " + file.getName() + " за " + elapsed + " мс");
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            setStatus("Сохранено как: " + file.getName(), false);
        });

        task.setOnFailed(e -> {
            setStatus("Ошибка сохранения", false);
            logger.error("Ошибка сохранения JAR", task.getException());
            showError("Ошибка сохранения", task.getException().getMessage());
        });

        new Thread(task, "jar-saver").start();
    }

    @FXML
    private void onCompileCurrentTab() {
        Tab selectedTab = editorTabPane.getSelectionModel().getSelectedItem();
        if (selectedTab == null) return;
        String tabId = (String) selectedTab.getUserData();
        OpenedTab openedTab = openedTabs.get(tabId);
        if (openedTab == null) return;
        CodeArea codeArea = findCodeAreaInTab(selectedTab);
        if (codeArea == null) return;

        String sourceCode = codeArea.getText();
        String entryPath  = openedTab.getEntry().getFullPath();

        logger.info("Начало компиляции: " + entryPath);
        setStatus("Компиляция...", true);

        String taskName = "Компиляция: " + openedTab.getEntry().getName();
        addTask(taskName, "Компилятор: " + compilerService.getCompilerType());

        Task<CompilerService.CompileResult> task = new Task<>() {
            @Override
            protected CompilerService.CompileResult call() {
                long start = System.currentTimeMillis();
                updateTaskStatus(taskName, "Компиляция...");
                CompilerService.CompileResult result = compilerService.compile(
                        entryPath, sourceCode, jarService.getJarEntries());
                long elapsed = System.currentTimeMillis() - start;

                if (result.success) {
                    logger.success("Компиляция успешна: " + entryPath + " (" + elapsed + " мс, " + result.bytecode.length + " байт)");
                } else {
                    logger.error("Ошибка компиляции: " + entryPath + "\n" + result.errors);
                }
                return result;
            }
        };

        task.setOnSucceeded(e -> {
            CompilerService.CompileResult result = task.getValue();
            if (result.success) {
                jarService.updateClassBytes(entryPath, result.bytecode);
                openedTab.setModified(false);
                selectedTab.setText(openedTab.getEntry().getName());

                if (result.errors != null && !result.errors.isEmpty()) {

                    setStatus("Скомпилировано с ошибками: " + openedTab.getEntry().getName(), false);

                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("Компиляция с ошибками");
                    alert.setHeaderText("Класс скомпилирован и обновлён в JAR, но были ошибки:");

                    TextArea textArea = new TextArea(result.errors);
                    textArea.setEditable(false);
                    textArea.setWrapText(true);
                    textArea.setMaxWidth(Double.MAX_VALUE);
                    textArea.setMaxHeight(Double.MAX_VALUE);

                    VBox content = new VBox(10);
                    content.getChildren().addAll(
                            new Label("Байткод может работать некорректно! Не забудьте сохранить JAR."),
                            textArea
                    );
                    VBox.setVgrow(textArea, Priority.ALWAYS);

                    alert.getDialogPane().setContent(content);
                    alert.getDialogPane().setPrefSize(600, 400);
                    alert.showAndWait();
                } else {

                    setStatus("Скомпилировано: " + openedTab.getEntry().getName(), false);
                    showInfo("Успех", "Класс скомпилирован и обновлён в JAR.\nНе забудьте сохранить JAR.");
                }
            } else {

                setStatus("Критическая ошибка компиляции: " + openedTab.getEntry().getName(), false);

                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Критическая ошибка компиляции");
                alert.setHeaderText("Не удалось создать .class файл даже с ошибками:");

                TextArea textArea = new TextArea(result.errors);
                textArea.setEditable(false);
                textArea.setWrapText(true);
                textArea.setMaxWidth(Double.MAX_VALUE);
                textArea.setMaxHeight(Double.MAX_VALUE);
                VBox.setVgrow(textArea, Priority.ALWAYS);

                alert.getDialogPane().setContent(textArea);
                alert.getDialogPane().setPrefSize(600, 400);
                alert.showAndWait();
            }
        });

        task.setOnFailed(e -> {
            setStatus("Ошибка компиляции", false);
            logger.error("Исключение при компиляции", task.getException());
            showError("Ошибка", task.getException().getMessage());
            removeTask(taskName);
        });

        new Thread(task, "compiler").start();
    }

    @FXML
    private void onSearch() {
        String query = searchField.getText().trim().toLowerCase();
        if (query.isEmpty() || fileTree.getRoot() == null) return;
        searchInTree(fileTree.getRoot(), query);
    }

    private boolean searchInTree(TreeItem<JarEntryNode> item, String query) {
        if (item.getValue() != null && item.getValue().getName().toLowerCase().contains(query)) {
            fileTree.getSelectionModel().select(item);
            fileTree.scrollTo(fileTree.getRow(item));
            return true;
        }
        for (TreeItem<JarEntryNode> child : item.getChildren()) {
            if (searchInTree(child, query)) return true;
        }
        return false;
    }

    private void openClassInEditor(JarEntryNode node) {
        String tabId = node.getFullPath();
        for (Tab tab : editorTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                editorTabPane.getSelectionModel().select(tab);
                return;
            }
        }

        logger.info("Начало декомпиляции: " + node.getFullPath());
        setStatus("Декомпиляция: " + node.getName() + "...", true);

        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                long start = System.currentTimeMillis();
                try {
                    String result = decompilerService.decompile(
                            node.getFullPath(), node.getBytecode(), jarService.getJarEntries());
                    long elapsed = System.currentTimeMillis() - start;
                    logger.success("Декомпиляция успешна: " + node.getFullPath() + " (" + elapsed + " мс, " + result.length() + " символов)");
                    return result;
                } catch (Exception e) {
                    logger.warn("Основной декомпилятор не сработал, пробуем запасной метод");
                    String result = decompilerService.decompileFromBytes(node.getFullPath(), node.getBytecode());
                    long elapsed = System.currentTimeMillis() - start;
                    logger.success("Декомпиляция (запасной метод): " + node.getFullPath() + " (" + elapsed + " мс)");
                    return result;
                }
            }
        };

        task.setOnSucceeded(e -> {
            String source = task.getValue();
            OpenedTab openedTab = new OpenedTab(node, source);
            openedTabs.put(tabId, openedTab);
            Tab tab = createEditorTab(openedTab, tabId);
            editorTabPane.getTabs().add(tab);
            editorTabPane.getSelectionModel().select(tab);
            setStatus("Декомпилирован: " + node.getName(), false);
        });

        task.setOnFailed(e -> {
            setStatus("Ошибка декомпиляции", false);
            logger.error("Ошибка декомпиляции: " + node.getFullPath(), task.getException());
            showError("Ошибка декомпиляции", task.getException().getMessage());
        });

        new Thread(task, "decompiler").start();
    }

    private Tab createEditorTab(OpenedTab openedTab, String tabId) {
        Tab tab = new Tab(openedTab.getEntry().getName());
        tab.setUserData(tabId);

        CodeArea codeArea = new CodeArea();
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.getStyleClass().add("code-area");

        codeArea.setStyle("-fx-font-family: 'JetBrains Mono', 'Consolas', monospace; -fx-font-size: 14px;");

        String source = openedTab.getDecompiledSource();

        codeArea.replaceText(0, 0, source);

        if (JavaSyntaxHighlighter.canHighlight(source.length())) {
            JavaSyntaxHighlighter.applyHighlighting(codeArea, source);
            logger.info("Подсветка: " + openedTab.getEntry().getName() + " (" + source.length() + " символов)");
        } else {
            logger.warn("Файл слишком большой: " + source.length() + " символов");
        }

        ContextMenu tabContextMenu = new ContextMenu();

        MenuItem viewSourceItem = new MenuItem("Просмотр: Исходный код");
        viewSourceItem.setOnAction(e -> {
            codeArea.clear();
            codeArea.replaceText(0, 0, openedTab.getDecompiledSource());
            if (JavaSyntaxHighlighter.canHighlight(openedTab.getDecompiledSource().length())) {
                JavaSyntaxHighlighter.applyHighlighting(codeArea, openedTab.getDecompiledSource());
            }
        });

        MenuItem viewBytecodeItem = new MenuItem("Просмотр: Байткод");
        viewBytecodeItem.setOnAction(e -> {
            String bytecodeView = generateBytecodeView(openedTab.getEntry().getBytecode());
            codeArea.clear();
            codeArea.replaceText(0, 0, bytecodeView);

        });

        tabContextMenu.getItems().addAll(viewSourceItem, viewBytecodeItem);
        tab.setContextMenu(tabContextMenu);

        codeArea.setOnKeyPressed(event -> {
            if (event.isControlDown()) {
                switch (event.getCode()) {
                    case C -> {
                        String selected = codeArea.getSelectedText();
                        if (!selected.isEmpty()) {
                            javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                            content.putString(selected);
                            clipboard.setContent(content);
                        }
                    }
                    case V -> {
                        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                        if (clipboard.hasString()) {
                            codeArea.replaceSelection(clipboard.getString());
                        }
                    }
                    case X -> {
                        String selected = codeArea.getSelectedText();
                        if (!selected.isEmpty()) {
                            javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                            content.putString(selected);
                            clipboard.setContent(content);
                            codeArea.replaceSelection("");
                        }
                    }
                    case A -> {
                        codeArea.selectAll();
                        event.consume();
                    }
                }
            }
        });

        codeArea.textProperty().addListener((obs, old, newText) -> {
            if (!tab.getText().endsWith("*")) {
                tab.setText(openedTab.getEntry().getName() + " *");
            }
            openedTab.setDecompiledSource(newText);
        });

        VBox container = new VBox(codeArea);
        VBox.setVgrow(codeArea, Priority.ALWAYS);
        tab.setContent(container);
        return tab;
    }

    private CodeArea findCodeAreaInTab(Tab tab) {
        if (tab.getContent() instanceof VBox vbox) {
            for (var node : vbox.getChildren()) {
                if (node instanceof CodeArea ca) return ca;
            }
        }
        return null;
    }

    private String generateBytecodeView(byte[] bytecode) {
        if (bytecode == null || bytecode.length == 0) {
            return "No bytecode available";
        }

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("=== BYTECODE VIEW ===\n");
            sb.append("Class file size: ").append(bytecode.length).append(" bytes\n\n");

            sb.append("=== HEX DUMP ===\n");
            for (int i = 0; i < Math.min(bytecode.length, 1024); i += 16) {
                sb.append(String.format("%04X: ", i));
                for (int j = 0; j < 16 && i + j < bytecode.length; j++) {
                    sb.append(String.format("%02X ", bytecode[i + j] & 0xFF));
                }
                sb.append("\n");
            }

            if (bytecode.length > 1024) {
                sb.append("... (показаны первые 1024 байта из ").append(bytecode.length).append(")\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "Error generating bytecode view: " + e.getMessage();
        }
    }

    private void updateButtonStates(boolean jarLoaded) {
        btnSaveJar.setDisable(!jarLoaded);
        btnSaveAs.setDisable(!jarLoaded);
        btnCompile.setDisable(!jarLoaded);
    }

    private void setStatus(String message, boolean loading) {
        Platform.runLater(() -> {
            statusLabel.setText(message);
            progressBar.setVisible(loading);
        });
    }

    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message != null ? message : "Неизвестная ошибка");
            alert.showAndWait();
        });
    }

    private void showInfo(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    @FXML private void onExit()  { Platform.exit(); }

    private void showTasksDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Запущенные задачи");
        alert.setHeaderText("Активные фоновые задачи:");

        if (activeTasks.isEmpty()) {
            alert.setContentText("Нет активных задач");
        } else {
            ListView<String> taskList = new ListView<>();
            for (TaskInfo task : activeTasks) {
                String status = task.running ? "⏳" : "✓";
                taskList.getItems().add(status + " " + task.name + " - " + task.status);
            }
            taskList.setPrefHeight(200);
            alert.getDialogPane().setContent(taskList);
        }

        alert.showAndWait();
    }

    private void addTask(String name, String status) {
        TaskInfo task = new TaskInfo(name, status);
        activeTasks.add(task);
        updateProgressBar();
    }

    private void removeTask(String name) {
        activeTasks.removeIf(t -> t.name.equals(name));
        updateProgressBar();
    }

    private void updateTaskStatus(String name, String status) {
        for (TaskInfo task : activeTasks) {
            if (task.name.equals(name)) {
                task.status = status;
                break;
            }
        }
    }

    private void updateProgressBar() {
        Platform.runLater(() -> {
            if (activeTasks.isEmpty()) {
                progressBar.setVisible(false);
            } else {
                progressBar.setVisible(true);
            }
        });
    }

    @FXML
    private void onSelectCompilerEclipse() {
        compilerService.setCompilerType(CompilerService.CompilerType.ECLIPSE_JDT);
        menuCompilerEclipse.setSelected(true);
        menuCompilerJavac.setSelected(false);
        setStatus("Компилятор: Eclipse JDT (мягкий)", false);
        logger.info("Выбран компилятор: Eclipse JDT");
    }

    @FXML
    private void onSelectCompilerJavac() {
        compilerService.setCompilerType(CompilerService.CompilerType.JAVAC);
        menuCompilerEclipse.setSelected(false);
        menuCompilerJavac.setSelected(true);
        setStatus("Компилятор: javac (строгий)", false);
        logger.info("Выбран компилятор: javac");
    }

    @FXML
    private void onAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("О программе");
        alert.setHeaderText("MJRManager v1.0");
        alert.setContentText(
                "JAR Decompiler & Editor\n\n" +
                "Декомпилятор: CFR\n" +
                "Байткод: ASM\n" +
                "UI: JavaFX\n\n" +
                "Возможности:\n" +
                "  • Открытие JAR/WAR/EAR\n" +
                "  • Декомпиляция .class файлов\n" +
                "  • Редактирование с подсветкой синтаксиса\n" +
                "  • Перекомпиляция и сохранение в JAR\n" +
                "  • История последних файлов"
        );
        alert.showAndWait();
    }
}