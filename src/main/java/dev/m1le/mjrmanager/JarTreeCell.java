package dev.m1le.mjrmanager;

import dev.m1le.mjrmanager.model.JarEntryNode;
import javafx.scene.control.TreeCell;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

public class JarTreeCell extends TreeCell<JarEntryNode> {

    @Override
    protected void updateItem(JarEntryNode item, boolean empty) {
        super.updateItem(item, empty);

        if (empty || item == null) {
            setText(null);
            setGraphic(null);
            return;
        }

        setText(item.getName());
        setGraphic(createIcon(item));
    }

    private FontIcon createIcon(JarEntryNode node) {
        FontIcon icon;
        switch (node.getType()) {
            case ROOT -> {
                icon = new FontIcon(MaterialDesignA.ARCHIVE);
                icon.getStyleClass().add("icon-jar");
            }
            case PACKAGE -> {
                icon = new FontIcon(MaterialDesignF.FOLDER);
                icon.getStyleClass().add("icon-package");
            }
            case CLASS -> {
                icon = new FontIcon(MaterialDesignC.CODE_BRACES);
                icon.getStyleClass().add("icon-class");
            }
            default -> {
                icon = new FontIcon(MaterialDesignF.FILE);
                icon.getStyleClass().add("icon-resource");
            }
        }
        icon.setIconSize(16);
        return icon;
    }
}