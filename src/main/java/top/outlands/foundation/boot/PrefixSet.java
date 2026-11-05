package top.outlands.foundation.boot;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * A live {@link java.util.Set} view of a {@link PrefixMatcher}.
 *
 * <p>Older mods reach the classloader exclusions through reflection on
 * {@code LaunchClassLoader.classLoaderExceptions} and {@code transformerExceptions} and cast whatever they
 * find to {@code Set<String>}. The authoritative store is a {@link PrefixMatcher} now, so those fields hold
 * this view instead of a detached {@code HashSet}: reading it, iterating it, mutating it and even
 * {@code toString()}ing it all see the prefixes that are actually in effect.
 *
 * <p>Membership is the same as a plain {@code Set}: {@link #contains(Object)} is an exact match, while
 * {@link PrefixMatcher#matches(String)} keeps its prefix semantics. The view is thread safe because every
 * operation delegates to the matcher.
 */
public final class PrefixSet extends AbstractSet<String> {

    private final PrefixMatcher matcher;

    public PrefixSet(PrefixMatcher matcher) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
    }

    /**
     * @return the matcher this view delegates to
     */
    public PrefixMatcher matcher() {
        return matcher;
    }

    @Override
    public int size() {
        return matcher.size();
    }

    @Override
    public boolean isEmpty() {
        return matcher.size() == 0;
    }

    /**
     * @param o the prefix to look for
     * @return whether the prefix is stored, an exact comparison (prefix matching is {@link PrefixMatcher#matches(String)})
     */
    @Override
    public boolean contains(Object o) {
        return o instanceof String prefix && matcher.prefixes().contains(prefix);
    }

    /**
     * @return a read only iterator over the prefixes, shortest first
     */
    @Override
    public Iterator<String> iterator() {
        return matcher.prefixes().iterator();
    }

    @Override
    public boolean add(String prefix) {
        return matcher.add(prefix);
    }

    @Override
    public boolean remove(Object o) {
        return o instanceof String prefix && matcher.remove(prefix);
    }

    @Override
    public void clear() {
        for (final String prefix : matcher.prefixes()) {
            matcher.remove(prefix);
        }
    }

    /** Overridden because the iterator is read only, which {@link AbstractSet#removeAll} would trip over. */
    @Override
    public boolean removeAll(Collection<?> prefixes) {
        boolean changed = false;
        for (final Object prefix : prefixes) {
            changed |= remove(prefix);
        }
        return changed;
    }

    /** Overridden because the iterator is read only, which {@link AbstractSet#retainAll} would trip over. */
    @Override
    public boolean retainAll(Collection<?> prefixes) {
        boolean changed = false;
        for (final String prefix : List.copyOf(matcher.prefixes())) {
            if (!prefixes.contains(prefix)) {
                changed |= matcher.remove(prefix);
            }
        }
        return changed;
    }
}
