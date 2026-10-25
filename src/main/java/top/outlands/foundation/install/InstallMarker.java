package top.outlands.foundation.install;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * The file next to the installed artifact that says what it was built from.
 *
 * <p>Everything in it is a zip central directory value, so neither writing nor checking it reads any jar contents
 * (see {@link ZipFingerprint}). Reuse requires the loader identity, the side, and the input jar's fingerprint to
 * match; the output fingerprint is what verifies the artifact itself is still the one that was produced.
 *
 * @param loaderVersion the loader the artifact was patched for; also part of its path
 * @param side          {@code client} or {@code server}
 * @param packCrc       CRC32 of the {@code binpatches.pack.lzma} entry inside the loader jar
 * @param input         fingerprint of the vanilla jar the patches were applied to
 * @param output        fingerprint of the artifact
 * @param written       epoch seconds, for humans reading the directory
 */
public record InstallMarker(int format, String loaderVersion, String side, long packCrc, ZipFingerprint input,
                            ZipFingerprint output, long written) {

    /** Bumped when the meaning of a field changes; a mismatch is simply a miss. */
    public static final int FORMAT = 1;
    private static final String SUFFIX = ".cache";

    static Path pathOf(Path artifact) {
        return artifact.resolveSibling(artifact.getFileName() + SUFFIX);
    }

    static InstallMarker of(String loaderVersion, String side, long packCrc, ZipFingerprint input,
                            ZipFingerprint output) {
        return new InstallMarker(FORMAT, loaderVersion, side, packCrc, input, output,
                System.currentTimeMillis() / 1000L);
    }

    /** Reads the marker next to {@code artifact}, or empty when it is missing or unreadable. */
    static Optional<InstallMarker> read(Path artifact) {
        final Path path = pathOf(artifact);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        final Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            return Optional.empty();
        }
        try {
            return Optional.of(new InstallMarker(
                    Integer.parseInt(require(properties, "format")),
                    require(properties, "loader.version"),
                    require(properties, "side"),
                    Long.parseLong(require(properties, "loader.pack.crc")),
                    new ZipFingerprint(
                            Integer.parseInt(require(properties, "input.entries")),
                            Long.parseLong(require(properties, "input.agg")),
                            Long.parseLong(require(properties, "input.size"))),
                    new ZipFingerprint(
                            Integer.parseInt(require(properties, "output.entries")),
                            Long.parseLong(require(properties, "output.agg")),
                            Long.parseLong(require(properties, "output.size"))),
                    Long.parseLong(properties.getProperty("written", "0"))));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    /** Writes the marker next to {@code artifact}: temporary file first, then a rename, never half a marker. */
    void write(Path artifact) throws IOException {
        final Properties properties = new Properties();
        properties.setProperty("format", Integer.toString(format));
        properties.setProperty("loader.version", loaderVersion);
        properties.setProperty("side", side);
        properties.setProperty("loader.pack.crc", Long.toString(packCrc));
        properties.setProperty("input.entries", Integer.toString(input.entries()));
        properties.setProperty("input.agg", Long.toString(input.aggregate()));
        properties.setProperty("input.size", Long.toString(input.size()));
        properties.setProperty("output.entries", Integer.toString(output.entries()));
        properties.setProperty("output.agg", Long.toString(output.aggregate()));
        properties.setProperty("output.size", Long.toString(output.size()));
        properties.setProperty("written", Long.toString(written));

        final Path path = pathOf(artifact);
        final Path temp = path.resolveSibling(path.getFileName() + ".part");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            properties.store(writer, "outlands install step: what " + artifact.getFileName() + " was built from");
        }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
    }

    /** True when this marker describes an artifact built from exactly these inputs. */
    boolean describes(String loaderVersion, String side, long packCrc, ZipFingerprint input) {
        return format == FORMAT
                && this.loaderVersion.equals(loaderVersion)
                && this.side.equals(side)
                && this.packCrc == packCrc
                && this.input.equals(input);
    }

    private static String require(Properties properties, String key) {
        final String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }
}
