package top.outlands.foundation.install;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The install step: makes sure a patched Minecraft exists on disk and hands its path to the launch.
 *
 * <p>Modelled on what modern loaders do inside their {@code mainClass} on first launch - the launcher itself never
 * installs anything (PrismLauncher has no installer code, and its meta URL is a compile time constant, so a pack
 * cannot ask it to). Our main class is Foundation, so this is where the work happens: find the vanilla jar the
 * launcher resolved, apply the patch pack from the loader jar once, remember what was done in a marker next to the
 * artifact, and reuse it on every later launch.
 *
 * <p>Everything here is best effort. Any failure - a read-only libraries directory, a loader jar too old to carry
 * the applier, a full disk - logs and returns empty, which leaves the game patching at runtime exactly as it does
 * without this class.
 */
public final class PatchInstaller {
    /** {@code off} disables the step; anything else (the default) enables it. */
    public static final String ENABLED_PROPERTY = "outlands.install";
    /** {@code dir} (default) checks the artifact's directory fingerprint, {@code deep} decompresses it, {@code off} trusts the marker. */
    public static final String VERIFY_PROPERTY = "outlands.install.verify";
    /** Set by {@link #prepare()} and read by the loader, which then skips its own patching. */
    public static final String PATCHED_JAR_PROPERTY = "outlands.patchedJar";

    private static final Logger LOGGER = LogManager.getLogger("Foundation");

    private PatchInstaller() {
    }

    /**
     * @return the patched jar to put in front of the launch classpath, or empty when there is none to use
     */
    public static Optional<Path> prepare() {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
            LOGGER.debug("install: disabled by -D{}=false", ENABLED_PROPERTY);
            return Optional.empty();
        }
        try {
            return install();
        } catch (Throwable t) {
            LOGGER.warn("install: failed, the game will patch its classes at runtime as before", t);
            return Optional.empty();
        }
    }

    private static Optional<Path> install() throws IOException {
        final long started = System.nanoTime();

        final Optional<Path> loaderJar = Classpath.loaderJar();
        if (loaderJar.isEmpty()) {
            LOGGER.debug("install: no loader jar on the classpath, nothing to do");
            return Optional.empty();
        }
        final Optional<Path> gameJar = Classpath.gameJar("client");
        final String side = gameJar.isPresent() ? "client" : "server";
        final Optional<Path> resolvedGameJar = gameJar.isPresent() ? gameJar : Classpath.gameJar(side);
        if (resolvedGameJar.isEmpty()) {
            LOGGER.debug("install: no minecraft jar on the classpath (development run?), nothing to do");
            return Optional.empty();
        }

        final Path input = resolvedGameJar.get();
        final String loaderVersion = loaderVersion(loaderJar.get());
        final long packCrc = ZipFingerprint.entryCrc(loaderJar.get(), Classpath.PATCH_PACK);
        if (packCrc < 0L) {
            LOGGER.debug("install: {} carries no {}, nothing to do", loaderJar.get().getFileName(), Classpath.PATCH_PACK);
            return Optional.empty();
        }

        final Optional<InstallLayout> detected = InstallLayout.detect(loaderVersion, side);
        if (detected.isEmpty()) {
            LOGGER.debug("install: no writable libraries directory found, the patches stay in memory");
            return Optional.empty();
        }
        final InstallLayout layout = detected.get();
        final Path artifact = layout.artifact();

        final ZipFingerprint inputFingerprint = ZipFingerprint.of(input);
        final Optional<InstallMarker> existing = InstallMarker.read(artifact);
        if (existing.isPresent() && existing.get().describes(loaderVersion, side, packCrc, inputFingerprint)
                && verify(artifact, existing.get())) {
            LOGGER.info("install: reusing {} ({})", artifact, existing.get().output());
            return Optional.of(artifact);
        }

        if (!layout.ensureDirectory()) {
            LOGGER.debug("install: cannot write to {}, the patches stay in memory", layout.directory());
            return Optional.empty();
        }
        final Path temp = layout.directory().resolve(artifact.getFileName() + ".part");
        Files.deleteIfExists(temp);

        final ApplierProcess.Result result = ApplierProcess.run(loaderJar.get(), input, temp, side);
        if (!result.ok()) {
            LOGGER.warn("install: the patch applier did not succeed ({}), the game will patch its classes at runtime", result.describe());
            Files.deleteIfExists(temp);
            return Optional.empty();
        }
        LOGGER.info("install: {}", result.describe());

        final ZipFingerprint outputFingerprint = ZipFingerprint.of(temp);
        Files.move(temp, artifact, StandardCopyOption.REPLACE_EXISTING);
        InstallMarker.of(loaderVersion, side, packCrc, inputFingerprint, outputFingerprint).write(artifact);
        LOGGER.info("install: wrote {} in {} ms", artifact, (System.nanoTime() - started) / 1_000_000L);
        return Optional.of(artifact);
    }

    /** Checks the artifact still is what its marker says, to the depth {@code outlands.install.verify} asks for. */
    private static boolean verify(Path artifact, InstallMarker marker) {
        if (!Files.isRegularFile(artifact)) {
            return false;
        }
        final String mode = System.getProperty(VERIFY_PROPERTY, "dir");
        if ("off".equals(mode)) {
            return true;
        }
        if (!marker.output().matches(artifact)) {
            LOGGER.info("install: {} no longer matches its marker, reinstalling", artifact.getFileName());
            return false;
        }
        if ("deep".equals(mode)) {
            return deepVerify(artifact);
        }
        return true;
    }

    /** Reads every entry so the JDK verifies each CRC; slow by design, opt-in only. */
    private static boolean deepVerify(Path artifact) {
        try (ZipFile zip = new ZipFile(artifact.toFile())) {
            for (final Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                final ZipEntry entry = e.nextElement();
                try (InputStream in = zip.getInputStream(entry)) {
                    in.transferTo(OutputStream.nullOutputStream());
                }
            }
            return true;
        } catch (IOException e) {
            LOGGER.info("install: {} does not verify ({})", artifact.getFileName(), e.toString());
            return false;
        }
    }

    /** {@code outlands-<version>.jar} -> {@code <version>}; anything unexpected is kept verbatim. */
    static String loaderVersion(Path loaderJar) {
        final String name = loaderJar.getFileName().toString();
        if (name.startsWith("outlands-") && name.endsWith(".jar")) {
            return name.substring("outlands-".length(), name.length() - ".jar".length());
        }
        return name;
    }
}
