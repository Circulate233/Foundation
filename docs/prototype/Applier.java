/*
 * Measurement prototype for docs/install-step.md - NOT the production applier.
 *
 * It proved the load-bearing assumption of the install step offline: all 1,170 patchable client records apply to
 * the jar PrismLauncher puts on the classpath (`libraries/com/mojang/minecraft/1.12.2/minecraft-1.12.2-client.jar`)
 * with zero Adler32 mismatches, and the resulting overlay is 2.8MB with 1,170 entries that all parse as classes.
 *
 *   javac -cp outlands.jar:lzma.jar:asm.jar -d . Applier.java
 *   java  -cp outlands.jar:lzma.jar:asm.jar:. Applier outlands.jar minecraft-1.12.2-client.jar /tmp/overlay.jar client
 *
 * The production version lives in the Cleanroom repository (top.outlands.install.PatchApplier) and must behave the
 * same, minus the ASM parse (which is the protoype's extra safety net) and plus deterministic zip metadata.
 */
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import LZMA.LzmaInputStream;
import net.minecraftforge.fml.repackage.com.nothome.delta.GDiffPatcher;

/** Prototype of the install-time applier: writes an overlay jar of patched classes only. */
public class Applier {
    record P(String name, String target, boolean exists, int checksum, byte[] patch) {}

    public static void main(String[] args) throws Exception {
        File loader = new File(args[0]), vanilla = new File(args[1]), out = new File(args[2]);
        String side = args.length > 3 ? args[3] : "client";

        byte[] raw;
        try (ZipFile zf = new ZipFile(loader)) {
            try (InputStream in = zf.getInputStream(zf.getEntry("binpatches.pack.lzma"))) {
                raw = new LzmaInputStream(in).readAllBytes();
            }
        }
        List<P> patches = new ArrayList<>();
        try (JarInputStream jis = new JarInputStream(new ByteArrayInputStream(raw))) {
            JarEntry e;
            while ((e = jis.getNextJarEntry()) != null) {
                if (!e.getName().startsWith("binpatch/" + side + "/")) continue;
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(jis.readAllBytes()));
                in.readByte();
                String name = in.readUTF();
                String target = in.readUTF();
                boolean exists = in.readBoolean();
                int checksum = exists ? in.readInt() : 0;
                byte[] patch = new byte[in.readInt()];
                in.readFully(patch);
                patches.add(new P(name, target, exists, checksum, patch));
            }
        }
        patches.sort(Comparator.comparing(P::name));

        GDiffPatcher patcher = new GDiffPatcher();
        int written = 0, skipped = 0, badChecksum = 0;
        long rawBytes = 0;
        try (ZipFile vz = new ZipFile(vanilla);
             JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            for (P p : patches) {
                ZipEntry ve = vz.getEntry(p.name() + ".class");
                if (ve == null) { skipped++; continue; }          // class no longer exists in vanilla
                byte[] src = vz.getInputStream(ve).readAllBytes();
                java.util.zip.Adler32 adler = new java.util.zip.Adler32();
                adler.update(src);
                if (p.exists() && (int) adler.getValue() != p.checksum()) badChecksum++;
                byte[] patched = patcher.patch(src, p.patch());
                try (InputStream probe = new ByteArrayInputStream(patched)) {
                    org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(probe);
                    if (cr.getClassName() == null) throw new IllegalStateException("no class name");
                }
                JarEntry je = new JarEntry(p.name() + ".class");
                je.setTime(0L);
                jos.putNextEntry(je);
                jos.write(patched);
                jos.closeEntry();
                rawBytes += patched.length;
                written++;
            }
        }
        long size = out.length();
        System.out.printf("patches %d -> written %d (skipped %d), adler mismatches %d%n", patches.size(), written, skipped, badChecksum);
        System.out.printf("overlay jar: %,d bytes packed, %,d bytes of class data%n", size, rawBytes);
        try (ZipFile zf = new ZipFile(out)) {
            List<ZipEntry> entries = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) entries.add(e.nextElement());
            entries.sort(Comparator.comparing(ZipEntry::getName));
            CRC32 crc = new CRC32();
            ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            for (ZipEntry e : entries) {
                crc.update(e.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                buf.clear(); buf.putInt((int) e.getCrc()); crc.update(buf.array(), 0, 4);
                buf.clear(); buf.putLong(e.getSize()); crc.update(buf.array(), 0, 8);
            }
            System.out.printf("entries %d, agg %,d%n", entries.size(), crc.getValue());
        }
    }
}
