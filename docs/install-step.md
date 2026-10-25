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
    outlands-<loaderVersion>-mc-client-patched.jar   the artifact (an overlay, see below)
    outlands-<loaderVersion>-mc-client-patched.cache the marker
```

The artifact is an **overlay**: it holds only the classes the patches rewrite (1,170 of the client's 1,192 patch
records for 1.12.2, 2.8MB packed), not a copy of the whole game jar. The rest of Minecraft keeps coming from the
vanilla jar behind it, untouched. That is not just a size decision - it is what makes the artifact provably
equivalent to runtime patching, because a full jar would have to be built from our recompiled tree and would
therefore shadow classes whose bytes differ only by line tables and source file names.

The 22 remaining patch records are `existsAtTarget=false` (classes our patches remove); they have no vanilla
source and produce no entry.

`<libraries>` is the directory Prism hands us on the classpath (`~/.local/share/PrismLauncher/libraries` for a
shared instance, `<instance>/libraries` for a self-contained pack). Walking up from
`.../top/outlands/foundation/<v>/foundation-<v>.jar` finds it in both layouts; if it is not writable the step
is skipped and the loader patches at runtime exactly as it does today.

## Marker

A plain `key=value` file next to the jar, written *after* the jar is complete. Everything in it comes out of a
zip **central directory**, so neither writing nor checking it reads the jar contents:

```
format=1
side=client
loader.version=2026.10.25
loader.pack.crc=3060476666    CRC32 of the binpatches.pack.lzma entry inside the loader jar
input.entries=7957            the launcher's minecraft-1.12.2-client.jar
input.agg=2580656613
output.entries=1170
output.agg=1782657135
output.size=2839327
written=1761400000
```

An aggregate is a CRC32 over the entries sorted by name, fed `name utf-8`, `crc32` (4 bytes LE) and `size`
(8 bytes LE). Any changed class changes that entry's CRC32 in the directory, so the aggregate changes with it:
it is the artifact's fingerprint, and it costs a directory scan instead of a full read.

That is cheaper than what Forge ships and needs no extra pass: Forge's `.cache` files hold `Input:`/`Srg:`/
`Output:` sha1s of whole jars, whereas we produce the jar ourselves, so its per-entry CRCs are known the moment
the last entry is written. Measured on this machine, hashing the 7MB artifact and the 10MB vanilla jar is 4.8 and
5.7 ms of a 17MB read, against 2.5 and 3.0 ms and ~300KB for the two directory scans - small either way, but the
directory scan does not grow with the size of what it describes.

Reuse needs all four of `loader.version`, `loader.pack.crc`, `side` and `input.agg` to match and the artifact's
directory to still aggregate to `output.agg` with the recorded entry count and size. Anything else reinstalls.

The aggregates are written by Foundation after the applier returns, never by the applier itself: the aggregate
algorithm then has exactly one owner and cannot drift between the two repositories.

`outlands.install.verify=deep` additionally decompresses the artifact so the JDK validates every entry CRC. That
is the thorough check, and it is opt-in because it reads the whole jar.

## Flow

`Foundation.main`, after `breakModuleAndReflection()` and **before** the class loader is created:

```
1. InstallLayout.detect()          libraries root, or skip when it is not writable
2. PatchInstaller.prepare()        best effort, never throws, never blocks a launch
     inputs:  vanilla client jar (classpath entry .../minecraft-<ver>-client.jar)
              binpatches.pack.lzma (the classpath jar that carries it - the loader jar)
              side  (client or server, from which jar was found on the classpath)
     if marker matches (loader version, pack crc, side, input.agg) and the jar's directory
        still aggregates to output.agg with the recorded entry count and size
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

## Measured (1.12.2 client, this machine)

| | |
|---|---|
| patch records / entries written / skipped | 1,192 / 1,170 / 22 (`existsAtTarget=false`) |
| artifact | 2,839,327 bytes, 1,170 entries |
| input Adler32 mismatches against the launcher's client jar | 0 of 1,170 |
| first-launch install, including the child JVM | ~0.6 s |
| later launches | two central-directory scans, ~2-3 ms |
| classes read on later launches beyond what the game does anyway | none |

## Loader side (Cleanroom repo, same release train)

* `top.outlands.install.PatchApplier` - a `main` that reads the pack, applies the patches to the vanilla jar and
  writes the output jar. Standalone, no Guava: the child JVM classpath is just *loader jar : lzma jar*.
* `ClassPatchReader` - the patch entry format moves into one class, used by both `ClassPatchManager` and the
  applier, so the format has a single owner.
* `ClassPatchManager.setup(Side)` - when `outlands.patchedJar` names an existing file, log "pre-patched by
  foundation" and return without reading the pack. `patches` stays `null`, which makes `applyPatch` a
  pass-through - no other change in the patch path is needed.
* Checks that ship with it, in increasing cost:
  - every patch record's input Adler32 is verified against the vanilla class before it is patched; the applier
    fails on a mismatch instead of writing a garbage class (verified against the real 1.12.2 client jar: 1,170 of
    1,170 patchable records match),
  - every written entry is parsed with ASM before the marker is written,
  - a launch with and without `outlands.patchedJar` must load identical class bytes; `foundation.dump=true` already
    writes every loaded class to disk, so the two runs can be diffed without new tooling.

Order of work matters: the applier has to be **in a published loader jar** before Foundation can call it, and
the pack's foundation pin has to be bumped before users get it. Nothing breaks in between, because every
missing piece only skips the step (see below).

## Properties

| property | default | meaning |
|---|---|---|
| `outlands.install` | `on` | `off` skips the step entirely (runtime patching, today's behaviour) |
| `outlands.install.dir` | detected | override the cache root, e.g. for testing or a read-only libraries dir |
| `outlands.install.verify` | `dir` | `dir` scans the central directory (default), `deep` decompresses the artifact and lets the JDK check every entry CRC, `off` trusts the marker |
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
| marker present, jar missing | reinstall |
| marker absent, jar present (hand-edited instance) | reinstall |
| directory aggregate differs from `output.agg` | reinstall once, then skip if it drifts again |
| vanilla jar or pack crc changed (loader or game update) | reinstall under the new fingerprint |
| two instances launch at once | both write unique tmp files and `ATOMIC_MOVE`; the outputs are identical, last rename wins |

## Phase 2 (modern renaming parity, not in this step)

Modern versions run on an `-srg` jar with no runtime patching *and* no runtime renaming. Ours would need the
applier to emit the SRG variant (the loader jar already carries `deobf_data-1.12.2.tsrg` and ASM) and the loader
to treat covered classes as final (`unmap`/`map` identity, `DeobfuscationTransformer` skipped). Measured on this
machine that is worth ~250-350 ms of ASM round-trip per launch, and it is the prerequisite for AppCDS later.
Phase 1 must land first and change no class bytes, so it can be shipped and debugged on its own.
