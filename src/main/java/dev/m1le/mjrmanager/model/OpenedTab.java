package dev.m1le.mjrmanager.model;

public class OpenedTab {

    private final JarEntryNode entry;
    private String decompiledSource;
    private boolean modified;

    public OpenedTab(JarEntryNode entry, String decompiledSource) {
        this.entry = entry;
        this.decompiledSource = decompiledSource;
        this.modified = false;
    }

    public JarEntryNode getEntry() { return entry; }

    public String getDecompiledSource() { return decompiledSource; }
    public void setDecompiledSource(String source) {
        this.decompiledSource = source;
        this.modified = true;
    }

    public boolean isModified() { return modified; }
    public void setModified(boolean modified) { this.modified = modified; }

    public String getTabTitle() {
        return modified ? entry.getName() + " *" : entry.getName();
    }
}