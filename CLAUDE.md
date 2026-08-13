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

## The Kotlin/Native test executable is linked by this project

`main.compileTaskProvider`'s `-linker-options` travel in the klib for whoever links it. They do
**not** apply to binaries this project links, so `binaries.all { linkerOpts(…) }` is needed as well
— the same fact `:backends:litertlm` records, discovered again in `:backends:llamacpp` the first
time a test called a `koi_*` function.

Two consequences that only appear once something actually links the archive:

- **Apple targets need the frameworks named.** ggml's Metal and BLAS backends are inside the `.a`
  and the link fails on `_MTLCreateSystemDefaultDevice` and `_cblas_sgemm$NEWLAPACK$ILP64` without
  `-framework Metal -framework MetalKit -framework Foundation -framework Accelerate`. The `.a`
  carries no record of them; this is the same lesson as the JNI seam, one layer up.
- **Every target's test binary now references `koi_*`,** because `commonMain` calls the bridge —
  it is not limited to the source sets with native tests. So `allTests` needs a
  `libkoinference-facade.a` per target under `build/prebuilt/`; locally, run the per-target task.

## The facade header's declaration order is an ABI

The generated JNI bridges are numbered by position (`kniBridge0`, `kniBridge1`, …; the index skips
the struct-returning `koi_default_session_params`). **Append new functions at the end** — inserting
one renumbers every bridge after it and each hand-written actual in `jvmMain`/`androidMain` silently
calls the wrong C function.

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
- `:backends:litertlm` is **its own job**, not a matrix row: it consumes none of the prebuilt
  llama.cpp archives and drives its own CMake, which downloads Google's xcframework (cached on the
  hash of `native/CMakeLists.txt`, so a version bump re-downloads).
- Steps run under `bash -e`, so `grep -q … && { exit 1; }` fails the step when the grep matches
  *nothing* — the good case. Use `if grep -q …; then` for a check that something is absent.

## Publishing

Configured once in the root build file, not per module. Two things bite:

- The `subprojects` block is keyed on `plugins.withId("org.jetbrains.kotlin.multiplatform")`.
  `include(":backends:llamacpp")` creates an intermediate `:backends` project with no build file,
  which would otherwise publish an empty artifact called `backends`.
- `publishToMavenCentral()` takes no argument since plugin 0.34 — `SonatypeHost` is gone and the
  Central Portal is the only target. Older snippets do not compile.

## Instrumented tests earn their keep

`connectedAndroidDeviceTest` loads the packaged `.so` on an emulator and generates from
`stories260K.gguf` (1.2 MB, pushed to `/data/local/tmp/koinference/`). It found the `libomp` bug on
its first successful run, after every structural check had passed. Keep it.

## Upstream llama.cpp

`LLAMA_BUILD_COMMON` defaults to `LLAMA_STANDALONE` — OFF under CPM — and the facade uses `common_*`
helpers. `native/CMakeLists.txt` forces it on and links `common`; a stale reference to a
non-existent `llama-common` target once left every one of those symbols out of the archive.

`common` is also what makes `GenerationConstraint.JsonSchema` work: `json_schema_to_grammar` lives
there, so the facade converts and the Kotlin side never sees GBNF. Both the parse and the
conversion throw, and an exception crossing `extern "C"` is undefined behaviour — hence the
`try`/`catch` returning -1.

**GPU offload is a model-load parameter** (`llama_model_params.n_gpu_layers`), not a session one, so
`RuntimeSettings(backend = GPU)` has to reload the model rather than rebuild the session. A build
with no GPU backend compiled in ignores the value instead of failing.

**Test-model env vars are per backend** — `KOI_TEST_GGUF` and `KOI_TEST_LITERTLM`. One shared
variable fails loudly the moment both backends' tests run in the same invocation: each loader
rejects the other's container.

## LiteRT-LM

**"LiteRT" is two products.** LiteRT core (`litert_cc_sdk.zip`, `libLiteRt.so`) is tensor
in/tensor out — `litert_compiled_model.h`, no tokenizer, no sampler, no chat. It cannot
implement `generateResponse`. LiteRT-LM is the LLM layer, and it is the one this backend uses.
The clean CMake package on developers.google.com is the core one; don't reach for it expecting
an engine.

