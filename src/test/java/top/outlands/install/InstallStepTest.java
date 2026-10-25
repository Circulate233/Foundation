package top.outlands.foundation.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The install step's bookkeeping: fingerprints taken from zip central directories, and the marker that decides
 * whether yesterday's artifact can be reused. None of this may read jar contents on the hot path, which is what the
 * fingerprint shape enforces.
 */
class InstallStepTest {

    @TempDir
    Path dir;

    // ---------------------------------------------------------------- fingerprints

    @Test
    void fingerprintIsStableForTheSameContent() throws Exception {
        final Path first = jar("a.jar", Map.of("x/A.class", bytes("one"), "x/B.class", bytes("two")));
        final Path second = jar("b.jar", Map.of("x/B.class", bytes("two"), "x/A.class", bytes("one")));

        final ZipFingerprint a = ZipFingerprint.of(first);
        final ZipFingerprint b = ZipFingerprint.of(second);

        assertEquals(a.aggregate(), b.aggregate(), "insertion order must not matter, entries are sorted by name");
        assertEquals(a.entries(), b.entries());
        assertTrue(a.matches(first));
    }

    @Test
    void fingerprintChangesWhenAClassChanges() throws Exception {
        final Path before = jar("before.jar", Map.of("x/A.class", bytes("one"), "x/B.class", bytes("two")));
        final Path after = jar("after.jar", Map.of("x/A.class", bytes("one"), "x/B.class", bytes("two but different")));

        assertNotEquals(ZipFingerprint.of(before).aggregate(), ZipFingerprint.of(after).aggregate());
        assertFalse(ZipFingerprint.of(before).matches(after), "a jar that changed must not match the old fingerprint");
    }

    @Test
    void entryCrcComesFromTheDirectoryOnly() throws Exception {
        final Path jar = jar("pack.jar", Map.of("binpatches.pack.lzma", bytes("payload")));

        final long crc = ZipFingerprint.entryCrc(jar, "binpatches.pack.lzma");
        assertEquals(crcOf(bytes("payload")), crc);
        assertEquals(-1L, ZipFingerprint.entryCrc(jar, "not-there"));
    }

    // ---------------------------------------------------------------- marker

    @Test
    void markerRoundTripsAndDecidesReuse() throws Exception {
        final Path artifact = dir.resolve("outlands-1.0-mc-client-patched.jar");
        final ZipFingerprint input = new ZipFingerprint(7957, 2580656613L, 10_180_113L);
        final ZipFingerprint output = new ZipFingerprint(1170, 1782657135L, 2_839_327L);
        InstallMarker.of("1.0", "client", 3060476666L, input, output).write(artifact);

        final Optional<InstallMarker> read = InstallMarker.read(artifact);
        assertTrue(read.isPresent());
        assertEquals(output, read.get().output());
        assertTrue(read.get().describes("1.0", "client", 3060476666L, input));

        assertFalse(read.get().describes("1.1", "client", 3060476666L, input), "another loader version is a miss");
        assertFalse(read.get().describes("1.0", "server", 3060476666L, input), "another side is a miss");
        assertFalse(read.get().describes("1.0", "client", 1L, input), "another patch pack is a miss");
        assertFalse(read.get().describes("1.0", "client", 3060476666L, new ZipFingerprint(1, 2L, 3L)),
                "another vanilla jar is a miss");
    }

    @Test
    void missingOrBrokenMarkerIsSimplyAMiss() throws Exception {
        final Path artifact = dir.resolve("outlands-1.0-mc-client-patched.jar");
        assertTrue(InstallMarker.read(artifact).isEmpty());

        Files.writeString(InstallMarker.pathOf(artifact), "format=1\nside=client\n", StandardCharsets.UTF_8);
        assertTrue(InstallMarker.read(artifact).isEmpty(), "a marker missing its fingerprints cannot be trusted");
    }

    // ---------------------------------------------------------------- layout

    @Test
    void layoutPutsArtifactsUnderTheLibrariesRoot() {
        final InstallLayout layout = InstallLayout.at(dir, "2026.10.25", "client");

        assertEquals(dir.resolve("top/outlands/outlands/2026.10.25/outlands-2026.10.25-mc-client-patched.jar"),
                layout.artifact());
        assertEquals(dir.resolve("top/outlands/outlands/2026.10.25/outlands-2026.10.25-mc-client-patched.jar.cache"),
                layout.marker());
    }

    @Test
    void layoutHonoursTheOverrideProperty() throws Exception {
        final Path libraries = Files.createDirectories(dir.resolve("somewhere/libraries"));
        final String previous = System.getProperty(InstallLayout.DIR_PROPERTY);
        try {
            System.setProperty(InstallLayout.DIR_PROPERTY, libraries.toString());
            assertEquals(Optional.of(libraries), InstallLayout.librariesRoot());

            final InstallLayout layout = InstallLayout.detect("1.0", "client").orElseThrow();
            assertEquals(libraries.resolve("top/outlands/outlands/1.0"), layout.directory());
            assertTrue(layout.ensureDirectory());
            assertTrue(Files.isDirectory(layout.directory()));
        } finally {
            if (previous == null) {
                System.clearProperty(InstallLayout.DIR_PROPERTY);
            } else {
                System.setProperty(InstallLayout.DIR_PROPERTY, previous);
            }
        }
    }

    @Test
    void loaderVersionIsTheFileNameBetweenPrefixAndSuffix() {
        assertEquals("2026.10.25", PatchInstaller.loaderVersion(dir.resolve("outlands-2026.10.25.jar")));
        assertEquals("2026.10.25-2", PatchInstaller.loaderVersion(dir.resolve("outlands-2026.10.25-2.jar")));
        assertEquals("odd-name.jar", PatchInstaller.loaderVersion(dir.resolve("odd-name.jar")));
    }

    // ---------------------------------------------------------------- launch classpath

    @Test
    void theOverlayGoesFirstAndIsNotDuplicated() throws Exception {
        final Path overlay = jar("overlay.jar", Map.of("x/A.class", bytes("patched")));

        final java.net.URL[] plain = Classpath.launchClasspath(null);
        final java.net.URL[] withOverlay = Classpath.launchClasspath(overlay);

        assertEquals(plain.length + 1, withOverlay.length);
        assertEquals(overlay.toUri().toURL(), withOverlay[0], "the patched classes must shadow the vanilla ones");
        assertArrayEquals(plain, java.util.Arrays.copyOfRange(withOverlay, 1, withOverlay.length),
                "everything else keeps the launcher's order");
        assertEquals(0, Stream.of(withOverlay).filter(overlay.toUri().toURL()::equals).count() - 1,
                "the overlay must appear exactly once");
    }

    // ---------------------------------------------------------------- fixtures

    private Path jar(String name, Map<String, byte[]> entries) throws IOException {
        final Path file = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(entries).entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    private static long crcOf(byte[] bytes) {
        final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes, 0, bytes.length);
        return crc.getValue();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
