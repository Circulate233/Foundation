package top.outlands.foundation.boot;

import java.net.URL;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LaunchClassLoader(URL[])} feeds this an immutable {@code List.of(...)}; appending to that threw, which
 * made the install step's launch path unusable while the other constructor (a mutable list) kept working.
 */
class ManifestCPParserTest {

    @Test
    void acceptsAnImmutableListAndAppendsToACopy() throws Exception {
        final URL first = new java.io.File("/tmp/does-not-exist-a.jar").toURI().toURL();
        final URL second = new java.io.File("/tmp/does-not-exist-b.jar").toURI().toURL();
        final List<URL> input = List.of(first, second);

        final List<URL> parsed = ManifestCPParser.parse(input);

        assertEquals(List.of(first, second), parsed, "jars without a manifest keep their order and add nothing");
        assertEquals(2, input.size(), "the caller's list must not be modified");
        final URL added = new java.io.File("/tmp/does-not-exist-c.jar").toURI().toURL();
        assertTrue(parsed.add(added), "the result has to be mutable, callers append manifest entries to it");
    }
}
