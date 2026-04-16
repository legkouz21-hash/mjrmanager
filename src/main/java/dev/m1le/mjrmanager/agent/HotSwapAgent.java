package dev.m1le.mjrmanager.agent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class HotSwapAgent {

    private static Instrumentation instrumentation;
    private static ServerSocket serverSocket;
    private static final int PORT = 57321;

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
        startServer();
    }

    public static void agentmain(String args, Instrumentation inst) {
        instrumentation = inst;
        startServer();
    }

    private static void startServer() {
        Thread t = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (!serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        new Thread(() -> handleClient(client), "hotswap-handler").start();
                    } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                System.err.println("[HotSwapAgent] Не удалось запустить сервер: " + e.getMessage());
            }
        }, "hotswap-server");
        t.setDaemon(true);
        t.start();
        System.out.println("[HotSwapAgent] Сервер запущен на порту " + PORT);
    }

    private static void handleClient(Socket client) {
        try (DataInputStream dis = new DataInputStream(client.getInputStream());
             DataOutputStream dos = new DataOutputStream(client.getOutputStream())) {

            String className = dis.readUTF();
            int length = dis.readInt();
            byte[] bytecode = new byte[length];
            dis.readFully(bytecode);

            System.out.println("[HotSwapAgent] Получен запрос hot-swap: " + className);

            if (!instrumentation.isRedefineClassesSupported()) {
                dos.writeUTF("ERROR: JVM не поддерживает redefineClasses. Запустите с -XX:+AllowEnhancedClassRedefinition или используйте JDK 8-17");
                return;
            }

            Class<?> target = findClass(className);

            if (target == null) {
                dos.writeUTF("ERROR: класс не найден в JVM: " + className + "\nВозможно класс ещё не загружен или использует нестандартный ClassLoader");
                return;
            }

            System.out.println("[HotSwapAgent] Класс найден: " + target.getName() + " loader=" + target.getClassLoader());

            try {
                instrumentation.redefineClasses(new ClassDefinition(target, bytecode));
                dos.writeUTF("OK: " + className + " перезагружен");
                System.out.println("[HotSwapAgent] Hot-swap успешен: " + className);
            } catch (UnsupportedOperationException e) {
                dos.writeUTF("ERROR: Нельзя изменить структуру класса (добавление/удаление методов/полей не поддерживается стандартной JVM).\nМожно менять только тела методов.\nДетали: " + e.getMessage());
            } catch (ClassFormatError e) {
                dos.writeUTF("ERROR: Неверный формат байткода: " + e.getMessage());
            } catch (UnmodifiableClassException e) {
                dos.writeUTF("ERROR: Класс нельзя изменить (системный класс): " + e.getMessage());
            } catch (Exception e) {
                dos.writeUTF("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("[HotSwapAgent] Ошибка обработки клиента: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private static Class<?> findClass(String className) {
        Class<?>[] allClasses = instrumentation.getAllLoadedClasses();

        for (Class<?> c : allClasses) {
            if (c.getName().equals(className)) {
                return c;
            }
        }

        String binaryName = className.replace('.', '/');
        for (Class<?> c : allClasses) {
            try {
                String name = c.getName().replace('.', '/');
                if (name.equals(binaryName) || name.endsWith("/" + binaryName)) {
                    return c;
                }
            } catch (Exception ignored) {}
        }

        return null;
    }
}
