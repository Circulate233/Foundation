package top.outlands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import top.outlands.foundation.boot.ActualClassLoader;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Classes of one jar must see the package, manifest and code source a per-class JarURLConnection gave them. */
class ActualClassLoaderJarMetadataTest {

    @Test
    void thePackageComesFromTheJarManifest(@TempDir Path dir) throws Exception {
        Path jar = jar(dir, "mod.jar", Map.of("pkg/one/First.class", emptyClass("pkg/one/First")));

        try (ActualClassLoader loader = new ActualClassLoader(new URL[]{jar.toUri().toURL()}, parent())) {
            Class<?> loaded = loader.loadClass("pkg.one.First");

            assertSame(loader, loaded.getClassLoader());
            assertNotNull(loaded.getPackage());
            assertEquals("metadata-test", loaded.getPackage().getSpecificationTitle());
        }
    }

    @Test
    void theCodeSourceIsTheJarEntryUrl(@TempDir Path dir) throws Exception {
        Path jar = jar(dir, "mod.jar", Map.of("pkg/one/First.class", emptyClass("pkg/one/First")));

        try (ActualClassLoader loader = new ActualClassLoader(new URL[]{jar.toUri().toURL()}, parent())) {
            Class<?> loaded = loader.loadClass("pkg.one.First");
            assertEquals(
                loader.findResource("pkg/one/First.class").toExternalForm(),
                loaded.getProtectionDomain().getCodeSource().getLocation().toExternalForm());
        }
    }

    @Test
    void manyClassesOfOneJarAllLoad(@TempDir Path dir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            entries.put("bulk/C" + i + ".class", emptyClass("bulk/C" + i));
        }

        try (ActualClassLoader loader = new ActualClassLoader(
            new URL[]{jar(dir, "bulk.jar", entries).toUri().toURL()}, parent())) {
            for (int i = 0; i < 40; i++) {
                Class<?> loaded = loader.loadClass("bulk.C" + i);
                assertSame(loader, loaded.getClassLoader());
                assertNotNull(loaded.getPackage(), "package of " + loaded.getName());
                assertEquals("metadata-test", loaded.getPackage().getSpecificationTitle());
            }
        }
    }

    @Test
    void aJarWithoutManifestStillLoads(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("nomanifest.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("plain/Cls.class"));
            out.write(emptyClass("plain/Cls"));
            out.closeEntry();
        }

        try (ActualClassLoader loader = new ActualClassLoader(new URL[]{jar.toUri().toURL()}, parent())) {
            Class<?> loaded = loader.loadClass("plain.Cls");
            assertSame(loader, loaded.getClassLoader());
            assertNotNull(loaded.getPackage());
        }
    }

    private static ClassLoader parent() {
        return ActualClassLoader.class.getClassLoader();
    }

    private static byte[] emptyClass(String internalName) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Path jar(Path dir, String name, Map<String, byte[]> entries) throws IOException {
        final Path file = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\r\nSpecification-Title: metadata-test\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file;
    }
}
