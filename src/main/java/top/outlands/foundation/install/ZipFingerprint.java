package top.outlands.foundation.install;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A cheap content fingerprint of a jar, taken from its zip central directory.
 *
 * <p>The aggregate is a CRC32 over the entries sorted by name, each contributing its name as UTF-8, its CRC32 as
 * four little endian bytes and its size as eight little endian bytes. A class that changed changes that entry's
 * CRC32 in the directory, so the aggregate changes with it - which means a fingerprint can be checked by reading a
 * directory instead of a jar's contents. Forge's installer caches do the same job with whole-jar sha1s.
 *
 * @param entries   number of entries in the jar
 * @param aggregate the aggregate described above
 * @param size      the file's length in bytes
 */
public record ZipFingerprint(int entries, long aggregate, long size) {

    /** Reads a jar's fingerprint, touching only its central directory. */
    public static ZipFingerprint of(Path jar) throws IOException {
        final long size = Files.size(jar);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            final List<ZipEntry> entries = new ArrayList<>();
            for (final Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                entries.add(e.nextElement());
            }
            entries.sort(Comparator.comparing(ZipEntry::getName));
            final CRC32 crc = new CRC32();
            final ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            for (final ZipEntry entry : entries) {
                crc.update(entry.getName().getBytes(StandardCharsets.UTF_8));
                buffer.clear();
                buffer.putInt((int) entry.getCrc());
                crc.update(buffer.array(), 0, 4);
                buffer.clear();
                buffer.putLong(entry.getSize());
                crc.update(buffer.array(), 0, 8);
            }
            return new ZipFingerprint(entries.size(), crc.getValue(), size);
        }
    }

    /** @return the CRC32 the jar's central directory records for {@code name}, or -1 when there is no such entry */
    public static long entryCrc(Path jar, String name) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            final ZipEntry entry = zip.getEntry(name);
            return entry == null ? -1L : entry.getCrc();
        }
    }

    /** True when the jar still matches this fingerprint (size and entry count are checked first, they are free). */
    public boolean matches(Path jar) {
        try {
            if (Files.size(jar) != size) {
                return false;
            }
            final ZipFingerprint now = of(jar);
            return now.entries == entries && now.aggregate == aggregate;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return entries + " entries, aggregate " + aggregate + ", " + size + " bytes";
    }
}
