package top.outlands.foundation.install;

import java.io.File;
import java.net.URL;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The launch classpath, as a list of files, and the few lookups the install step needs from it.
 *
 * <p>Everything here is name based on purpose: the install step must work for a launcher that resolves libraries
 * into a shared directory and for a self-contained pack that ships them next to the instance, and neither tells us
 * where anything is.
 */
public final class Classpath {
    /** The resource every loader jar carries and nothing else on a game classpath does. */
    public static final String PATCH_PACK = "binpatches.pack.lzma";

    private Classpath() {
    }

    /** {@code java.class.path}, in order, without the entries the JVM invented (empty strings). */
    public static List<Path> entries() {
        final String classpath = System.getProperty("java.class.path", "");
        final List<Path> entries = new ArrayList<>();
        for (final String entry : classpath.split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            entries.add(Paths.get(entry));
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * The loader jar: the classpath entry that carries {@link #PATCH_PACK}.
     *
     * <p>Entries named like our artifact are tried first, so a normal launch answers without opening every jar.
     */
    public static Optional<Path> loaderJar() {
        final List<Path> entries = entries();
        for (final Path entry : entries) {
            if (looksLikeLoader(entry) && carries(entry, PATCH_PACK)) {
                return Optional.of(entry);
            }
        }
        for (final Path entry : entries) {
            if (carries(entry, PATCH_PACK)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /**
     * The game jar the launcher put on the classpath.
     *
     * @param side {@code client} or {@code server}
     */
    public static Optional<Path> gameJar(String side) {
        final String suffix = "-" + side + ".jar";
        // the launcher's jar is 'minecraft-<version>-<side>.jar'; our own build artifacts are 'outlands-...', and
        // the name is the only thing that tells a game jar from any other jar on this classpath
        for (final Path entry : entries()) {
            final String name = entry.getFileName().toString();
            if (name.startsWith("minecraft-") && name.endsWith(suffix)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** The jar that provides an entry such as {@code LZMA/LzmaInputStream.class}. */
    public static Optional<Path> jarWithEntry(String resourcePath) {
        for (final Path entry : entries()) {
            if (carries(entry, resourcePath)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    private static boolean looksLikeLoader(Path path) {
        final String name = path.getFileName().toString();
        return name.startsWith("outlands-") && name.endsWith(".jar");
    }

    private static boolean carries(Path path, String resourcePath) {
        if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".jar")) {
            return false;
        }
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            return jar.getEntry(resourcePath) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The classpath to hand to the game's class loader: {@code overlay} first when there is one, then everything the
     * launcher resolved. Order matters - the class loader resolves class files in the order of this array, so the
     * patched classes shadow the vanilla ones and everything else keeps coming from the vanilla jar.
     */
    public static URL[] launchClasspath(Path overlay) {
        final List<URL> urls = new ArrayList<>();
        if (overlay != null) {
            try {
                urls.add(overlay.toUri().toURL());
            } catch (java.net.MalformedURLException e) {
                throw new IllegalStateException("cannot turn " + overlay + " into a classpath entry", e);
            }
        }
        for (final Path entry : entries()) {
            if (overlay != null && entry.equals(overlay)) {
                continue;
            }
            try {
                urls.add(entry.toUri().toURL());
            } catch (java.net.MalformedURLException e) {
                throw new IllegalStateException("cannot turn " + entry + " into a classpath entry", e);
            }
        }
        return urls.toArray(new URL[0]);
    }

    /** Directories on the classpath have nothing to do with the install step, but tests use this for clarity. */
    static Stream<Path> jars() {
        return entries().stream().filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".jar"));
    }
}
