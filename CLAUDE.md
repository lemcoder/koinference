# Working in this repo

Things that cost time to discover. The README covers layout and commands; this is the stuff that is
not visible from the code.

## The native seam

CMake owns the native build. The Konan plugin only *generates* the JNI bindings and the `.c` stub —
`konanConfig` is deliberately empty in `backends/llamacpp/build.gradle.kts`.

This is not a stylistic choice. The plugin can link a stub itself, and doing so here means konan's
clang linking a CMake/NDK-built archive. Every native bug in this repo's history came from that
seam: missing host compiler-rt, `-framework` rejected by the Android linker, konan's bundled NDK-r20
libc++ against an NDK-28 archive, lld unable to read zstd debug sections. A static `.a` carries no
record of what it needs; CMake's target graph does. Keep the link in CMake.

The corollary: **never hardcode a path the plugin owns.** Ask the task.

```kotlin
generateJni.get().stubSourceDirectory   // where the .c is
generateJni.get().stubLibraryBaseName   // what System.loadLibrary will ask for
interopLibraryDir                       // where the built library landed
```

Three separate breakages came from guessing these instead (`.a` location, then `c/`, then the
library name). `native/CMakeLists.txt` reads `KONAN_JNI_STUB_DIR` and `KONAN_JNI_LIB_NAME` with no
fallbacks, on purpose.

## Toolchain facts

- **`prebuilt/` is keyed by `konanTarget.name`** (`macos_arm64`), not the Kotlin target name
  (`macosArm64`). Using the wrong one configures fine and fails at link with undefined `koi_*`.
- **Kotlin/Native must exist before generation.** The generator runs cinterop out of a distribution
  that KGP only downloads when it first compiles a native target. `generateJvmInterop*` depends on
  `downloadKotlinNativeDistribution` for that reason — `jvmTest` alone would otherwise fail on a
  clean machine.
- **`mavenLocal()` is first in `pluginManagement`**, so local plugin builds shadow the Gradle Plugin
  Portal. Everything can pass locally against a plugin version that means something else in CI. When
  changing the plugin, bump the version and publish; do not reuse an alpha.
- The Gradle daemon has no login PATH (`-PkoiCmake`) and may run a JBR with `include/` stripped
  (`-PkoiJniHome`). Both are auto-detected, both overridable.

## Android

- **`GGML_OPENMP=OFF` is load-bearing.** ggml links OpenMP by default and Android ships no
  `libomp.so`; without it the AAR passes every structural check and dies on `dlopen` on device.
- Libraries are stripped at link (`-Wl,--strip-debug`): the NDK compiles with `-g` regardless of
  build type and ELF carries DWARF inside the `.so` — 48 MB versus 6 MB per ABI.
- Android is the **ART/JNI leg**, not `androidNative*`. Those Kotlin/Native targets were removed:
  an app consumes an AAR, and konan could not link the NDK-built archive anyway.

## CI

- `build-artifacts.yml` is **reusable-only**. It used to also trigger on `pull_request` while being
  called by `gradle.yml`, which built every native artifact twice.
- The JVM job consumes a prebuilt stub via `-PkoiStubDir=<module-relative path>`. Relative because
  `${{ github.workspace }}` is empty inside a matrix definition — it silently produced `/backends/…`
  and an `UnsatisfiedLinkError`.
- `fail-fast: false` on the matrix. With it on, one failure cancels the others and one bug looks
  like three.
- The Android job frees disk before the emulator (`dotnet`, `ghc`, CodeQL). Two ABIs of llama.cpp
  plus NDK, SDK and a system image exhaust the runner, and the emulator's refusal is a quiet
  `adb: device not found` loop until timeout.

## Instrumented tests earn their keep

`connectedAndroidDeviceTest` loads the packaged `.so` on an emulator and generates from
`stories260K.gguf` (1.2 MB, pushed to `/data/local/tmp/koinference/`). It found the `libomp` bug on
its first successful run, after every structural check had passed. Keep it.

## Upstream llama.cpp

`LLAMA_BUILD_COMMON` defaults to `LLAMA_STANDALONE` — OFF under CPM — and the facade uses `common_*`
helpers. `native/CMakeLists.txt` forces it on and links `common`; a stale reference to a
non-existent `llama-common` target once left every one of those symbols out of the archive.

## Cinterop idioms that bite

- `koi_default_session_params()` returns `CValue<T>`, an immutable snapshot — `.apply { field = … }`
  does not compile. Build with `cValue<T> { … }`.
- A function parameter shadows a struct field of the same name inside that builder, so `this.temp`
  is required where `temp` is also a parameter.
- Pointer indexing (`buf[i]`) needs `import kotlinx.cinterop.get`; without it the compiler falls
  through to `MatchGroupCollection.get` and reports a nonsensical `MatchGroup?` type error.
