package dev.m1le.mjrmanager.service;

import net.fabricmc.tinyremapper.*;

import java.io.*;
import java.net.*;
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
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.contains("\"minecraft\"")) {
                String ver = line.split(":")[1].replace("\"", "").replace(",", "").trim();
                ver = ver.replaceAll("[~^>=]", "").trim();
                if (ver.matches("\\d+\\.\\d+.*")) return ver;
            }
        }
        return null;
    }

    public RemapResult remapJar(Map<String, byte[]> jarEntries, String mcVersion, ProgressCallback progress) throws IOException {
        RemapResult result = new RemapResult();

        progress.onProgress("Получение версии Yarn для MC " + mcVersion + "...");
        String yarnVersion = fetchLatestYarnVersion(mcVersion);
        if (yarnVersion == null) {
            throw new IOException("Не удалось найти Yarn маппинги для Minecraft " + mcVersion);
        }
        progress.onProgress("Yarn: " + yarnVersion);

        String mappingsUrl = "https://maven.fabricmc.net/net/fabricmc/yarn/"
            + yarnVersion + "/yarn-" + yarnVersion + "-tiny.gz";

        progress.onProgress("Скачивание маппингов...");
        Path mappingsFile = Files.createTempFile("yarn-mappings-", ".tiny.gz");
        Path inputJar    = Files.createTempFile("input-", ".jar");
        Path outputJar   = Files.createTempFile("output-", ".jar");

        try {
            downloadFile(mappingsUrl, mappingsFile, progress);
            progress.onProgress("Маппинги скачаны: " + (Files.size(mappingsFile) / 1024) + " KB");

            progress.onProgress("Создание временного JAR...");
            writeJar(jarEntries, inputJar);

            long jarSize = Files.size(inputJar);
            progress.onProgress("Временный JAR создан: " + (jarSize / 1024) + " KB");

            if (jarSize < 22) {
                throw new IOException("Временный JAR пустой или повреждён (размер: " + jarSize + " байт)");
            }

            progress.onProgress("Запуск TinyRemapper...");

            Files.deleteIfExists(outputJar);

            TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(mappingsFile, "intermediary", "named"))
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

            progress.onProgress("Чтение результата...");
            Map<String, byte[]> remappedEntries = readJar(outputJar);

            int before = (int) jarEntries.keySet().stream().filter(k -> k.endsWith(".class")).count();

            jarEntries.clear();
            jarEntries.putAll(remappedEntries);

            int after = (int) jarEntries.keySet().stream().filter(k -> k.endsWith(".class")).count();
            result.remappedClasses = after;

            progress.onProgress("Ремаппинг завершён: " + before + " → " + after + " классов");

        } finally {
            Files.deleteIfExists(mappingsFile);
            Files.deleteIfExists(inputJar);
            Files.deleteIfExists(outputJar);
        }

        return result;
    }

    private void writeJar(Map<String, byte[]> entries, Path dest) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(dest)))) {
            Set<String> added = new HashSet<>();

            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                String name = e.getKey();
                byte[] data = e.getValue();

                if (data == null || data.length == 0) continue;
                if (added.contains(name)) continue;

                ZipEntry ze = new ZipEntry(name);
                ze.setTime(System.currentTimeMillis());

                zos.putNextEntry(ze);
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

    private String fetchLatestYarnVersion(String mcVersion) {
        try {
            URL url = new URL("https://meta.fabricmc.net/v2/versions/yarn/" + mcVersion);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "MJRManager/1.0");
            if (conn.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            Pattern buildPat = Pattern.compile("\"build\"\\s*:\\s*(\\d+)");
            Matcher m = buildPat.matcher(sb.toString());
            int latestBuild = 0;
            while (m.find()) {
                int build = Integer.parseInt(m.group(1));
                if (build > latestBuild) latestBuild = build;
            }

            return latestBuild > 0 ? mcVersion + "+build." + latestBuild : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void downloadFile(String urlStr, Path dest, ProgressCallback progress) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "MJRManager/1.0");

        int total = conn.getContentLength();
        try (InputStream is = conn.getInputStream();
             OutputStream os = Files.newOutputStream(dest)) {
            byte[] buf = new byte[16384];
            int read, downloaded = 0;
            while ((read = is.read(buf)) != -1) {
                os.write(buf, 0, read);
                downloaded += read;
                if (total > 0) {
                    progress.onProgress("Скачивание: " + (downloaded / 1024) + " KB / " + (total / 1024) + " KB");
                }
            }
        }
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String message);
    }
}
