package dev.m1le.mjrmanager.service;

import dev.m1le.mjrmanager.fabricremapper.RemapUtil;
import dev.m1le.mjrmanager.fabricremapper.YarnDownloading;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

public class FabricRemapService {

    public static class RemapResult {
        public int remappedClasses;
        public List<String> log = new ArrayList<>();
    }

    public boolean isFabricMod(Map<String, byte[]> jarEntries) {
        return jarEntries.containsKey("fabric.mod.json")
            || jarEntries.containsKey("META-INF/fabric.mod.json");
    }

    public String getModId(Map<String, byte[]> jarEntries) {
        byte[] json = jarEntries.get("fabric.mod.json");
        if (json == null) json = jarEntries.get("META-INF/fabric.mod.json");
        if (json == null) return "unknown";
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(new String(json));
        return m.find() ? m.group(1) : "unknown";
    }

    public String getMinecraftVersion(Map<String, byte[]> jarEntries) {
        byte[] json = jarEntries.get("fabric.mod.json");
        if (json == null) json = jarEntries.get("META-INF/fabric.mod.json");
        if (json == null) return null;
        String content = new String(json);
        for (String line : content.split("\n")) {
            if (line.contains("\"minecraft\"")) {
                String ver = line.split(":")[1].replace("\"", "").replace(",", "").trim();
                ver = ver.replaceAll("[~^>=*]", "").trim();
                if (ver.matches("\\d+\\.\\d+.*")) return ver;
            }
        }
        return null;
    }

    public RemapResult remapJar(Map<String, byte[]> jarEntries, String mcVersion, ProgressCallback progress) throws IOException {
        RemapResult result = new RemapResult();

        Path inputJar  = Files.createTempFile("mjr-input-",  ".jar");
        Path outputJar = Files.createTempFile("mjr-output-", ".jar");
        Files.deleteIfExists(outputJar);

        try {
            progress.onProgress("Создание временного JAR...");
            writeJar(jarEntries, inputJar);
            progress.onProgress("JAR создан: " + (Files.size(inputJar) / 1024) + " KB");

            progress.onProgress("Скачивание Yarn маппингов для MC " + mcVersion + "...");
            Path mappingsGz = YarnDownloading.resolve(mcVersion);
            if (mappingsGz == null) {
                throw new IOException("Не удалось скачать Yarn маппинги для MC " + mcVersion);
            }
            progress.onProgress("Маппинги скачаны: " + mappingsGz.getFileName());

            progress.onProgress("Запуск TinyRemapper (шаг 1)...");
            TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(mappingsGz, "intermediary", "named"))
                .renameInvalidLocals(true)
                .rebuildSourceFilenames(true)
                .ignoreConflicts(true)
                .keepInputData(true)
                .skipLocalVariableMapping(true)
                .ignoreFieldDesc(true)
                .build();

            try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(outputJar).build()) {
                outputConsumer.addNonClassFiles(inputJar);
                remapper.readInputs(inputJar);
                remapper.readClassPath(inputJar);
                remapper.apply(outputConsumer);
            } finally {
                remapper.finish();
            }

            progress.onProgress("Шаг 1 завершён. Запуск шага 2 (ASM ремаппинг полей/методов)...");
            Path mappingsTiny2 = YarnDownloading.resolveTiny2(mcVersion);
            if (mappingsTiny2 != null) {
                Map<String, String> asmMappings = RemapUtil.getMappings(mappingsTiny2);
                progress.onProgress("Загружено ASM маппингов: " + asmMappings.size());
                RemapUtil.remapJar(outputJar, remapper, asmMappings);
                progress.onProgress("Шаг 2 завершён");
                Files.deleteIfExists(mappingsTiny2);
                if (YarnDownloading.path != null) Files.deleteIfExists(YarnDownloading.path);
            }

            Files.deleteIfExists(mappingsGz);

            progress.onProgress("Чтение результата...");
            Map<String, byte[]> remappedEntries = readJar(outputJar);

            int before = (int) jarEntries.keySet().stream().filter(k -> k.endsWith(".class")).count();
            jarEntries.clear();
            jarEntries.putAll(remappedEntries);
            result.remappedClasses = (int) jarEntries.keySet().stream().filter(k -> k.endsWith(".class")).count();

            progress.onProgress("Ремаппинг завершён: " + before + " → " + result.remappedClasses + " классов");

        } finally {
            Files.deleteIfExists(inputJar);
            Files.deleteIfExists(outputJar);
        }

        return result;
    }

    private void writeJar(Map<String, byte[]> entries, Path dest) throws IOException {
        Set<String> added = new HashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(dest)))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                String name = e.getKey();
                byte[] data = e.getValue();
                if (data == null || data.length == 0 || added.contains(name)) continue;
                zos.putNextEntry(new ZipEntry(name));
                zos.write(data);
                zos.closeEntry();
                added.add(name);
            }
            zos.finish();
        }
    }

    private Map<String, byte[]> readJar(Path src) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(src)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    result.put(entry.getName(), zis.readAllBytes());
                }
                zis.closeEntry();
            }
        }
        return result;
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String message);
    }
}
