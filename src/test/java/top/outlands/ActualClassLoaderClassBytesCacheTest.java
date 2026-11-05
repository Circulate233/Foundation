package top.outlands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import top.outlands.foundation.boot.ActualClassLoader;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@code getClassBytes} is called with both the internal ({@code pkg/Cls}) and the binary
 * ({@code pkg.Cls}) spelling of a name; both must share one cache entry instead of reading the class
 * file twice, and the negative cache must answer for both spellings too.
 */
class ActualClassLoaderClassBytesCacheTest {

    @Test
    void bothSeparatorsShareOneCacheEntry(@TempDir Path dir) throws Exception {
        try (ActualClassLoader loader = loader(dir)) {
            final byte[] binary = loader.getClassBytes("pkg.Cls");
            final byte[] internal = loader.getClassBytes("pkg/Cls");

            assertNotNull(binary);
            assertNotNull(internal);
            assertSame(binary, internal, "the slash separated name must hit the binary entry");
        }
    }

    @Test
    void internalNameFirstAlsoCachesForTheBinaryName(@TempDir Path dir) throws Exception {
        try (ActualClassLoader loader = loader(dir)) {
            final byte[] internal = loader.getClassBytes("pkg/Cls");
            final byte[] binary = loader.getClassBytes("pkg.Cls");

            assertNotNull(internal);
            assertSame(internal, binary, "the binary name must hit the internal entry");
        }
    }

    @Test
    void theNegativeCacheIsShared(@TempDir Path dir) throws Exception {
        try (ActualClassLoader loader = loader(dir)) {
            assertNull(loader.getClassBytes("missing/NotThere"));
            assertNull(loader.getClassBytes("missing.NotThere"));
        }
    }

    @Test
    void clearNegativeEntriesAcceptsBothSpellings(@TempDir Path dir) throws Exception {
        try (ActualClassLoader loader = loader(dir)) {
            assertNull(loader.getClassBytes("missing.NotThere"));

            loader.clearNegativeEntries(Set.of("missing/NotThere"));

            // no longer served from the negative cache, so the lookup runs again (and still finds nothing)
            assertNull(loader.getClassBytes("missing.NotThere"));
            assertNull(loader.getClassBytes("missing/NotThere"));
        }
    }

    @Test
    void testGetClassBytesIsUncachedButServesCachedBytes(@TempDir Path dir) throws Exception {
        try (ActualClassLoader loader = loader(dir)) {
            final byte[] viaTest = loader.testGetClassBytes("pkg/Cls");
            assertNotNull(viaTest);
            // not cached by the probing variant
            assertNotNull(loader.getClassBytes("pkg.Cls"));

            final byte[] cached = loader.getClassBytes("pkg/Cls");
            assertSame(cached, loader.testGetClassBytes("pkg.Cls"), "the probe must reuse the cached array");
            assertArrayEquals(viaTest, cached);
        }
    }

    private static ActualClassLoader loader(Path dir) throws IOException {
        return new ActualClassLoader(
            new URL[]{jar(dir).toUri().toURL()}, ActualClassLoader.class.getClassLoader());
    }

    private static Path jar(Path dir) throws IOException {
        final Path file = dir.resolve("classes.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : Map.of("pkg/Cls.class", emptyClass("pkg/Cls")).entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file;
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
}
