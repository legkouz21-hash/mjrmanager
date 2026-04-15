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
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.scene.control.ButtonBar;
import javafx.geometry.Insets;
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
    @FXML private Button btnCompile;
    @FXML private Button btnRunJar;
    @FXML private Button btnGlobalSearch;
    @FXML private Button btnManifest;
    @FXML private TextField searchField;
    @FXML private Menu menuRecent;
    @FXML private SplitPane mainSplit;
    @FXML private BorderPane rootPane;
    @FXML private HBox statusBar;
    @FXML private RadioMenuItem menuCompilerEclipse;
    @FXML private RadioMenuItem menuCompilerJavac;
    @FXML private RadioMenuItem menuDecompilerCfr;
    @FXML private RadioMenuItem menuDecompilerProcyon;

    private final JarService              jarService        = new JarService();
    private final DecompilerService       decompilerService = new DecompilerService();
    private final ProcyonDecompilerService procyonService   = new ProcyonDecompilerService();
    private final CompilerService         compilerService   = new CompilerService();
    private final SettingsService         settingsService   = new SettingsService();
    private final DebugLogger             logger            = DebugLogger.getInstance();
    private final SearchService           searchService     = new SearchService(decompilerService);
    private final ManifestService         manifestService   = new ManifestService();
    private final ExportService           exportService     = new ExportService(decompilerService);
    private final DependencyService       dependencyService = new DependencyService();

    private boolean useProcyon = false;

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
        setupDragAndDrop();
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

    private void setupDragAndDrop() {
        rootPane.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                List<File> files = event.getDragboard().getFiles();
                if (files.size() == 1) {
                    File file = files.get(0);
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear")) {
                        event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                    }
                }
            }
            event.consume();
        });

        rootPane.setOnDragDropped(event -> {
            boolean success = false;
            if (event.getDragboard().hasFiles()) {
                List<File> files = event.getDragboard().getFiles();
                if (files.size() == 1) {
                    File file = files.get(0);
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear")) {
                        loadJar(file);
                        success = true;
                        logger.info("JAR файл загружен через drag-and-drop: " + file.getName());
                    }
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });
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
                    String result;
                    if (useProcyon) {
                        result = procyonService.decompile(node.getFullPath(), node.getBytecode(), jarService.getJarEntries());
                    } else {
                        result = decompilerService.decompile(node.getFullPath(), node.getBytecode(), jarService.getJarEntries());
                    }
                    long elapsed = System.currentTimeMillis() - start;
                    logger.success("Декомпиляция успешна: " + node.getFullPath() + " (" + elapsed + " мс)");
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
            String sourceText = openedTab.getDecompiledSource();
            codeArea.clear();
            codeArea.replaceText(0, 0, sourceText);
            if (JavaSyntaxHighlighter.canHighlight(sourceText.length())) {
                JavaSyntaxHighlighter.applyHighlighting(codeArea, sourceText);
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

        HBox searchBar = buildSearchBar(codeArea);
        HBox replaceBar = buildReplaceBar(codeArea, searchBar);
        TextField searchField2 = (TextField) searchBar.getChildren().get(1);

        codeArea.setOnKeyPressed(event -> {
            if (event.isControlDown()) {
                switch (event.getCode()) {
                    case F -> {
                        showSearchBar(searchBar, replaceBar, searchField2, false);
                        event.consume();
                    }
                    case H -> {
                        showSearchBar(searchBar, replaceBar, searchField2, true);
                        event.consume();
                    }
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
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                searchBar.setVisible(false);
                searchBar.setManaged(false);
                replaceBar.setVisible(false);
                replaceBar.setManaged(false);
                codeArea.requestFocus();
            }
        });

        codeArea.textProperty().addListener((obs, old, newText) -> {
            if (!tab.getText().endsWith("*")) {
                tab.setText(openedTab.getEntry().getName() + " *");
            }
            openedTab.setDecompiledSource(newText);
        });

        VBox container = new VBox(searchBar, replaceBar, codeArea);
        VBox.setVgrow(codeArea, Priority.ALWAYS);

        Canvas minimap = buildMinimap(codeArea);
        HBox editorWithMinimap = new HBox(container, minimap);
        HBox.setHgrow(container, Priority.ALWAYS);

        tab.setContent(editorWithMinimap);
        return tab;
    }

    private void showSearchBar(HBox searchBar, HBox replaceBar, TextField searchField, boolean withReplace) {
        searchBar.setVisible(true);
        searchBar.setManaged(true);
        if (withReplace) {
            replaceBar.setVisible(true);
            replaceBar.setManaged(true);
        } else {
            replaceBar.setVisible(false);
            replaceBar.setManaged(false);
        }
        Platform.runLater(searchField::requestFocus);
    }

    private HBox buildSearchBar(CodeArea codeArea) {
        HBox bar = new HBox(6);
        bar.setStyle("-fx-background-color: #21252b; -fx-padding: 4 8 4 8; -fx-border-color: #181a1f; -fx-border-width: 0 0 1 0;");
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        bar.setVisible(false);
        bar.setManaged(false);

        Label label = new Label("Поиск:");
        label.setStyle("-fx-text-fill: #636d83; -fx-font-size: 12px;");

        TextField field = new TextField();
        field.setPromptText("Найти...");
        field.setPrefWidth(220);
        field.setStyle("-fx-background-color: #1d2026; -fx-text-fill: #abb2bf; -fx-border-color: #3a3f4b; -fx-border-radius: 4; -fx-background-radius: 4; -fx-padding: 3 6 3 6; -fx-font-size: 12px;");

        Label matchLabel = new Label("");
        matchLabel.setStyle("-fx-text-fill: #636d83; -fx-font-size: 11px;");

        Button btnPrev = makeBarButton("◀", "Предыдущее (Shift+Enter)");
        Button btnNext = makeBarButton("▶", "Следующее (Enter)");
        Button btnClose = makeBarButton("✕", "Закрыть (Esc)");
        btnClose.setStyle(btnClose.getStyle() + "-fx-text-fill: #e06c75;");

        CheckBox caseBox = new CheckBox("Aa");
        caseBox.setStyle("-fx-text-fill: #abb2bf; -fx-font-size: 11px;");

        List<Integer> matches = new ArrayList<>();
        int[] currentMatch = {-1};

        Runnable doSearch = () -> {
            matches.clear();
            currentMatch[0] = -1;
            String query = field.getText();
            String text = codeArea.getText();
            if (query.isEmpty()) {
                matchLabel.setText("");
                field.setStyle(field.getStyle().replace("-fx-border-color: #e06c75;", "-fx-border-color: #3a3f4b;"));
                return;
            }
            String searchText = caseBox.isSelected() ? text : text.toLowerCase();
            String searchQuery = caseBox.isSelected() ? query : query.toLowerCase();
            int idx = 0;
            while ((idx = searchText.indexOf(searchQuery, idx)) != -1) {
                matches.add(idx);
                idx += searchQuery.length();
            }
            if (matches.isEmpty()) {
                matchLabel.setText("Не найдено");
                field.setStyle(field.getStyle().replace("-fx-border-color: #3a3f4b;", "-fx-border-color: #e06c75;"));
            } else {
                field.setStyle(field.getStyle().replace("-fx-border-color: #e06c75;", "-fx-border-color: #3a3f4b;"));
                currentMatch[0] = 0;
                codeArea.selectRange(matches.get(0), matches.get(0) + query.length());
                codeArea.requestFollowCaret();
                matchLabel.setText("1 / " + matches.size());
            }
        };

        Runnable goNext = () -> {
            if (matches.isEmpty()) return;
            currentMatch[0] = (currentMatch[0] + 1) % matches.size();
            int pos = matches.get(currentMatch[0]);
            codeArea.selectRange(pos, pos + field.getText().length());
            codeArea.requestFollowCaret();
            matchLabel.setText((currentMatch[0] + 1) + " / " + matches.size());
        };

        Runnable goPrev = () -> {
            if (matches.isEmpty()) return;
            currentMatch[0] = (currentMatch[0] - 1 + matches.size()) % matches.size();
            int pos = matches.get(currentMatch[0]);
            codeArea.selectRange(pos, pos + field.getText().length());
            codeArea.requestFollowCaret();
            matchLabel.setText((currentMatch[0] + 1) + " / " + matches.size());
        };

        field.textProperty().addListener((obs, o, n) -> doSearch.run());
        caseBox.selectedProperty().addListener((obs, o, n) -> doSearch.run());
        btnNext.setOnAction(e -> goNext.run());
        btnPrev.setOnAction(e -> goPrev.run());

        field.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                if (e.isShiftDown()) goPrev.run(); else goNext.run();
                e.consume();
            }
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                bar.setVisible(false);
                bar.setManaged(false);
                codeArea.requestFocus();
            }
        });

        btnClose.setOnAction(e -> {
            bar.setVisible(false);
            bar.setManaged(false);
            codeArea.requestFocus();
        });

        bar.getChildren().addAll(label, field, btnPrev, btnNext, matchLabel, caseBox, btnClose);
        return bar;
    }

    private HBox buildReplaceBar(CodeArea codeArea, HBox searchBar) {
        HBox bar = new HBox(6);
        bar.setStyle("-fx-background-color: #21252b; -fx-padding: 4 8 4 8; -fx-border-color: #181a1f; -fx-border-width: 0 0 1 0;");
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        bar.setVisible(false);
        bar.setManaged(false);

        Label label = new Label("Замена:");
        label.setStyle("-fx-text-fill: #636d83; -fx-font-size: 12px;");

        TextField replaceField = new TextField();
        replaceField.setPromptText("Заменить на...");
        replaceField.setPrefWidth(220);
        replaceField.setStyle("-fx-background-color: #1d2026; -fx-text-fill: #abb2bf; -fx-border-color: #3a3f4b; -fx-border-radius: 4; -fx-background-radius: 4; -fx-padding: 3 6 3 6; -fx-font-size: 12px;");

        Button btnReplace = makeBarButton("Заменить", "Заменить текущее");
        Button btnReplaceAll = makeBarButton("Заменить всё", "Заменить все вхождения");

        btnReplace.setOnAction(e -> {
            TextField searchField = (TextField) searchBar.getChildren().get(1);
            String query = searchField.getText();
            String replacement = replaceField.getText();
            if (query.isEmpty()) return;
            String selected = codeArea.getSelectedText();
            if (selected.equalsIgnoreCase(query)) {
                int start = codeArea.getSelection().getStart();
                codeArea.replaceSelection(replacement);
                codeArea.selectRange(start, start + replacement.length());
            }
            searchField.fireEvent(new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ENTER, false, false, false, false));
        });

        btnReplaceAll.setOnAction(e -> {
            TextField searchField = (TextField) searchBar.getChildren().get(1);
            String query = searchField.getText();
            String replacement = replaceField.getText();
            if (query.isEmpty()) return;
            String text = codeArea.getText();
            int count = 0;
            int idx = text.indexOf(query);
            while (idx != -1) { count++; idx = text.indexOf(query, idx + query.length()); }
            if (count == 0) { showInfo("Замена", "Вхождений не найдено"); return; }
            String newText = text.replace(query, replacement);
            codeArea.replaceText(newText);
            showInfo("Замена", "Заменено вхождений: " + count);
        });

        replaceField.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                bar.setVisible(false);
                bar.setManaged(false);
                codeArea.requestFocus();
            }
        });

        bar.getChildren().addAll(label, replaceField, btnReplace, btnReplaceAll);
        return bar;
    }

    private Button makeBarButton(String text, String tooltip) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: #2c313a; -fx-text-fill: #abb2bf; -fx-border-color: transparent; -fx-background-radius: 4; -fx-cursor: hand; -fx-font-size: 11px; -fx-padding: 3 8 3 8;");
        btn.setTooltip(new Tooltip(tooltip));
        btn.setOnMouseEntered(e -> btn.setStyle(btn.getStyle().replace("#2c313a", "#3a3f4b")));
        btn.setOnMouseExited(e -> btn.setStyle(btn.getStyle().replace("#3a3f4b", "#2c313a")));
        return btn;
    }

    private Canvas buildMinimap(CodeArea codeArea) {
        javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(80, 0);
        canvas.setStyle("-fx-background-color: #1d2026;");

        javafx.scene.canvas.GraphicsContext gc = canvas.getGraphicsContext2D();

        Runnable redraw = () -> {
            double height = codeArea.getHeight();
            if (height <= 0) return;
            canvas.setHeight(height);
            gc.setFill(javafx.scene.paint.Color.web("#1d2026"));
            gc.fillRect(0, 0, 80, height);

            String[] lines = codeArea.getText().split("\n", -1);
            int totalLines = lines.length;
            if (totalLines == 0) return;

            double lineHeight = Math.max(1.0, height / totalLines);
            double fontSize = Math.min(lineHeight, 2.5);

            for (int i = 0; i < totalLines; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;

                double y = i * lineHeight;
                double indent = 0;
                for (int c = 0; c < lines[i].length() && lines[i].charAt(c) == ' '; c++) indent++;
                double x = 2 + (indent * 0.3);

                if (line.startsWith("//") || line.startsWith("*")) {
                    gc.setFill(javafx.scene.paint.Color.web("#4b5263"));
                } else if (line.startsWith("public") || line.startsWith("private") || line.startsWith("protected")
                        || line.startsWith("class") || line.startsWith("interface") || line.startsWith("enum")) {
                    gc.setFill(javafx.scene.paint.Color.web("#cc7832"));
                } else if (line.startsWith("import") || line.startsWith("package")) {
                    gc.setFill(javafx.scene.paint.Color.web("#61afef"));
                } else if (line.startsWith("@")) {
                    gc.setFill(javafx.scene.paint.Color.web("#bbb529"));
                } else {
                    gc.setFill(javafx.scene.paint.Color.web("#636d83"));
                }

                double lineWidth = Math.min(76 - x, line.length() * fontSize * 0.6);
                gc.fillRect(x, y, lineWidth, Math.max(lineHeight * 0.7, 0.5));
            }

            double visibleTop = codeArea.getEstimatedScrollY();
            double totalHeight = codeArea.getTotalHeightEstimate();
            if (totalHeight > 0) {
                double viewRatio = height / totalHeight;
                double scrollRatio = visibleTop / totalHeight;
                double vpH = height * viewRatio;
                double vpY = scrollRatio * height;
                gc.setFill(javafx.scene.paint.Color.web("#528bff", 0.15));
                gc.fillRect(0, vpY, 80, vpH);
                gc.setStroke(javafx.scene.paint.Color.web("#528bff", 0.4));
                gc.setLineWidth(1);
                gc.strokeRect(0, vpY, 79, vpH);
            }
        };

        codeArea.textProperty().addListener((obs, o, n) -> Platform.runLater(redraw));
        codeArea.heightProperty().addListener((obs, o, n) -> Platform.runLater(redraw));
        codeArea.estimatedScrollYProperty().addListener((obs, o, n) -> Platform.runLater(redraw));

        canvas.setOnMouseClicked(event -> {
            double ratio = event.getY() / canvas.getHeight();
            double total = codeArea.getTotalHeightEstimate();
            codeArea.scrollYBy(ratio * total - codeArea.getEstimatedScrollY());
        });

        canvas.setOnMouseDragged(event -> {
            double ratio = Math.max(0, Math.min(1, event.getY() / canvas.getHeight()));
            double total = codeArea.getTotalHeightEstimate();
            codeArea.scrollYBy(ratio * total - codeArea.getEstimatedScrollY());
        });

        Platform.runLater(redraw);
        return canvas;
    }

    private CodeArea findCodeAreaInTab(Tab tab) {
        if (tab.getContent() instanceof HBox hbox) {
            for (var node : hbox.getChildren()) {
                if (node instanceof VBox vbox) {
                    for (var child : vbox.getChildren()) {
                        if (child instanceof CodeArea ca) return ca;
                    }
                }
                if (node instanceof CodeArea ca) return ca;
            }
        }
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
        btnCompile.setDisable(!jarLoaded);
        btnRunJar.setDisable(!jarLoaded);
        btnGlobalSearch.setDisable(!jarLoaded);
        btnManifest.setDisable(!jarLoaded);
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
    private void onSelectDecompilerCfr() {
        useProcyon = false;
        menuDecompilerCfr.setSelected(true);
        menuDecompilerProcyon.setSelected(false);
        setStatus("Декомпилятор: CFR", false);
        logger.info("Выбран декомпилятор: CFR");
    }

    @FXML
    private void onSelectDecompilerProcyon() {
        useProcyon = true;
        menuDecompilerCfr.setSelected(false);
        menuDecompilerProcyon.setSelected(true);
        setStatus("Декомпилятор: Procyon", false);
        logger.info("Выбран декомпилятор: Procyon");
    }

    @FXML
    private void onRunJar() {
        if (jarService.getCurrentJarFile() == null) {
            showError("Ошибка", "Сначала откройте JAR файл");
            return;
        }

        String mainClass = manifestService.getMainClass(jarService.getJarEntries());
        if (mainClass == null || mainClass.isBlank()) {
            showError("Нет Main-Class", "В манифесте не указан Main-Class.\nОтредактируйте манифест и добавьте:\nMain-Class: com.example.Main");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Запуск JAR");
        confirm.setHeaderText("Запустить JAR?");
        confirm.setContentText("Main-Class: " + mainClass + "\nФайл: " + jarService.getCurrentJarFile().getName());

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        File jarFile = jarService.getCurrentJarFile();
        String taskName = "Запуск: " + jarFile.getName();
        addTask(taskName, "Запуск...");
        setStatus("Запуск JAR: " + jarFile.getName(), true);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                ProcessBuilder pb = new ProcessBuilder(
                    "java", "-jar", jarFile.getAbsolutePath()
                );
                pb.redirectErrorStream(true);
                pb.directory(jarFile.getParentFile());
                Process process = pb.start();

                StringBuilder output = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        logger.info("[JAR] " + line);
                    }
                }

                int exitCode = process.waitFor();
                logger.info("JAR завершён с кодом: " + exitCode);
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            setStatus("JAR завершён: " + jarFile.getName(), false);
            removeTask(taskName);
        });

        task.setOnFailed(e -> {
            setStatus("Ошибка запуска JAR", false);
            logger.error("Ошибка запуска JAR", task.getException());
            showError("Ошибка запуска", task.getException().getMessage());
            removeTask(taskName);
        });

        new Thread(task, "jar-runner").start();
    }

    @FXML
    private void onShowDependencies() {
        if (jarService.getCurrentJarFile() == null) {
            showError("Ошибка", "Сначала откройте JAR файл");
            return;
        }

        TreeItem<JarEntryNode> selected = fileTree.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getValue() == null || !selected.getValue().isClass()) {
            showError("Ошибка", "Выберите класс в дереве");
            return;
        }

        String classPath = selected.getValue().getFullPath();
        String taskName = "Анализ зависимостей: " + selected.getValue().getName();
        addTask(taskName, "Анализ...");
        setStatus("Анализ зависимостей...", true);

        Task<DependencyService.DependencyResult> task = new Task<>() {
            @Override
            protected DependencyService.DependencyResult call() {
                return dependencyService.analyze(classPath, jarService.getJarEntries());
            }
        };

        task.setOnSucceeded(e -> {
            DependencyService.DependencyResult result = task.getValue();
            setStatus("Зависимости: " + selected.getValue().getName(), false);
            removeTask(taskName);
            showDependenciesDialog(result);
        });

        task.setOnFailed(e -> {
            setStatus("Ошибка анализа", false);
            logger.error("Ошибка анализа зависимостей", task.getException());
            showError("Ошибка", task.getException().getMessage());
            removeTask(taskName);
        });

        new Thread(task, "dep-analyzer").start();
    }

    private void showDependenciesDialog(DependencyService.DependencyResult result) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Зависимости класса");
        dialog.setHeaderText(result.targetClass.replace(".class", "").replace('/', '.'));

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab usedByTab = new Tab("Используется в (" + result.usedBy.size() + ")");
        ListView<String> usedByList = new ListView<>();
        usedByList.getItems().addAll(result.usedBy);
        usedByList.setPrefSize(600, 350);
        usedByList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                String cls = usedByList.getSelectionModel().getSelectedItem();
                if (cls != null) {
                    dialog.close();
                    openClassByName(cls);
                }
            }
        });
        usedByTab.setContent(usedByList);

        Tab usesTab = new Tab("Использует (" + result.uses.size() + ")");
        ListView<String> usesList = new ListView<>();
        usesList.getItems().addAll(result.uses);
        usesList.setPrefSize(600, 350);
        usesList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                String cls = usesList.getSelectionModel().getSelectedItem();
                if (cls != null) {
                    dialog.close();
                    openClassByName(cls);
                }
            }
        });
        usesTab.setContent(usesList);

        tabs.getTabs().addAll(usedByTab, usesTab);
        dialog.getDialogPane().setContent(tabs);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private void openClassByName(String dotName) {
        String entryPath = dotName.replace('.', '/') + ".class";
        TreeItem<JarEntryNode> found = findTreeItem(fileTree.getRoot(), entryPath);
        if (found != null && found.getValue() != null) {
            fileTree.getSelectionModel().select(found);
            openClassInEditor(found.getValue());
        } else {
            showError("Класс не найден", dotName + "\nКласс может быть из внешней библиотеки.");
        }
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
                "  • История последних файлов\n" +
                "  • Глобальный поиск по содержимому\n" +
                "  • Редактирование манифеста\n" +
                "  • Добавление/удаление файлов\n" +
                "  • Экспорт в исходники"
        );
        alert.showAndWait();
    }


    @FXML
    private void onGlobalSearch() {
        if (jarService.getCurrentJarFile() == null) {
            showError("Ошибка", "Сначала откройте JAR файл");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Глобальный поиск");
        dialog.setHeaderText("Поиск по содержимому всех классов");

        ButtonType searchButtonType = new ButtonType("Найти", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(searchButtonType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new javafx.geometry.Insets(20, 150, 10, 10));

        TextField queryField = new TextField();
        queryField.setPromptText("Поисковый запрос");
        queryField.setPrefWidth(300);

        CheckBox caseSensitiveBox = new CheckBox("Учитывать регистр");
        CheckBox regexBox = new CheckBox("Регулярное выражение");

        grid.add(new Label("Запрос:"), 0, 0);
        grid.add(queryField, 1, 0);
        grid.add(caseSensitiveBox, 1, 1);
        grid.add(regexBox, 1, 2);

        dialog.getDialogPane().setContent(grid);
        Platform.runLater(queryField::requestFocus);

        Optional<ButtonType> result = dialog.showAndWait();

        if (result.isPresent() && result.get() == searchButtonType) {
            String query = queryField.getText().trim();
            if (query.isEmpty()) {
                showError("Ошибка", "Введите поисковый запрос");
                return;
            }

            performGlobalSearch(query, caseSensitiveBox.isSelected(), regexBox.isSelected());
        }
    }

    private void performGlobalSearch(String query, boolean caseSensitive, boolean useRegex) {
        setStatus("Поиск: " + query + "...", true);
        logger.info("Глобальный поиск: " + query);

        Task<List<SearchService.SearchResult>> task = new Task<>() {
            @Override
            protected List<SearchService.SearchResult> call() {
                return searchService.searchInContent(
                    query, jarService.getJarEntries(), caseSensitive, useRegex
                );
            }
        };

        task.setOnSucceeded(e -> {
            List<SearchService.SearchResult> results = task.getValue();
            setStatus("Найдено: " + results.size() + " совпадений", false);
            showSearchResults(query, results);
        });

        task.setOnFailed(e -> {
            setStatus("Ошибка поиска", false);
            logger.error("Ошибка глобального поиска", task.getException());
            showError("Ошибка поиска", task.getException().getMessage());
        });

        new Thread(task, "global-search").start();
    }

    private void showSearchResults(String query, List<SearchService.SearchResult> results) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Результаты поиска");
        dialog.setHeaderText("Найдено совпадений: " + results.size() + " для запроса: \"" + query + "\"");

        ListView<SearchService.SearchResult> listView = new ListView<>();
        listView.getItems().addAll(results);
        listView.setPrefSize(700, 400);

        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SearchService.SearchResult item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.toString());
                }
            }
        });

        listView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                SearchService.SearchResult selected = listView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    dialog.close();
                    openFileFromSearch(selected);
                }
            }
        });

        VBox content = new VBox(10);
        content.getChildren().addAll(
            new Label("Дважды кликните на результат для открытия файла"),
            listView
        );

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private void openFileFromSearch(SearchService.SearchResult result) {
        TreeItem<JarEntryNode> root = fileTree.getRoot();
        TreeItem<JarEntryNode> found = findTreeItem(root, result.filePath);
        
        if (found != null && found.getValue() != null) {
            fileTree.getSelectionModel().select(found);
            openClassInEditor(found.getValue());
        }
    }

    private TreeItem<JarEntryNode> findTreeItem(TreeItem<JarEntryNode> item, String path) {
        if (item.getValue() != null && path.equals(item.getValue().getFullPath())) {
            return item;
        }
        for (TreeItem<JarEntryNode> child : item.getChildren()) {
            TreeItem<JarEntryNode> result = findTreeItem(child, path);
            if (result != null) return result;
        }
        return null;
    }


    @FXML
    private void onViewManifest() {
        if (jarService.getCurrentJarFile() == null) {
            showError("Ошибка", "Сначала откройте JAR файл");
            return;
        }

        String content = manifestService.getManifestContent(jarService.getJarEntries());
        
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Манифест JAR");
        alert.setHeaderText("META-INF/MANIFEST.MF");

        TextArea textArea = new TextArea(content);
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setFont(javafx.scene.text.Font.font("Monospace", 12));
        textArea.setPrefSize(600, 400);

        alert.getDialogPane().setContent(textArea);
        alert.showAndWait();
    }

    @FXML
    private void onEditManifest() {
        if (jarService.getCurrentJarFile() == null) {
            showError("Ошибка", "Сначала откройте JAR файл");
            return;
        }

        String content = manifestService.getManifestContent(jarService.getJarEntries());

        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Редактирование манифеста");
        dialog.setHeaderText("META-INF/MANIFEST.MF");

        ButtonType saveButtonType = new ButtonType("Сохранить", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        TextArea textArea = new TextArea(content);
        textArea.setWrapText(false);
        textArea.setFont(javafx.scene.text.Font.font("Monospace", 12));
        textArea.setPrefSize(600, 400);

        VBox vbox = new VBox(10);
        vbox.getChildren().addAll(
            new Label("Редактируйте манифест. Формат: Ключ: Значение"),
            textArea
        );

        dialog.getDialogPane().setContent(vbox);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                return textArea.getText();
            }
            return null;
        });

        Optional<String> result = dialog.showAndWait();

        result.ifPresent(newContent -> {
            try {
                manifestService.updateManifest(jarService.getJarEntries(), newContent);
                showInfo("Успех", "Манифест обновлён. Не забудьте сохранить JAR.");
                logger.info("Манифест обновлён");
            } catch (Exception e) {
                showError("Ошибка", "Не удалось обновить манифест: " + e.getMessage());
                logger.error("Ошибка обновления манифеста", e);
            }
        });
    }


    @FXML
    private void onAddFiles() {
        if (jarService.getCurrentJarFile() == null) {
            showError("Ошибка", "Сначала откройте JAR файл");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Добавить файлы в JAR");
        Stage stage = (Stage) fileTree.getScene().getWindow();
        List<File> files = chooser.showOpenMultipleDialog(stage);

        if (files == null || files.isEmpty()) return;

        TextInputDialog pathDialog = new TextInputDialog("");
        pathDialog.setTitle("Путь в JAR");
        pathDialog.setHeaderText("Укажите путь внутри JAR (например: com/example/)");
        pathDialog.setContentText("Путь:");

        Optional<String> pathResult = pathDialog.showAndWait();

        pathResult.ifPresent(basePath -> {
            try {
                String normalizedPath = basePath.trim();
                if (!normalizedPath.isEmpty() && !normalizedPath.endsWith("/")) {
                    normalizedPath += "/";
                }

                for (File file : files) {
                    String entryPath = normalizedPath + file.getName();
                    byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
                    jarService.getJarEntries().put(entryPath, fileBytes);
                    logger.info("Добавлен файл: " + entryPath);
                }

                showInfo("Успех", "Добавлено файлов: " + files.size() + "\nНе забудьте сохранить JAR.");

                loadJar(jarService.getCurrentJarFile());

            } catch (Exception e) {
                showError("Ошибка", "Не удалось добавить файлы: " + e.getMessage());
                logger.error("Ошибка добавления файлов", e);
            }
        });
    }

    @FXML
    private void onDeleteFile() {
        if (jarService.getCurrentJarFile() == null) {
            showError("Ошибка", "Сначала откройте JAR файл");
            return;
        }

        TreeItem<JarEntryNode> selected = fileTree.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getValue() == null) {
            showError("Ошибка", "Выберите файл для удаления");
            return;
        }

        JarEntryNode node = selected.getValue();
        if (node.isPackage() || node.getType() == JarEntryNode.Type.ROOT) {
            showError("Ошибка", "Нельзя удалить пакет или корневой элемент");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Подтверждение");
        confirm.setHeaderText("Удалить файл?");
        confirm.setContentText("Файл: " + node.getFullPath() + "\n\nЭто действие нельзя отменить!");

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            jarService.getJarEntries().remove(node.getFullPath());
            logger.info("Удалён файл: " + node.getFullPath());

            editorTabPane.getTabs().removeIf(tab -> 
                node.getFullPath().equals(tab.getUserData())
            );

            showInfo("Успех", "Файл удалён. Не забудьте сохранить JAR.");

            loadJar(jarService.getCurrentJarFile());
        }
    }


    @FXML
    private void onExportToSources() {
        if (jarService.getCurrentJarFile() == null) {
            showError("Ошибка", "Сначала откройте JAR файл");
            return;
        }

        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle("Выберите папку для экспорта");
        Stage stage = (Stage) fileTree.getScene().getWindow();
        File outputDir = chooser.showDialog(stage);

        if (outputDir == null) return;

        Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
        progressAlert.setTitle("Экспорт в исходники");
        progressAlert.setHeaderText("Экспорт классов...");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        Label progressLabel = new Label("Подготовка...");

        VBox vbox = new VBox(10, progressLabel, progressBar);
        progressAlert.getDialogPane().setContent(vbox);
        progressAlert.getDialogPane().getButtonTypes().clear();

        progressAlert.show();

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                exportService.exportToSources(
                    jarService.getJarEntries(),
                    outputDir,
                    (current, total, file) -> {
                        updateProgress(current, total);
                        Platform.runLater(() -> {
                            progressLabel.setText("Экспорт: " + current + " / " + total + "\n" + file);
                        });
                    }
                );
                return null;
            }
        };

        progressBar.progressProperty().bind(task.progressProperty());

        task.setOnSucceeded(e -> {
            progressAlert.close();
            showInfo("Успех", "Исходники экспортированы в:\n" + outputDir.getAbsolutePath());
            logger.info("Экспорт завершён: " + outputDir.getAbsolutePath());
        });

        task.setOnFailed(e -> {
            progressAlert.close();
            showError("Ошибка", "Не удалось экспортировать: " + task.getException().getMessage());
            logger.error("Ошибка экспорта", task.getException());
        });

        new Thread(task, "export-sources").start();
    }
}