package dev.m1le.mjrmanager.service;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ExportService {

    private final DecompilerService decompilerService;

    public ExportService(DecompilerService decompilerService) {
        this.decompilerService = decompilerService;
    }

    public void exportToSources(Map<String, byte[]> jarEntries, File outputDir, 
                                ProgressCallback progressCallback) throws IOException {
        
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        List<String> classFiles = new ArrayList<>();
        for (String entryPath : jarEntries.keySet()) {
            if (entryPath.endsWith(".class")) {
                classFiles.add(entryPath);
            }
        }

        int total = classFiles.size();
        int current = 0;

        File srcDir = new File(outputDir, "src");
        srcDir.mkdirs();

        for (String entryPath : classFiles) {
            current++;
            
            if (progressCallback != null) {
                progressCallback.onProgress(current, total, entryPath);
            }

            try {
                byte[] bytecode = jarEntries.get(entryPath);
                String decompiled = decompilerService.decompile(entryPath, bytecode, jarEntries);

                String javaPath = entryPath.replace(".class", ".java");
                File javaFile = new File(srcDir, javaPath);

                javaFile.getParentFile().mkdirs();

                Files.write(javaFile.toPath(), decompiled.getBytes("UTF-8"));

            } catch (Exception e) {
                System.err.println("Ошибка экспорта " + entryPath + ": " + e.getMessage());
            }
        }

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

        File metaInfDir = new File(outputDir, "META-INF");
        metaInfDir.mkdirs();

        for (Map.Entry<String, byte[]> entry : jarEntries.entrySet()) {
            String entryPath = entry.getKey();
            
            if (entryPath.startsWith("META-INF/")) {
                File metaFile = new File(outputDir, entryPath);
                metaFile.getParentFile().mkdirs();
                Files.write(metaFile.toPath(), entry.getValue());
            }
        }

        createReadme(outputDir);
    }

    private void createReadme(File outputDir) throws IOException {
        File readme = new File(outputDir, "README.txt");
        String content = "Экспортировано из JAR с помощью MJRManager\n\n" +
                        "Структура:\n" +
                        "  src/       - исходные коды (.java)\n" +
                        "  resources/ - ресурсы\n" +
                        "  META-INF/  - метаданные JAR\n\n" +
                        "Для компиляции используйте:\n" +
                        "  javac -d bin -sourcepath src src/**/*.java\n\n" +
                        "Для создания JAR:\n" +
                        "  jar cvfm output.jar META-INF/MANIFEST.MF -C bin .\n";
        
        Files.write(readme.toPath(), content.getBytes("UTF-8"));
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int current, int total, String currentFile);
    }
}
