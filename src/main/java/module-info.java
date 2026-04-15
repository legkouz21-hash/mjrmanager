module dev.m1le.mjrmanager {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;

    requires org.fxmisc.richtext;
    requires org.fxmisc.flowless;
    requires reactfx;

    requires org.objectweb.asm;
    requires org.objectweb.asm.util;
    requires org.objectweb.asm.commons;
    requires org.objectweb.asm.tree;

    requires cfr;

    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.materialdesign2;

    requires java.compiler;
    requires java.prefs;
    requires org.eclipse.jdt.core.compiler.batch;
    requires procyon.compilertools;

    opens dev.m1le.mjrmanager to javafx.fxml;
    opens dev.m1le.mjrmanager.model to javafx.fxml;

    exports dev.m1le.mjrmanager;
    exports dev.m1le.mjrmanager.model;
    exports dev.m1le.mjrmanager.service;
    exports dev.m1le.mjrmanager.editor;
}