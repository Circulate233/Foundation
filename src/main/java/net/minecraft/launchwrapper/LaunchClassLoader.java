package net.minecraft.launchwrapper;

import top.outlands.foundation.boot.ActualClassLoader;
import top.outlands.foundation.boot.ManifestCPParser;
import top.outlands.foundation.boot.PrefixSet;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.jar.Manifest;

/**
 * The compatibility face of {@link ActualClassLoader}.
 *
 * <p>This class declares the fields older mods reach through reflection, and nothing else. Anything that
 * actually stores state lives in the superclass: declaring a field here would hide the superclass one and
 * hand callers a detached, permanently empty copy. That already happened to the four caches
 * ({@code cachedClasses}, {@code invalidClasses}, {@code resourceCache}, {@code negativeResourceCache}),
 * which were never written to because every lookup of them inside {@link ActualClassLoader} resolves to the
 * superclass field, and to the exclusion sets below, which mods read as empty.
 */
public class LaunchClassLoader extends ActualClassLoader {
    /**
     * Live views of {@link ActualClassLoader#classLoaderExceptions} and {@link ActualClassLoader#transformerExceptions}.
     *
     * <p>lwjgl3ify walks these through reflection when it re-runs transformers on a redefined class
     * ({@code me.eigenraven.lwjgl3ify.hotswap.TransformHelper}), so they must show the prefixes that are in
     * effect. Declared here rather than in the superclass because {@code Class#getDeclaredField} does not
     * look at superclasses.
     */
    private Set<String> classLoaderExceptions = new PrefixSet(ActualClassLoader.classLoaderExceptions);
    private Set<String> transformerExceptions = new PrefixSet(ActualClassLoader.transformerExceptions);

    /**
     * FoamFix (and many other mods) are still using these even some of them have long gone from upstream.
     * Kept declared here, and only here, so {@code getDeclaredField} on this class still answers.
     */
    private Map<Package, Manifest> packageManifests = null;
    private static Manifest EMPTY = new Manifest();

    public LaunchClassLoader(URL[] sources) {
        super(ManifestCPParser.parse(List.of(sources)).toArray(new URL[0]), LaunchClassLoader.class.getClassLoader());
        Launch.classLoader = this;
    }

    public LaunchClassLoader(ClassLoader loader) {
        super(getUCP(), loader);
        Launch.classLoader = this;
    }

    private static URL[] getUCP() {
        String[] classpaths = System.getProperty("java.class.path").split(File.pathSeparator);
        List<URL> urls = new ArrayList<>();
        try {
            for (String classpath : classpaths) {
                urls.add(new File(classpath).toURI().toURL());
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        return ManifestCPParser.parse(urls).toArray(new URL[0]);
    }

    /**
     * CCL is calling this
     */
    public byte[] runTransformers(final String name, final String transformedName, byte[] basicClass) {
        return super.runTransformers(name, transformedName, basicClass);
    }

}
