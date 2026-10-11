package io.translatetube.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Rewrites NewPipeExtractor calls to Java library methods missing from Android 9.
 * Same-signature calls (URLDecoder, URLEncoder, Collectors) keep stack shape;
 * String.isBlank is rewritten to a static helper taking the receiver as its argument.
 */
public abstract class PatchNewPipeApiTask extends DefaultTask {
    private static final String COMPAT =
        "io/translatetube/local/compat/Android9JavaApiCompat";
    private static final String CHARSET_STRING =
        "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;";
    private static final String COLLECTOR =
        "()Ljava/util/stream/Collector;";

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputJar();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @TaskAction
    public void patch() throws IOException {
        Path inputPath = getInputJar().get().getAsFile().toPath();
        Path outputPath = getOutputJar().get().getAsFile().toPath();
        Files.createDirectories(outputPath.getParent());

        int[] patched = new int[14];
        try (JarFile input = new JarFile(inputPath.toFile());
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(outputPath))) {
            Enumeration<JarEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    output.putNextEntry(new JarEntry(entry.getName()));
                    output.closeEntry();
                    continue;
                }
                if (isSignatureFile(entry.getName())) continue;

                byte[] bytes;
                try (InputStream stream = input.getInputStream(entry)) {
                    bytes = readFully(stream);
                }
                if (entry.getName().endsWith(".class")) {
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(reader, 0);
                    ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                         String signature, String[] exceptions) {
                            MethodVisitor delegate = super.visitMethod(access, name, descriptor,
                                signature, exceptions);
                            return new MethodVisitor(Opcodes.ASM9, delegate) {
                                @Override
                                public void visitMethodInsn(int opcode, String owner, String method,
                                                            String desc, boolean isInterface) {
                                    if (opcode == Opcodes.INVOKESTATIC
                                            && "java/net/URLDecoder".equals(owner)
                                            && "decode".equals(method)
                                            && CHARSET_STRING.equals(desc)) {
                                        owner = COMPAT;
                                        patched[0]++;
                                    } else if (opcode == Opcodes.INVOKESTATIC
                                            && "java/net/URLEncoder".equals(owner)
                                            && "encode".equals(method)
                                            && CHARSET_STRING.equals(desc)) {
                                        owner = COMPAT;
                                        patched[1]++;
                                    } else if (opcode == Opcodes.INVOKESTATIC
                                            && "java/util/stream/Collectors".equals(owner)
                                            && "toUnmodifiableList".equals(method)
                                            && COLLECTOR.equals(desc)) {
                                        owner = COMPAT;
                                        patched[2]++;
                                    } else if (opcode == Opcodes.INVOKESTATIC
                                            && "java/util/stream/Collectors".equals(owner)
                                            && "toUnmodifiableSet".equals(method)
                                            && COLLECTOR.equals(desc)) {
                                        owner = COMPAT;
                                        patched[12]++;
                                    } else if (opcode == Opcodes.INVOKESTATIC
                                            && "java/util/stream/Collectors".equals(owner)
                                            && "toUnmodifiableMap".equals(method)
                                            && (("(Ljava/util/function/Function;Ljava/util/function/Function;)Ljava/util/stream/Collector;").equals(desc)
                                                || "(Ljava/util/function/Function;Ljava/util/function/Function;Ljava/util/function/BinaryOperator;)Ljava/util/stream/Collector;".equals(desc))) {
                                        owner = COMPAT;
                                        patched[13]++;
                                    } else if (opcode == Opcodes.INVOKESTATIC
                                            && "java/util/List".equals(owner)
                                            && "of".equals(method)) {
                                        owner = COMPAT;
                                        method = "listOf";
                                        isInterface = false;
                                        patched[4]++;
                                    } else if (opcode == Opcodes.INVOKESTATIC
                                            && "java/util/Set".equals(owner)
                                            && "of".equals(method)) {
                                        owner = COMPAT;
                                        method = "setOf";
                                        isInterface = false;
                                        patched[5]++;
                                    } else if (opcode == Opcodes.INVOKESTATIC
                                            && "java/util/Map".equals(owner)
                                            && "of".equals(method)) {
                                        owner = COMPAT;
                                        method = "mapOf";
                                        isInterface = false;
                                        patched[6]++;
                                    } else if (opcode == Opcodes.INVOKESTATIC
                                            && "java/util/Map".equals(owner)
                                            && "ofEntries".equals(method)
                                            && "([Ljava/util/Map$Entry;)Ljava/util/Map;".equals(desc)) {
                                        owner = COMPAT;
                                        method = "mapOfEntries";
                                        isInterface = false;
                                        patched[7]++;
                                    } else if (opcode == Opcodes.INVOKESTATIC
                                            && "java/util/List".equals(owner)
                                            && "copyOf".equals(method)
                                            && "(Ljava/util/Collection;)Ljava/util/List;".equals(desc)) {
                                        owner = COMPAT;
                                        method = "listCopyOf";
                                        isInterface = false;
                                        patched[8]++;
                                    } else if (opcode == Opcodes.INVOKESTATIC
                                            && "java/util/Set".equals(owner)
                                            && "copyOf".equals(method)
                                            && "(Ljava/util/Collection;)Ljava/util/Set;".equals(desc)) {
                                        owner = COMPAT;
                                        method = "setCopyOf";
                                        isInterface = false;
                                        patched[9]++;
                                    } else if (opcode == Opcodes.INVOKESTATIC
                                            && "java/util/Map".equals(owner)
                                            && "copyOf".equals(method)
                                            && "(Ljava/util/Map;)Ljava/util/Map;".equals(desc)) {
                                        owner = COMPAT;
                                        method = "mapCopyOf";
                                        isInterface = false;
                                        patched[10]++;
                                    } else if (opcode == Opcodes.INVOKESTATIC
                                            && "java/util/Objects".equals(owner)
                                            && "requireNonNullElse".equals(method)
                                            && "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;".equals(desc)) {
                                        owner = COMPAT;
                                        method = "requireNonNullElse";
                                        patched[11]++;
                                    } else if (opcode == Opcodes.INVOKESTATIC
                                            && "java/util/Objects".equals(owner)
                                            && "requireNonNullElseGet".equals(method)
                                            && "(Ljava/lang/Object;Ljava/util/function/Supplier;)Ljava/lang/Object;".equals(desc)) {
                                        owner = COMPAT;
                                        method = "requireNonNullElseGet";
                                        patched[11]++;
                                    } else if (opcode == Opcodes.INVOKEVIRTUAL
                                            && "java/lang/String".equals(owner)
                                            && "isBlank".equals(method)
                                            && "()Z".equals(desc)) {
                                        opcode = Opcodes.INVOKESTATIC;
                                        owner = COMPAT;
                                        method = "isBlank";
                                        desc = "(Ljava/lang/String;)Z";
                                        isInterface = false;
                                        patched[3]++;
                                    }
                                    super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                                }
                            };
                        }
                    };
                    reader.accept(visitor, 0);
                    bytes = writer.toByteArray();
                }

                output.putNextEntry(new JarEntry(entry.getName()));
                output.write(bytes);
                output.closeEntry();
            }
        }

        if (patched[0] == 0) {
            throw new GradleException("NewPipeExtractor artifact did not contain the expected URLDecoder.decode(String, Charset) call; refusing an unpatched Android 9 build.");
        }
        if (patched[2] == 0) {
            getLogger().warn("No Collectors.toUnmodifiableList() calls were found in NewPipeExtractor.");
        }
        getLogger().lifecycle(
            "Android 9 compatibility patch applied to NewPipeExtractor: URL decode={}, URL encode={}, lists={}, isBlank={}, List.of={}, Set.of={}, Map.of={}, Map.ofEntries={}, copyOf calls={}, Objects null-default calls={}, unmodifiable sets={}, unmodifiable maps={}",
            patched[0], patched[1], patched[2], patched[3], patched[4], patched[5], patched[6],
            patched[7], patched[8] + patched[9] + patched[10], patched[11], patched[12], patched[13]
        );
    }

    private static boolean isSignatureFile(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) return false;
        return upper.endsWith(".SF") || upper.endsWith(".RSA")
            || upper.endsWith(".DSA") || upper.endsWith(".EC")
            || upper.contains("/SIG-");
    }

    private static byte[] readFully(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }
}
