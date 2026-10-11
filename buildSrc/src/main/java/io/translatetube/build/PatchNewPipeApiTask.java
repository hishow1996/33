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

        int[] patched = new int[4];
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
            "Android 9 compatibility patch applied to NewPipeExtractor: URL decode={}, URL encode={}, unmodifiable lists={}, String.isBlank={}",
            patched[0], patched[1], patched[2], patched[3]
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
