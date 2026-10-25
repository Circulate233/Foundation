package top.outlands.foundation.install;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Where the install step puts its artifacts.
 *
 * <pre>
 *   &lt;libraries&gt;/top/outlands/outlands/&lt;loaderVersion&gt;/outlands-&lt;loaderVersion&gt;-mc-&lt;side&gt;-patched.jar
 *   &lt;libraries&gt;/top/outlands/outlands/&lt;loaderVersion&gt;/outlands-&lt;loaderVersion&gt;-mc-&lt;side&gt;-patched.cache
 * </pre>
 *
 * <p>{@code <libraries>} is the directory the launcher hands us on the classpath - the shared one for a normal
 * instance, the instance's own for a self-contained pack. It is found by walking up from Foundation's own jar,
 * which lives at {@code <libraries>/top/outlands/foundation/<version>/foundation-<version>.jar}.
 */
public final class InstallLayout {
    /** Set to a directory to put the artifacts somewhere else; used by tests and by read-only installs. */
    public static final String DIR_PROPERTY = "outlands.install.dir";

    private static final String GROUP_PATH = "top/outlands/outlands";

    private final Path libraries;
    private final String loaderVersion;
    private final String side;

    private InstallLayout(Path libraries, String loaderVersion, String side) {
        this.libraries = libraries;
        this.loaderVersion = loaderVersion;
        this.side = side;
    }

    /** @return the layout for this run, or empty when no writable libraries directory could be found */
    public static Optional<InstallLayout> detect(String loaderVersion, String side) {
        final Optional<Path> root = librariesRoot();
        if (root.isEmpty()) {
            return Optional.empty();
        }
        final Path libraries = root.get();
        if (!isUsableDirectory(libraries)) {
            return Optional.empty();
        }
        return Optional.of(new InstallLayout(libraries, loaderVersion, side));
    }

    public Path libraries() {
        return libraries;
    }

    /** The directory the artifact and its marker live in. */
    public Path directory() {
        return libraries.resolve(GROUP_PATH).resolve(loaderVersion);
    }

    public Path artifact() {
        return directory().resolve(artifactName(loaderVersion, side));
    }

    public Path marker() {
        return directory().resolve(artifactName(loaderVersion, side) + ".cache");
    }

    private static String artifactName(String loaderVersion, String side) {
        return "outlands-" + loaderVersion + "-mc-" + side + "-patched.jar";
    }

    /**
     * The libraries directory: the {@code outlands.install.dir} override when set, otherwise the first ancestor of
     * Foundation's own jar that is called {@code libraries}.
     */
    public static Optional<Path> librariesRoot() {
        final String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isEmpty()) {
            return Optional.of(Paths.get(override).toAbsolutePath());
        }
        final Optional<Path> ownJar = ownJar();
        if (ownJar.isEmpty()) {
            return Optional.empty();
        }
        Path cursor = ownJar.get().getParent();
        for (int depth = 0; depth < 8 && cursor != null; depth++) {
            if (cursor.getFileName() != null && "libraries".equals(cursor.getFileName().toString())) {
                return Optional.of(cursor);
            }
            cursor = cursor.getParent();
        }
        return Optional.empty();
    }

    /** Foundation's own jar, i.e. the code source of this class. */
    public static Optional<Path> ownJar() {
        try {
            final URI location = InstallLayout.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Optional.of(Paths.get(location));
        } catch (URISyntaxException | FileSystemNotFoundException | NullPointerException | SecurityException e) {
            return Optional.empty();
        }
    }

    private static boolean isUsableDirectory(Path libraries) {
        try {
            return java.nio.file.Files.isDirectory(libraries) && java.nio.file.Files.isWritable(libraries);
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Creates the artifact directory, returning false when it cannot be created. */
    boolean ensureDirectory() {
        try {
            java.nio.file.Files.createDirectories(directory());
            return java.nio.file.Files.isWritable(directory());
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "libraries=" + libraries + ", artifact=" + artifact();
    }

    /** Only for tests and logging: a layout rooted at an arbitrary directory. */
    static InstallLayout at(Path libraries, String loaderVersion, String side) {
        return new InstallLayout(libraries, loaderVersion, side);
    }

    static File asFile(Path path) {
        return path.toFile();
    }
}
