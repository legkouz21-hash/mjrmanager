package dev.m1le.mjrmanager.service;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ExportService {

    private final DecompilerService decompilerService;
    private final ProcyonDecompilerService procyonService;

    public static class ExportSettings {
        public boolean exportClasses = true;
        public boolean exportResources = true;
        public boolean exportMetaInf = true;
        public boolean createReadme = true;
        public boolean useProcyon = false;
        public boolean remapFabric = false;
        public boolean remapClasses = true;
        public boolean remapMethods = true;
        public boolean remapFields = true;
        public boolean skipInnerClasses = false;
        public boolean skipSyntheticClasses = false;
        public String packageFilter = "";
    }

    public ExportService(DecompilerService decompilerService) {
        this.decompilerService = decompilerService;
        this.procyonService = new ProcyonDecompilerService();
    }

    public void exportToSources(Map<String, byte[]> jarEntries, File outputDir,
                                ExportSettings settings,
                                ProgressCallback progressCallback) throws IOException {

        if (!outputDir.exists()) outputDir.mkdirs();

        List<String> classFiles = new ArrayList<>();
        for (String entryPath : jarEntries.keySet()) {
            if (!entryPath.endsWith(".class")) continue;
            if (settings.skipInnerClasses && entryPath.contains("$")) continue;
            if (!settings.packageFilter.isEmpty()) {
                String pkg = settings.packageFilter.replace('.', '/');
                if (!entryPath.startsWith(pkg)) continue;
            }
            classFiles.add(entryPath);
        }

        int total = classFiles.size();
        int current = 0;

        if (settings.exportClasses) {
            File srcDir = new File(outputDir, "src");
            srcDir.mkdirs();

            for (String entryPath : classFiles) {
                current++;
                if (progressCallback != null) {
                    progressCallback.onProgress(current, total, entryPath);
                }
                try {
                    byte[] bytecode = jarEntries.get(entryPath);
                    String decompiled;
                    if (settings.useProcyon) {
                        decompiled = procyonService.decompile(entryPath, bytecode, jarEntries);
                    } else {
                        decompiled = decompilerService.decompile(entryPath, bytecode, jarEntries);
                    }
                    String javaPath = entryPath.replace(".class", ".java");
                    File javaFile = new File(srcDir, javaPath);
                    javaFile.getParentFile().mkdirs();
                    Files.write(javaFile.toPath(), decompiled.getBytes("UTF-8"));
                } catch (Exception e) {
                    System.err.println("Ошибка экспорта " + entryPath + ": " + e.getMessage());
                }
            }
        }

        if (settings.exportResources) {
            File resourcesDir = new File(outputDir, "resources");
            resourcesDir.mkdirs();
            for (Map.Entry<String, byte[]> entry : jarEntries.entrySet()) {
                String entryPath = entry.getKey();
                if (!entryPath.endsWith(".class") && !entryPath.startsWith("META-INF/")) {
                    File resourceFile = new File(resourcesDir, entryPath);
                    resourceFile.getParentFile().mkdirs();
                    Files.write(resourceFile.toPath(), entry.getValue());
                }
            }
        }

        if (settings.exportMetaInf) {
            for (Map.Entry<String, byte[]> entry : jarEntries.entrySet()) {
                String entryPath = entry.getKey();
                if (entryPath.startsWith("META-INF/")) {
                    File metaFile = new File(outputDir, entryPath);
                    metaFile.getParentFile().mkdirs();
                    Files.write(metaFile.toPath(), entry.getValue());
                }
            }
        }

        if (settings.createReadme) {
            createReadme(outputDir, settings);
        }
    }

    private void createReadme(File outputDir, ExportSettings settings) throws IOException {
        File readme = new File(outputDir, "README.txt");
        StringBuilder sb = new StringBuilder();
        sb.append("Экспортировано из JAR с помощью MJRManager\n\n");
        sb.append("Настройки экспорта:\n");
        sb.append("  Декомпилятор: ").append(settings.useProcyon ? "Procyon" : "CFR").append("\n");
        if (settings.remapFabric) sb.append("  Fabric маппинги: применены\n");
        sb.append("\nСтруктура:\n");
        if (settings.exportClasses)   sb.append("  src/       - исходные коды (.java)\n");
        if (settings.exportResources) sb.append("  resources/ - ресурсы\n");
        if (settings.exportMetaInf)   sb.append("  META-INF/  - метаданные JAR\n");
        sb.append("\nДля компиляции:\n");
        sb.append("  javac -d bin -sourcepath src src/**/*.java\n");
        Files.write(readme.toPath(), sb.toString().getBytes("UTF-8"));
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int current, int total, String currentFile);
    }
}
