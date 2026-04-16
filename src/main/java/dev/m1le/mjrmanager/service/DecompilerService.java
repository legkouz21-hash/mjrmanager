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
            options.put("comments", "false");

            CfrDriver driver = new CfrDriver.Builder()
                    .withOutputSink(sinkFactory)
                    .withOptions(options)
                    .build();

            driver.analyse(Collections.singletonList(classFile.toString()));

            Files.deleteIfExists(classFile);
            Files.deleteIfExists(tempDir);

            String output = result.toString().trim();
            if (output.isEmpty()) {
                return generateBasicClassInfo(className, bytecode);
            }
            return decodeUnicodeEscapes(output);
        } catch (Exception e) {
            return "Decompilation error: " + e.getMessage() + "\n\n" + generateBasicClassInfo(className, bytecode);
        }
    }

    private String generateBasicClassInfo(String className, byte[] bytecode) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Декомпиляция не удалась, показана базовая информация о классе\n\n");
        
        String simpleClassName = className.contains("/") 
            ? className.substring(className.lastIndexOf('/') + 1).replace(".class", "")
            : className.replace(".class", "");
        
        String packageName = "";
        if (className.contains("/")) {
            packageName = className.substring(0, className.lastIndexOf('/')).replace('/', '.');
            sb.append("package ").append(packageName).append(";\n\n");
        }
        
        sb.append("public class ").append(simpleClassName).append(" {\n");
        sb.append("    // Размер байткода: ").append(bytecode.length).append(" байт\n");
        sb.append("    // Полный путь: ").append(className).append("\n");
        sb.append("    \n");
        sb.append("    // Декомпиляция не удалась.\n");
        sb.append("    // Возможные причины:\n");
        sb.append("    // - Обфусцированный код\n");
        sb.append("    // - Нестандартный байткод\n");
        sb.append("    // - Поврежденный .class файл\n");
        sb.append("}\n");
        
        return sb.toString();
    }

    public static String decodeUnicodeEscapes(String source) {
        if (source == null || !source.contains("\\u")) return source;

        StringBuilder sb = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);

            if (c == '\\' && i + 1 < source.length()) {
                char next = source.charAt(i + 1);

                if (next == 'u' && i + 5 < source.length()) {
                    String hex = source.substring(i + 2, i + 6);
                    if (isHex(hex)) {
                        int codePoint = Integer.parseInt(hex, 16);
                        if (codePoint >= 0x20) {
                            sb.append((char) codePoint);
                            i += 6;
                            continue;
                        }
                    }
                }

                if (next == '\\' && i + 2 < source.length()
                        && source.charAt(i + 2) == 'u' && i + 6 < source.length()) {
                    String hex = source.substring(i + 3, i + 7);
                    if (isHex(hex)) {
                        int codePoint = Integer.parseInt(hex, 16);
                        if (codePoint >= 0x20) {
                            sb.append((char) codePoint);
                            i += 7;
                            continue;
                        }
                    }
                }
            }

            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static boolean isHex(String s) {
        if (s == null || s.length() != 4) return false;
        for (char c : s.toCharArray()) {
            if ("0123456789abcdefABCDEF".indexOf(c) < 0) return false;
        }
        return true;
    }
}