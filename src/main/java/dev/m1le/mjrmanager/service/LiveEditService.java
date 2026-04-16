package dev.m1le.mjrmanager.service;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.jar.*;

public class LiveEditService {

    private static final int AGENT_PORT = 57321;
    private Process runningProcess;
    private boolean running = false;
    private File agentJar;

    public boolean isRunning() {
        return running && runningProcess != null && runningProcess.isAlive();
    }

    public Process startWithAgent(File jarFile, String mainClass, OutputCallback output) throws IOException {
        agentJar = buildAgentJar();
        System.out.println("[LiveEdit] Agent JAR: " + agentJar.getAbsolutePath() + " size=" + agentJar.length());

        String javaExe = ProcessHandle.current().info().command().orElse("java");

        ProcessBuilder pb = new ProcessBuilder(
            javaExe,
            "-javaagent:" + agentJar.getAbsolutePath(),
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005",
            "-jar", jarFile.getAbsolutePath()
        );
        pb.redirectErrorStream(true);
        pb.directory(jarFile.getParentFile());

        runningProcess = pb.start();
        running = true;

        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(runningProcess.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.onLine(line);
                }
            } catch (IOException ignored) {}
            running = false;
            try { output.onExit(runningProcess.exitValue()); } catch (Exception ignored) {}
        }, "jar-output-reader");
        reader.setDaemon(true);
        reader.start();

        return runningProcess;
    }

    public String hotSwapClass(String className, byte[] bytecode) {
        if (!isRunning()) {
            return "ERROR: JAR не запущен в Live режиме";
        }

        int retries = 3;
        for (int i = 0; i < retries; i++) {
            try (Socket socket = new Socket("localhost", AGENT_PORT);
                 DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                 DataInputStream dis = new DataInputStream(socket.getInputStream())) {

                socket.setSoTimeout(8000);
                dos.writeUTF(className);
                dos.writeInt(bytecode.length);
                dos.write(bytecode);
                dos.flush();

                return dis.readUTF();

            } catch (ConnectException e) {
                if (i < retries - 1) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    continue;
                }
                return "ERROR: агент не отвечает. Убедитесь что JAR запущен в Live режиме и агент инициализирован (ждите сообщение '[HotSwapAgent] Сервер запущен' в консоли)";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }
        return "ERROR: не удалось подключиться к агенту";
    }

    public void stop() {
        if (runningProcess != null && runningProcess.isAlive()) {
            runningProcess.destroyForcibly();
        }
        running = false;
    }

    private File buildAgentJar() throws IOException {
        File agentFile = Files.createTempFile("mjrmanager-agent-", ".jar").toFile();
        agentFile.deleteOnExit();

        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Premain-Class", "dev.m1le.mjrmanager.agent.HotSwapAgent");
        attrs.putValue("Agent-Class", "dev.m1le.mjrmanager.agent.HotSwapAgent");
        attrs.putValue("Can-Redefine-Classes", "true");
        attrs.putValue("Can-Retransform-Classes", "true");
        attrs.putValue("Can-Set-Native-Method-Prefix", "true");

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(agentFile), manifest)) {
            String classResource = "dev/m1le/mjrmanager/agent/HotSwapAgent.class";
            try (InputStream is = LiveEditService.class.getClassLoader().getResourceAsStream(classResource)) {
                if (is == null) {
                    throw new IOException("Не найден ресурс агента: " + classResource + ". Убедитесь что проект скомпилирован.");
                }
                JarEntry entry = new JarEntry(classResource);
                jos.putNextEntry(entry);
                jos.write(is.readAllBytes());
                jos.closeEntry();
            }
        }

        return agentFile;
    }

    @FunctionalInterface
    public interface OutputCallback {
        void onLine(String line);
        default void onExit(int code) {}
    }
}
