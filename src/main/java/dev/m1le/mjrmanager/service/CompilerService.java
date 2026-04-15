package dev.m1le.mjrmanager.service;

import org.eclipse.jdt.core.compiler.batch.BatchCompiler;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class CompilerService {

    public enum CompilerType {
        JAVAC,
        ECLIPSE_JDT
    }

    private CompilerType currentCompiler = CompilerType.ECLIPSE_JDT;

    public void setCompilerType(CompilerType type) {
        this.currentCompiler = type;
    }

    public CompilerType getCompilerType() {
        return currentCompiler;
    }

    public static class CompileResult {
        public final boolean success;
        public final byte[] bytecode;
        public final String errors;

        public CompileResult(boolean success, byte[] bytecode, String errors) {
            this.success = success;
            this.bytecode = bytecode;
            this.errors = errors;
        }
    }

    public CompileResult compile(String className, String sourceCode, Map<String, byte[]> classpath) {
        return currentCompiler == CompilerType.JAVAC
                ? compileWithJavac(className, sourceCode, classpath)
                : compileWithEclipse(className, sourceCode, classpath);
    }

    private CompileResult compileWithEclipse(String className, String sourceCode, Map<String, byte[]> classpath) {
        String simpleClassName = extractSimpleClassName(className);

        try {
            Path tempDir = Files.createTempDirectory("mjrmanager_compile_");
            Path outputDir = tempDir.resolve("out");
            Files.createDirectories(outputDir);

            Path cpDir = tempDir.resolve("cp");
            Files.createDirectories(cpDir);
            writeClasspath(classpath, cpDir);

            Path sourceFile = tempDir.resolve(simpleClassName + ".java");
            Files.writeString(sourceFile, sourceCode, StandardCharsets.UTF_8);

            StringWriter errorWriter = new StringWriter();
            PrintWriter errorPrinter = new PrintWriter(errorWriter);

            String[] args = {
                "-source", "8",
                "-target", "8",
                "-encoding", "UTF-8",
                "-cp", cpDir.toString(),
                "-d", outputDir.toString(),
                "-proceedOnError",
                sourceFile.toString()
            };

            boolean success = BatchCompiler.compile(args, errorPrinter, errorPrinter, null);
            errorPrinter.close();

            Optional<Path> found = findCompiledClass(outputDir, simpleClassName);
            String errors = errorWriter.toString();

            if (found.isPresent()) {
                byte[] bytecode = Files.readAllBytes(found.get());
                deleteDirectory(tempDir);

                if (success) {
                    return new CompileResult(true, bytecode, null);
                } else {
                    return new CompileResult(true, bytecode,
                            "[Eclipse JDT] Скомпилировано с ошибками:\n" + errors);
                }
            } else {
                deleteDirectory(tempDir);
                return new CompileResult(false, null, errors);
            }

        } catch (Exception e) {
            return new CompileResult(false, null, "Ошибка компиляции: " + e.getMessage());
        }
    }

    private CompileResult compileWithJavac(String className, String sourceCode, Map<String, byte[]> classpath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new CompileResult(false, null,
                    "Компилятор Java не найден. Убедитесь, что используется JDK, а не JRE.");
        }

        String simpleClassName = extractSimpleClassName(className);

        try {
            Path tempDir = Files.createTempDirectory("mjrmanager_compile_");
            Path outputDir = tempDir.resolve("out");
            Files.createDirectories(outputDir);

            Path cpDir = tempDir.resolve("cp");
            Files.createDirectories(cpDir);
            writeClasspath(classpath, cpDir);

            Path sourceFile = tempDir.resolve(simpleClassName + ".java");
            Files.writeString(sourceFile, sourceCode, StandardCharsets.UTF_8);

            StringWriter errorWriter = new StringWriter();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

            StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                    diagnostics, Locale.getDefault(), StandardCharsets.UTF_8);

            fileManager.setLocation(StandardLocation.CLASS_OUTPUT,
                    Collections.singletonList(outputDir.toFile()));
            fileManager.setLocation(StandardLocation.CLASS_PATH,
                    Collections.singletonList(cpDir.toFile()));

            Iterable<? extends JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjects(sourceFile.toFile());

            List<String> options = Arrays.asList(
                    "-source", "8",
                    "-target", "8",
                    "-encoding", "UTF-8",
                    "-proc:none"
            );

            JavaCompiler.CompilationTask task = compiler.getTask(
                    errorWriter, fileManager, diagnostics, options, null, compilationUnits);

            boolean success = task.call();
            fileManager.close();

            Optional<Path> found = findCompiledClass(outputDir, simpleClassName);

            if (found.isPresent()) {
                byte[] bytecode = Files.readAllBytes(found.get());
                deleteDirectory(tempDir);

                if (success) {
                    return new CompileResult(true, bytecode, null);
                } else {
                    StringBuilder errors = new StringBuilder();
                    errors.append("[javac] Компиляция с ошибками:\n");
                    for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                        if (d.getKind() == Diagnostic.Kind.ERROR || d.getKind() == Diagnostic.Kind.WARNING) {
                            errors.append(String.format("[%s] Строка %d: %s%n",
                                    d.getKind(), d.getLineNumber(), d.getMessage(Locale.getDefault())));
                        }
                    }
                    return new CompileResult(true, bytecode, errors.toString());
                }
            } else {
                StringBuilder errors = new StringBuilder();
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR || d.getKind() == Diagnostic.Kind.WARNING) {
                        errors.append(String.format("[%s] Строка %d: %s%n",
                                d.getKind(), d.getLineNumber(), d.getMessage(Locale.getDefault())));
                    }
                }
                deleteDirectory(tempDir);
                return new CompileResult(false, null, errors.toString());
            }

        } catch (Exception e) {
            return new CompileResult(false, null, "Ошибка компиляции: " + e.getMessage());
        }
    }

    private String extractSimpleClassName(String className) {
        String simpleClassName = className.contains("/")
                ? className.substring(className.lastIndexOf('/') + 1)
                : className;
        if (simpleClassName.endsWith(".class")) {
            simpleClassName = simpleClassName.substring(0, simpleClassName.length() - 6);
        }
        return simpleClassName;
    }

    private void writeClasspath(Map<String, byte[]> classpath, Path cpDir) throws IOException {
        for (Map.Entry<String, byte[]> entry : classpath.entrySet()) {
            if (entry.getKey().endsWith(".class")) {
                Path classPath = cpDir.resolve(entry.getKey().replace('/', File.separatorChar));
                Files.createDirectories(classPath.getParent());
                Files.write(classPath, entry.getValue());
            }
        }
    }

    private Optional<Path> findCompiledClass(Path outputDir, String simpleClassName) throws IOException {
        final String targetName = simpleClassName + ".class";
        return Files.walk(outputDir)
                .filter(p -> p.getFileName().toString().equals(targetName))
                .findFirst();
    }

    private void deleteDirectory(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException ignored) {}
    }
}