**It cannot be built from source, and this is not a toolchain problem.** Configure and generate
do pass on macOS once two upstream bugs are patched (`add_subdirectory(constrained_decoding)`
points at a path that is really `logits_processor/constrained_decoding`, and
`runtime/components/preprocessor/` ships no `CMakeLists.txt`). What does not pass is
compilation: `gemma3_data_processor.h` includes `"support/preprocessor/audio_preprocessor.h"
// from @litert`, and `litert/support/` does not exist in the public LiteRT repo at the commit
LiteRT-LM pins. Those are plain members on the mainline Conversation path, not behind an ifdef.
Hours went into proving this; don't re-run the experiment.

**Consequently the dependency is a binary.** `native/CMakeLists.txt` downloads the
`CLiteRTLM*.xcframework` release archive with a pinned SHA256. The `engine.h` inside it is
byte-identical to `c/engine.h` at the same tag, which is what makes it safe to compile against.
Note that at v0.15.0 the repo has no `c/conversation.h` — the conversation declarations live in
`engine.h`, and the separate header on `main` is a later split. Bind against the tag's header,
not `main`'s.

**Structured output is the reverse of what it looks like.** The source build substitutes
`cmake/patches/stubs/gemma_model_constraint_provider.cc`, forty lines that print
`STUBBED/DISABLED` and return nullptr. The prebuilt statically links the real provider — `nm`
shows `_LiteRtLmGemmaModelConstraintProvider_Create` with its full vtable. Separately, the
JSON-schema path runs through llguidance, which is open and also linked in. So the prebuilt has
*more* constrained-decoding capability than a source build would, not less.

**A `.a` carries no record of the dylib it needs** — the same lesson as the llama.cpp seam, in a
new place. The facade archive references `libCLiteRTLM_mac.dylib`, so CMake stages the dylib
next to the archive and Gradle passes `-lCLiteRTLM_mac` and an `-rpath` alongside
`-lkoinference-litertlm-facade`. Setting those on `main.compileTaskProvider` alone is not
enough: the test executable is linked by this project and needs `binaries.all { linkerOpts(…) }`
too, or it fails with undefined `koilm_*`.

**Android cannot use the facade, and this is structural.** The AAR's `liblitertlm_jni.so` is
version-scripted (`VERS_1.0`) down to 24 `Java_..._LiteRtLmJni_*` entry points plus six section
markers — `nm -D` finds **zero** `litert_lm_*` symbols. There is nothing for a C facade to link
against, and no amount of NDK work changes that. So the Android leg is Google's Kotlin API, and
the `internal expect` seam sits at engine/conversation handles rather than at the C API's shape.
Don't "unify" it by making Android hold Long handles into a registry; the two bindings are
genuinely different and the handle types say so.

**The two runtimes ship separately and must be bumped together.** `litertlm` in the version
catalog and `KOILM_VERSION` in `native/CMakeLists.txt` are the same release; a skew between them
is a behaviour skew between targets.

**Our AAR contains no `.so`.** The runtime is a transitive dependency merged by the consuming
app. Hence `api(libs.litertlm.android)` rather than `implementation` — with `implementation`, a
consumer compiles and then dies at the first generate.

**A commonMain file with any top-level declaration collides with a same-named androidMain file.**
`expect` declarations generate no JVM class, which is why `:backends:llamacpp` can reuse
`LlamaCppBridge.kt` across source sets. Adding one `const` to the common bridge produced
`Duplicate JVM class name … LiteRtLmBridgeKt`; the constants live in `Backends.kt` for that
reason alone.

**Instrumented tests need block bodies.** JUnit4 rejects a non-void test method, so
`fun x() = runBlocking { … }` fails at runtime with `Method x() should be void` rather than at
compile time. `runBlocking`, not `runTest` — real inference outruns runTest's 60s default.

**The cinterop package is named after the `.def` file, not the interop.**
`koinference_litertlm.def` gives package `koinference_litertlm`, however the interop is named in
Gradle.

**Models are `.litertlm` or `.task`; raw `.tflite` is rejected** by LiteRT-LM itself. The
smallest published one is SmolLM2-135M-Instruct at 136 MB — there is no `stories260K` equivalent,
so generation tests are env-gated rather than run in CI.

## Cinterop idioms that bite

- `koi_default_session_params()` returns `CValue<T>`, an immutable snapshot — `.apply { field = … }`
  does not compile. Build with `cValue<T> { … }`.
- A function parameter shadows a struct field of the same name inside that builder, so `this.temp`
  is required where `temp` is also a parameter.
- Pointer indexing (`buf[i]`) needs `import kotlinx.cinterop.get`; without it the compiler falls
  through to `MatchGroupCollection.get` and reports a nonsensical `MatchGroup?` type error.
