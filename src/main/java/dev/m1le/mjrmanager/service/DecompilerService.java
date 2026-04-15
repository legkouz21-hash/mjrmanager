package dev.m1le.mjrmanager.service;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class DecompilerService {

    public String decompile(String className, byte[] bytecode, Map<String, byte[]> allEntries) {
        StringBuilder result = new StringBuilder();

        ClassFileSource source = new ClassFileSource() {
            @Override
            public void informAnalysisRelativePathDetail(String usePath, String classFilePath) {}

            @Override
            public Collection<String> addJar(String toPath) {
                return Collections.emptyList();
            }

            @Override
            public Pair<byte[], String> getClassFileContent(String path) throws IOException {
                String normalized = path.endsWith(".class") ? path : path + ".class";
                byte[] bytes = allEntries.get(normalized);
                if (bytes == null) bytes = allEntries.get(path);
                if (bytes == null) throw new IOException("Класс не найден: " + path);
                return Pair.make(bytes, path);
            }

            @Override
            public String getPossiblyRenamedPath(String path) {
                return path;
            }
        };

        OutputSinkFactory sinkFactory = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                return Collections.singletonList(SinkClass.STRING);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                if (sinkType == SinkType.JAVA) {
                    return (Sink<T>) (Sink<String>) s -> result.append(s);
                }
                return ignore -> {};
            }
        };

        Map<String, String> options = new HashMap<>();
        options.put("showversion", "false");
        options.put("silent", "true");
        options.put("comments", "false");

        String cfrPath = className.endsWith(".class") ? className : className + ".class";

        CfrDriver driver = new CfrDriver.Builder()
                .withClassFileSource(source)
                .withOutputSink(sinkFactory)
                .withOptions(options)
                .build();

        driver.analyse(Collections.singletonList(cfrPath));

        String output = result.toString().trim();
        if (output.isEmpty()) {
            return decompileFromBytes(className, bytecode);
        }
        return decodeUnicodeEscapes(output);
    }

    public String decompileFromBytes(String className, byte[] bytecode) {
        try {
            Path tempDir = Files.createTempDirectory("mjrmanager_");
            String classFileName = className.contains("/")
                    ? className.substring(className.lastIndexOf('/') + 1)
                    : className;
            if (!classFileName.endsWith(".class")) classFileName += ".class";

            Path classFile = tempDir.resolve(classFileName);
            Files.write(classFile, bytecode);

            StringBuilder result = new StringBuilder();

            OutputSinkFactory sinkFactory = new OutputSinkFactory() {
                @Override
                public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                    return Collections.singletonList(SinkClass.STRING);
                }

                @Override
                @SuppressWarnings("unchecked")
                public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                    if (sinkType == SinkType.JAVA) {
                        return (Sink<T>) (Sink<String>) s -> result.append(s);
                    }
                    return ignore -> {};
                }
            };

            Map<String, String> options = new HashMap<>();
            options.put("showversion", "false");
            options.put("silent", "true");

            CfrDriver driver = new CfrDriver.Builder()
                    .withOutputSink(sinkFactory)
                    .withOptions(options)
                    .build();

            driver.analyse(Collections.singletonList(classFile.toString()));

            Files.deleteIfExists(classFile);
            Files.deleteIfExists(tempDir);

            String output = result.toString().trim();
            return output.isEmpty() ? "Decompilation completed but no output was generated" : output;
        } catch (Exception e) {
            return "Decompilation error: " + e.getMessage();
        }
    }

    private String decodeUnicodeEscapes(String source) {
        StringBuilder sb = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (i + 5 < source.length()
                    && source.charAt(i) == '\\'
                    && source.charAt(i + 1) == 'u') {

                String hex = source.substring(i + 2, i + 6);
                if (hex.chars().allMatch(c -> "0123456789abcdefABCDEF".indexOf(c) >= 0)) {
                    int codePoint = Integer.parseInt(hex, 16);

                    if (codePoint >= 0x20) {
                        sb.append((char) codePoint);
                        i += 6;
                        continue;
                    }
                }
            }
            sb.append(source.charAt(i));
            i++;
        }
        return sb.toString();
    }
}