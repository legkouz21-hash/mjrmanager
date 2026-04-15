package dev.m1le.mjrmanager.service;

import dev.m1le.mjrmanager.model.JarEntryNode;
import javafx.scene.control.TreeItem;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

public class JarService {

    private File currentJarFile;
    private final Map<String, byte[]> jarEntries = new LinkedHashMap<>();

    private static class InnerClassEntry {
        String className;
        String entryName;
        String packagePath;
        TreeItem<JarEntryNode> classItem;

        InnerClassEntry(String className, String entryName, String packagePath, TreeItem<JarEntryNode> classItem) {
            this.className = className;
            this.entryName = entryName;
            this.packagePath = packagePath;
            this.classItem = classItem;
        }
    }

    public TreeItem<JarEntryNode> openJar(File jarFile) throws IOException {
        this.currentJarFile = jarFile;
        jarEntries.clear();


        JarEntryNode rootNode = new JarEntryNode(jarFile.getName(), "", JarEntryNode.Type.ROOT);
        TreeItem<JarEntryNode> root = new TreeItem<>(rootNode);
        root.setExpanded(true);

        JarEntryNode classesNode = new JarEntryNode("📦 Classes", "", JarEntryNode.Type.PACKAGE);
        TreeItem<JarEntryNode> classesRoot = new TreeItem<>(classesNode);
        classesRoot.setExpanded(true);

        JarEntryNode resourcesNode = new JarEntryNode("📁 Resources", "", JarEntryNode.Type.PACKAGE);
        TreeItem<JarEntryNode> resourcesRoot = new TreeItem<>(resourcesNode);

        root.getChildren().addAll(classesRoot, resourcesRoot);

        Map<String, TreeItem<JarEntryNode>> packageMap = new TreeMap<>();
        Map<String, TreeItem<JarEntryNode>> classMap = new TreeMap<>();
        Map<String, TreeItem<JarEntryNode>> resourcePackageMap = new TreeMap<>();
        List<InnerClassEntry> innerClasses = new ArrayList<>();

        int totalEntries = 0;
        int classCount = 0;
        int innerClassCount = 0;

        try (JarInputStream jis = new JarInputStream(new FileInputStream(jarFile))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                totalEntries++;
                String entryName = entry.getName();
                byte[] bytes = jis.readAllBytes();
                jarEntries.put(entryName, bytes);

                if (entry.isDirectory()) continue;

                if (entryName.endsWith(".class")) {
                    classCount++;
                    String className = getSimpleName(entryName).replace(".class", "");
                    String packagePath = getPackagePath(entryName);

                    JarEntryNode classNode = new JarEntryNode(className, entryName, JarEntryNode.Type.CLASS);
                    classNode.setBytecode(bytes);
                    TreeItem<JarEntryNode> classItem = new TreeItem<>(classNode);

                    if (isInnerClass(className)) {
                        innerClassCount++;
                        innerClasses.add(new InnerClassEntry(className, entryName, packagePath, classItem));
                    } else {
                        TreeItem<JarEntryNode> parent = packagePath.isEmpty()
                                ? classesRoot
                                : getOrCreatePackageNode(packagePath, packageMap, classesRoot);
                        parent.getChildren().add(classItem);
                        classMap.put(entryName, classItem);
                    }
                } else {
                    String resName = getSimpleName(entryName);
                    String packagePath = getPackagePath(entryName);

                    JarEntryNode resNode = new JarEntryNode(resName, entryName, JarEntryNode.Type.RESOURCE);
                    TreeItem<JarEntryNode> resItem = new TreeItem<>(resNode);
                    TreeItem<JarEntryNode> parent = packagePath.isEmpty()
                            ? resourcesRoot
                            : getOrCreatePackageNode(packagePath, resourcePackageMap, resourcesRoot);
                    parent.getChildren().add(resItem);
                }
            }
        }
        for (InnerClassEntry inner : innerClasses) {
            String outerClassName = getOuterClassName(inner.className);
            String outerClassPath = inner.packagePath.isEmpty()
                    ? outerClassName + ".class"
                    : inner.packagePath + "/" + outerClassName + ".class";

            TreeItem<JarEntryNode> outerClassItem = classMap.get(outerClassPath);
            if (outerClassItem != null) {
                outerClassItem.getChildren().add(inner.classItem);
            } else {
                TreeItem<JarEntryNode> parent = inner.packagePath.isEmpty()
                        ? classesRoot
                        : getOrCreatePackageNode(inner.packagePath, packageMap, classesRoot);
                parent.getChildren().add(inner.classItem);
            }
        }

        sortTree(root);
        return root;
    }

    public void updateClassBytes(String entryPath, byte[] newBytes) {
        jarEntries.put(entryPath, newBytes);
    }

    public void saveJar(File outputFile) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputFile))) {
            for (Map.Entry<String, byte[]> entry : jarEntries.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jos.putNextEntry(jarEntry);
                jos.write(entry.getValue());
                jos.closeEntry();
            }
        }
    }

    public void saveJarInPlace() throws IOException {
        if (currentJarFile == null) throw new IllegalStateException("Нет открытого JAR файла");
        File backup = new File(currentJarFile.getParent(),
                currentJarFile.getName().replace(".jar", "_backup.jar"));
        Files.copy(currentJarFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        saveJar(currentJarFile);
    }

    public File getCurrentJarFile() { return currentJarFile; }
    public Map<String, byte[]> getJarEntries() { return jarEntries; }

    private TreeItem<JarEntryNode> getOrCreatePackageNode(
            String packagePath,
            Map<String, TreeItem<JarEntryNode>> packageMap,
            TreeItem<JarEntryNode> root) {

        if (packageMap.containsKey(packagePath)) {
            return packageMap.get(packagePath);
        }

        String[] parts = packagePath.split("/");
        StringBuilder current = new StringBuilder();
        TreeItem<JarEntryNode> parent = root;

        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!current.isEmpty()) current.append("/");
            current.append(part);

            String key = current.toString();
            if (!packageMap.containsKey(key)) {
                JarEntryNode pkgNode = new JarEntryNode(part, key, JarEntryNode.Type.PACKAGE);
                TreeItem<JarEntryNode> pkgItem = new TreeItem<>(pkgNode);
                pkgItem.setExpanded(false);
                parent.getChildren().add(pkgItem);
                packageMap.put(key, pkgItem);
            }
            parent = packageMap.get(key);
        }

        return parent;
    }

    private String getSimpleName(String entryName) {
        int idx = entryName.lastIndexOf('/');
        return idx >= 0 ? entryName.substring(idx + 1) : entryName;
    }

    private String getPackagePath(String entryName) {
        int idx = entryName.lastIndexOf('/');
        return idx >= 0 ? entryName.substring(0, idx) : "";
    }

    private void sortTree(TreeItem<JarEntryNode> item) {
        item.getChildren().sort((a, b) -> {
            JarEntryNode na = a.getValue();
            JarEntryNode nb = b.getValue();
            if (na.isPackage() && !nb.isPackage()) return -1;
            if (!na.isPackage() && nb.isPackage()) return 1;
            return na.getName().compareToIgnoreCase(nb.getName());
        });
        for (TreeItem<JarEntryNode> child : item.getChildren()) {
            sortTree(child);
        }
    }

    private boolean isInnerClass(String className) {
        return className.contains("$");
    }

    private String getOuterClassName(String innerClassName) {
        int dollarIndex = innerClassName.indexOf('$');
        return dollarIndex > 0 ? innerClassName.substring(0, dollarIndex) : innerClassName;
    }
}