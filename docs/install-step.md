# The install step

Modelled on how Forge/NeoForge work in PrismLauncher today: the launcher never installs anything
(`PrismLauncher/launcher` has no installer code at all, `Launcher_META_URL` is a compile-time constant, so a
pack cannot point it anywhere else). What looks like an install is the component's `mainClass` doing it:
NeoForge ships `io.github.zekerzhayard:ForgeWrapper` as the entry point, which runs the installer's processors
on first launch and drops the results into the shared `libraries/`, next to a `.cache` marker that makes the
step idempotent.

Foundation is our entry point, so the install step belongs here. The goal is structural parity with those
versions - a pack that only describes itself, and a machine that materializes the rest - **not** startup
speed. Whatever speed falls out of it is a side effect (see *Phase 2*).

## Output layout

```
<libraries>/                                     walked up from our own jar, see InstallLayout
  top/outlands/outlands/<loaderVersion>/
    outlands-<loaderVersion>-mc-client-patched.jar   the artifact
    outlands-<loaderVersion>-mc-client-patched.cache the marker
```

`<libraries>` is the directory Prism hands us on the classpath (`~/.local/share/PrismLauncher/libraries` for a
shared instance, `<instance>/libraries` for a self-contained pack). Walking up from
`.../top/outlands/foundation/<v>/foundation-<v>.jar` finds it in both layouts; if it is not writable the step
is skipped and the loader patches at runtime exactly as it does today.

## Marker

A plain `key=value` file next to the jar, written *after* the jar is complete:

```
key=<sha1 of every input: pack, vanilla jar, side, loader jar>
output.sha1=<sha1 of the produced jar>
output.size=<bytes>
written=<epoch seconds>
```

`key` decides reuse. `output.sha1` catches a corrupt or half-replaced jar when `outlands.install.verify` is on;
by default only the size is compared, because the marker is written after an `ATOMIC_MOVE` and hashing 7MB on
every launch is not worth it.

## Flow

`Foundation.main`, after `breakModuleAndReflection()` and **before** the class loader is created:

```
1. InstallLayout.detect()          libraries root, or skip when it is not writable
2. PatchInstaller.prepare()        best effort, never throws, never blocks a launch
     inputs:  vanilla client jar (classpath entry .../minecraft-<ver>-client.jar)
              binpatches.pack.lzma (the classpath jar that carries it - the loader jar)
              side  (client or server, from which jar was found on the classpath)
     if marker.key == currentKey && jar exists && (verify ? sha1 ok : size ok)
         -> reuse
     else
         -> run the applier in a child JVM, write tmp, ATOMIC_MOVE, write marker
     -> Optional<Path>, empty on any failure
3. classpath = [patched jar (when present)] + System.getProperty("java.class.path")
4. Launch.classLoader = new LaunchClassLoader(classpath)   /  LoadsAllClassLoader(classpath)
5. System.setProperty("outlands.patchedJar", path)          read by the loader, see below
```

The patched jar goes **first**: `LaunchClassLoader(URL[])` already feeds `ClassPathIndex` in order and the
first hit wins, so no `addURL`/ordering change is needed. Classes the patches do not touch keep coming from the
vanilla jar behind it, unchanged.

## Loader side (Cleanroom repo, same release train)

* `top.outlands.install.PatchApplier` - a `main` that reads the pack, applies the patches to the vanilla jar and
  writes the output jar. Standalone, no Guava: the child JVM classpath is just *loader jar : lzma jar*.
* `ClassPatchReader` - the patch entry format moves into one class, used by both `ClassPatchManager` and the
  applier, so the format has a single owner.
* `ClassPatchManager.setup(Side)` - when `outlands.patchedJar` names an existing file, log "pre-patched by
  foundation" and return without reading the pack. `patches` stays `null`, which makes `applyPatch` a
  pass-through - no other change in the patch path is needed.
* Build check: the applier's output for the client side must be byte-identical to the jar `reobfObf` already
  produces (`reobf-input/outlands-<v>-mc-obf.jar`). That is the regression test, and it costs nothing because
  the build produces both sides already.

Order of work matters: the applier has to be **in a published loader jar** before Foundation can call it, and
the pack's foundation pin has to be bumped before users get it. Nothing breaks in between, because every
missing piece only skips the step (see below).

## Properties

| property | default | meaning |
|---|---|---|
| `outlands.install` | `on` | `off` skips the step entirely (runtime patching, today's behaviour) |
| `outlands.install.dir` | detected | override the cache root, e.g. for testing or a read-only libraries dir |
| `outlands.install.verify` | `false` | verify `output.sha1` on every launch instead of trusting size |
| `outlands.patchedJar` | unset | set by Foundation, read by the loader; only a hint, the loader re-checks the file |

## Failure policy

Every row ends in "launch proceeds", with runtime patching as the fallback. The install step is never allowed
to be the reason a game does not start.

| situation | behaviour |
|---|---|
| libraries root not found or not writable | skip, log at info |
| vanilla jar not on the classpath (dev run, ForgeDev) | skip, log at debug |
| loader jar without `PatchApplier` (old loader) | applier fails, skip, log at warn |
| child JVM exits non-zero / times out | delete tmp, skip, log at warn |
| marker present, jar missing or wrong size | reinstall |
| marker absent, jar present (hand-edited instance) | reinstall |
| verify on and sha1 differs | reinstall once, then skip |
| two instances launch at once | both write unique tmp files and `ATOMIC_MOVE`; the outputs are identical, last rename wins |

## Phase 2 (modern renaming parity, not in this step)

Modern versions run on an `-srg` jar with no runtime patching *and* no runtime renaming. Ours would need the
applier to emit the SRG variant (the loader jar already carries `deobf_data-1.12.2.tsrg` and ASM) and the loader
to treat covered classes as final (`unmap`/`map` identity, `DeobfuscationTransformer` skipped). Measured on this
machine that is worth ~250-350 ms of ASM round-trip per launch, and it is the prerequisite for AppCDS later.
Phase 1 must land first and change no class bytes, so it can be shipped and debugged on its own.
