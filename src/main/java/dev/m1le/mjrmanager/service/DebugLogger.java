package dev.m1le.mjrmanager.service;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DebugLogger {

    private static final DebugLogger INSTANCE = new DebugLogger();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final StringBuilder logBuffer = new StringBuilder();
    private final StringProperty logProperty = new SimpleStringProperty("");

    private DebugLogger() {}

    public static DebugLogger getInstance() {
        return INSTANCE;
    }

    public void info(String message) {
        log("INFO", message);
    }

    public void warn(String message) {
        log("WARN", message);
    }

    public void error(String message) {
        log("ERROR", message);
    }

    public void error(String message, Throwable t) {
        log("ERROR", message + "\n" + formatException(t));
    }

    public void success(String message) {
        log("SUCCESS", message);
    }

    public void clear() {
        synchronized (logBuffer) {
            logBuffer.setLength(0);
            Platform.runLater(() -> logProperty.set(""));
        }
    }

    public StringProperty logProperty() {
        return logProperty;
    }

    public String getLogText() {
        synchronized (logBuffer) {
            return logBuffer.toString();
        }
    }

    private void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(TIME_FMT);
        String line = String.format("[%s] [%s] %s\n", timestamp, level, message);

        synchronized (logBuffer) {
            logBuffer.append(line);

            if (logBuffer.length() > 500_000) {
                int cutIndex = logBuffer.indexOf("\n", logBuffer.length() - 400_000);
                if (cutIndex > 0) {
                    logBuffer.delete(0, cutIndex + 1);
                }
            }
            final String fullLog = logBuffer.toString();
            Platform.runLater(() -> logProperty.set(fullLog));
        }
    }

    private String formatException(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
        for (StackTraceElement el : t.getStackTrace()) {
            sb.append("    at ").append(el.toString()).append("\n");
            if (sb.length() > 2000) {
                sb.append("    ... (truncated)\n");
                break;
            }
        }
        if (t.getCause() != null) {
            sb.append("  Caused by: ").append(formatException(t.getCause()));
        }
        return sb.toString();
    }
}