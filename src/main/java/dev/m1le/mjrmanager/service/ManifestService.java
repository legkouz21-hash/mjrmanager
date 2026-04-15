package dev.m1le.mjrmanager.service;

import java.io.*;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public class ManifestService {

    public String getManifestContent(Map<String, byte[]> jarEntries) {
        byte[] manifestBytes = jarEntries.get("META-INF/MANIFEST.MF");
        
        if (manifestBytes == null) {
            return createDefaultManifest();
        }

        try {
            return new String(manifestBytes, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return new String(manifestBytes);
        }
    }


    public void updateManifest(Map<String, byte[]> jarEntries, String manifestContent) throws IOException {

        validateManifest(manifestContent);
        
        byte[] manifestBytes = manifestContent.getBytes("UTF-8");
        jarEntries.put("META-INF/MANIFEST.MF", manifestBytes);
    }

    public Map<String, String> parseManifest(String manifestContent) throws IOException {
        Map<String, String> attributes = new LinkedHashMap<>();
        
        try (ByteArrayInputStream bais = new ByteArrayInputStream(manifestContent.getBytes("UTF-8"))) {
            Manifest manifest = new Manifest(bais);
            Attributes mainAttributes = manifest.getMainAttributes();
            
            for (Object key : mainAttributes.keySet()) {
                String keyStr = key.toString();
                String value = mainAttributes.getValue(keyStr);
                attributes.put(keyStr, value);
            }
        }
        
        return attributes;
    }

    public String createManifestFromAttributes(Map<String, String> attributes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Manifest-Version: 1.0\n");
        
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (!entry.getKey().equals("Manifest-Version")) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
        
        sb.append("\n");
        return sb.toString();
    }

    private void validateManifest(String manifestContent) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(manifestContent.getBytes("UTF-8"))) {
            new Manifest(bais);
        } catch (IOException e) {
            throw new IOException("Неверный формат манифеста: " + e.getMessage());
        }
    }

    private String createDefaultManifest() {
        return "Manifest-Version: 1.0\n" +
               "Created-By: MJRManager\n\n";
    }

    public String getMainClass(Map<String, byte[]> jarEntries) {
        try {
            String content = getManifestContent(jarEntries);
            Map<String, String> attributes = parseManifest(content);
            return attributes.get("Main-Class");
        } catch (Exception e) {
            return null;
        }
    }

    public void setMainClass(Map<String, byte[]> jarEntries, String mainClass) throws IOException {
        String content = getManifestContent(jarEntries);
        Map<String, String> attributes = parseManifest(content);
        attributes.put("Main-Class", mainClass);
        String newContent = createManifestFromAttributes(attributes);
        updateManifest(jarEntries, newContent);
    }
}
