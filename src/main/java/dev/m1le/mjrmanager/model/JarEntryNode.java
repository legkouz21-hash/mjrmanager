package dev.m1le.mjrmanager.model;

public class JarEntryNode {

    public enum Type {
        ROOT, PACKAGE, CLASS, RESOURCE
    }

    private final String name;
    private final String fullPath;
    private final Type type;
    private byte[] bytecode;

    public JarEntryNode(String name, String fullPath, Type type) {
        this.name = name;
        this.fullPath = fullPath;
        this.type = type;
    }

    public String getName() { return name; }
    public String getFullPath() { return fullPath; }
    public Type getType() { return type; }

    public byte[] getBytecode() { return bytecode; }
    public void setBytecode(byte[] bytecode) { this.bytecode = bytecode; }

    public boolean isClass() { return type == Type.CLASS; }
    public boolean isPackage() { return type == Type.PACKAGE; }

    @Override
    public String toString() { return name; }
}