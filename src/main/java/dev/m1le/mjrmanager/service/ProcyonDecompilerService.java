package dev.m1le.mjrmanager.service;

import com.strobel.assembler.InputTypeLoader;
import com.strobel.assembler.metadata.*;
import com.strobel.decompiler.DecompilationOptions;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;

import java.io.*;
import java.nio.file.*;
import java.util.Map;

public class ProcyonDecompilerService {

    public String decompile(String className, byte[] bytecode, Map<String, byte[]> allEntries) {
        try {
            DecompilerSettings settings = DecompilerSettings.javaDefaults();
            settings.setShowSyntheticMembers(false);
            settings.setForceExplicitImports(true);

            MetadataSystem metadataSystem = new MetadataSystem(new ITypeLoader() {
                @Override
                public boolean tryLoadType(String internalName, Buffer buffer) {
                    String key = internalName + ".class";
                    byte[] bytes = allEntries.get(key);
                    if (bytes == null) bytes = allEntries.get(internalName);
                    if (bytes == null) return false;
                    buffer.reset(bytes.length);
                    buffer.putByteArray(bytes, 0, bytes.length);
                    buffer.position(0);
                    return true;
                }
            });

            String internalName = className.replace(".class", "");
            TypeReference typeRef = metadataSystem.lookupType(internalName);
            if (typeRef == null) return decompileFromTempFile(className, bytecode);

            TypeDefinition typeDef = typeRef.resolve();
            if (typeDef == null) return decompileFromTempFile(className, bytecode);

            DecompilationOptions options = new DecompilationOptions();
            options.setSettings(settings);
            options.setFullDecompilation(true);

            StringWriter writer = new StringWriter();
            settings.getLanguage().decompileType(typeDef, new PlainTextOutput(writer), options);
            String result = writer.toString().trim();

            return result.isEmpty() ? decompileFromTempFile(className, bytecode) : result;
        } catch (Exception e) {
            return decompileFromTempFile(className, bytecode);
        }
    }

    private String decompileFromTempFile(String className, byte[] bytecode) {
        try {
            Path tempDir = Files.createTempDirectory("mjrmanager_procyon_");
            String classFileName = className.contains("/")
                    ? className.substring(className.lastIndexOf('/') + 1)
                    : className;
            if (!classFileName.endsWith(".class")) classFileName += ".class";

            Path classFile = tempDir.resolve(classFileName);
            Files.write(classFile, bytecode);

            DecompilerSettings settings = DecompilerSettings.javaDefaults();

            MetadataSystem metadataSystem = new MetadataSystem(new InputTypeLoader());
            TypeReference typeRef = metadataSystem.lookupType(classFile.toString());

            StringWriter writer = new StringWriter();
            if (typeRef != null) {
                TypeDefinition typeDef = typeRef.resolve();
                if (typeDef != null) {
                    DecompilationOptions options = new DecompilationOptions();
                    options.setSettings(settings);
                    options.setFullDecompilation(true);
                    settings.getLanguage().decompileType(typeDef, new PlainTextOutput(writer), options);
                }
            }

            Files.deleteIfExists(classFile);
            Files.deleteIfExists(tempDir);

            String result = writer.toString().trim();
            return result.isEmpty() ? "// Procyon: не удалось декомпилировать " + className : result;
        } catch (Exception e) {
            return "// Procyon error: " + e.getMessage();
        }
    }
}
