package dev.m1le.mjrmanager.service;

import org.objectweb.asm.*;

import java.util.*;

public class DependencyService {

    public static class DependencyResult {
        public String targetClass;
        public List<String> usedBy = new ArrayList<>();
        public List<String> uses = new ArrayList<>();
    }

    public DependencyResult analyze(String targetClassName, Map<String, byte[]> allEntries) {
        DependencyResult result = new DependencyResult();
        result.targetClass = targetClassName;

        String normalizedTarget = targetClassName.replace(".class", "").replace('/', '.');

        for (Map.Entry<String, byte[]> entry : allEntries.entrySet()) {
            if (!entry.getKey().endsWith(".class")) continue;

            String entryClassName = entry.getKey().replace(".class", "").replace('/', '.');
            if (entryClassName.equals(normalizedTarget)) {
                result.uses.addAll(collectDependencies(entry.getValue()));
                continue;
            }

            Set<String> deps = collectDependencies(entry.getValue());
            if (deps.contains(normalizedTarget)) {
                result.usedBy.add(entryClassName);
            }
        }

        result.uses.remove(normalizedTarget);
        Collections.sort(result.usedBy);
        Collections.sort(result.uses);

        return result;
    }

    private Set<String> collectDependencies(byte[] bytecode) {
        Set<String> deps = new HashSet<>();
        try {
            ClassReader reader = new ClassReader(bytecode);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    if (superName != null) deps.add(superName.replace('/', '.'));
                    if (interfaces != null) {
                        for (String iface : interfaces) deps.add(iface.replace('/', '.'));
                    }
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    addFromDescriptor(descriptor, deps);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    addFromDescriptor(descriptor, deps);
                    if (exceptions != null) {
                        for (String ex : exceptions) deps.add(ex.replace('/', '.'));
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            deps.add(type.replace('/', '.'));
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            deps.add(owner.replace('/', '.'));
                            addFromDescriptor(descriptor, deps);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            deps.add(owner.replace('/', '.'));
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Exception ignored) {}
        return deps;
    }

    private void addFromDescriptor(String descriptor, Set<String> deps) {
        if (descriptor == null) return;
        int i = 0;
        while (i < descriptor.length()) {
            if (descriptor.charAt(i) == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end > i) {
                    deps.add(descriptor.substring(i + 1, end).replace('/', '.'));
                    i = end + 1;
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }
    }
}
