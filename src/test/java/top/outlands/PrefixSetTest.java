package top.outlands;

import org.junit.jupiter.api.Test;
import top.outlands.foundation.boot.PrefixMatcher;
import top.outlands.foundation.boot.PrefixSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The view handed to old mods must be the matcher itself, not a copy that drifts away from it. */
class PrefixSetTest {

    @Test
    void readsReflectTheMatcher() {
        final PrefixMatcher matcher = new PrefixMatcher();
        final Set<String> view = new PrefixSet(matcher);

        matcher.add("org.spongepowered.");
        matcher.add("net.minecraft.");

        assertEquals(2, view.size());
        assertFalse(view.isEmpty());
        assertTrue(view.contains("org.spongepowered."));
        assertFalse(view.contains("org.spongepowered"), "contains is exact, not prefix based");
        assertEquals(List.of("net.minecraft.", "org.spongepowered."), new ArrayList<>(view));
    }

    @Test
    void writesReachTheMatcher() {
        final PrefixMatcher matcher = new PrefixMatcher();
        final Set<String> view = new PrefixSet(matcher);

        assertTrue(view.add("org.objectweb.asm."));
        assertTrue(matcher.matches("org.objectweb.asm.ClassReader"));

        assertTrue(view.remove("org.objectweb.asm."));
        assertFalse(matcher.matches("org.objectweb.asm.ClassReader"));
        assertFalse(view.remove("org.objectweb.asm."));
    }

    @Test
    void removeAllAndRetainAllWorkDespiteTheReadOnlyIterator() {
        final PrefixMatcher matcher = new PrefixMatcher();
        matcher.add("a.");
        matcher.add("b.");
        matcher.add("c.");
        final Set<String> view = new PrefixSet(matcher);

        assertTrue(view.removeAll(List.of("a.", "c.")));
        assertEquals(List.of("b."), new ArrayList<>(view));

        matcher.add("d.");
        assertFalse(view.retainAll(List.of("d.", "b.")), "nothing to drop");
        assertEquals(List.of("b.", "d."), new ArrayList<>(view));

        assertTrue(view.retainAll(List.of("d.")));
        assertEquals(List.of("d."), new ArrayList<>(view));

        view.clear();
        assertTrue(matcher.prefixes().isEmpty());
    }

    @Test
    void theViewIsEmptyWhenTheMatcherIs() {
        final PrefixMatcher matcher = new PrefixMatcher();
        final Set<String> view = new PrefixSet(matcher);

        assertTrue(view.isEmpty());
        assertFalse(view.contains("anything"));
        assertSame(matcher, ((PrefixSet) view).matcher());
    }
}
