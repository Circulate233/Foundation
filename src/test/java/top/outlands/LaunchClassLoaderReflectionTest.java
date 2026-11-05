package top.outlands;

import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.Test;
import top.outlands.foundation.boot.ActualClassLoader;
import top.outlands.foundation.boot.PrefixSet;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Older mods reach the launchwrapper state through {@code getDeclaredField} on the concrete class, so the
 * fields that stay must show live data and the ones that were removed must not silently answer with an
 * empty husk.
 */
class LaunchClassLoaderReflectionTest {

    @Test
    void theExclusionSetsAreLiveViewsOfTheStaticMatchers() throws Exception {
        try (LaunchClassLoader loader = new LaunchClassLoader(new URL[0])) {
            final Set<String> exclusions = readSet(loader, "classLoaderExceptions");
            final Set<String> transformerExclusions = readSet(loader, "transformerExceptions");

            assertInstanceOf(PrefixSet.class, exclusions);
            assertSame(ActualClassLoader.classLoaderExceptions, ((PrefixSet) exclusions).matcher());
            assertSame(ActualClassLoader.transformerExceptions, ((PrefixSet) transformerExclusions).matcher());

            ActualClassLoader.classLoaderExceptions.add("top.outlands.reflectiontest.");
            try {
                // this is what lwjgl3ify's TransformHelper iterates when it re-runs transformers
                assertTrue(exclusions.contains("top.outlands.reflectiontest."));
                assertEquals(ActualClassLoader.classLoaderExceptions.size(), exclusions.size());
            } finally {
                ActualClassLoader.classLoaderExceptions.remove("top.outlands.reflectiontest.");
            }
            assertFalse(exclusions.contains("top.outlands.reflectiontest."));
        }
    }

    @Test
    void theRemovedShadowCachesAreGone() {
        for (final String name : new String[]{"cachedClasses", "invalidClasses", "resourceCache", "negativeResourceCache"}) {
            assertThrows(NoSuchFieldException.class, () -> LaunchClassLoader.class.getDeclaredField(name),
                name + " must not shadow the superclass field");
        }
    }

    @Test
    void theLegacyAnchorsStayOnTheLaunchWrapperClassOnly() throws Exception {
        // FoamFix and friends look these up, and getDeclaredField does not see the superclass
        assertEquals(Map.class, LaunchClassLoader.class.getDeclaredField("packageManifests").getType());
        assertEquals(Manifest.class, LaunchClassLoader.class.getDeclaredField("EMPTY").getType());
        assertThrows(NoSuchFieldException.class, () -> ActualClassLoader.class.getDeclaredField("packageManifests"),
            "the anchor lives on the launchwrapper class only");
        assertThrows(NoSuchFieldException.class, () -> ActualClassLoader.class.getDeclaredField("EMPTY"),
            "the anchor lives on the launchwrapper class only");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> readSet(Object loader, String name) throws Exception {
        final Field field = LaunchClassLoader.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Set<String>) field.get(loader);
    }
}
