package top.outlands.foundation.install;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the patch applier from the loader jar in a child JVM.
 *
 * <p>A child process rather than a call in this JVM, for the same reason Forge's installer is a separate process:
 * the applier needs the loader jar's classes loaded against a class loader that has nothing to do with the game's,
 * and a failure there must not leave this JVM half initialised. The child's classpath is only the loader jar and the
 * LZMA decoder, which is why the applier itself is written against nothing but the JDK.
 */
final class ApplierProcess {
    private static final String MAIN_CLASS = "top.outlands.install.PatchApplier";
    private static final String LZMA_CLASS = "LZMA/LzmaInputStream.class";
    private static final long TIMEOUT_SECONDS = 300L;

    private ApplierProcess() {
    }

    /**
     * Applies the pack inside {@code loaderJar} to {@code input}, writing {@code output}.
     *
     * @return the child's exit code, and its output for the log
     */
    static Result run(Path loaderJar, Path input, Path output, String side) {
        final Path lzma = Classpath.jarWithEntry(LZMA_CLASS).orElse(null);
        if (lzma == null) {
            return new Result(-1, List.of("no jar on the classpath provides " + LZMA_CLASS), null);
        }
        final String javaBinary = ProcessHandle.current().info().command().orElse("java");
        final List<String> command = new ArrayList<>();
        command.add(javaBinary);
        command.add("-cp");
        command.add(loaderJar + File.pathSeparator + lzma);
        command.add(MAIN_CLASS);
        command.add("--input");
        command.add(input.toString());
        command.add("--output");
        command.add(output.toString());
        command.add("--side");
        command.add(side);

        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        try {
            final Process process = builder.start();
            final List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, lines, "timed out after " + TIMEOUT_SECONDS + "s");
            }
            return new Result(process.exitValue(), lines, null);
        } catch (IOException e) {
            return new Result(-1, List.of(), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, List.of(), "interrupted");
        }
    }

    /** What one applier run produced. */
    record Result(int exitCode, List<String> output, String failure) {
        boolean ok() {
            return failure == null && exitCode == 0;
        }

        String describe() {
            if (failure != null) {
                return failure;
            }
            return output.isEmpty() ? "exit " + exitCode : String.join("; ", output);
        }
    }
}
