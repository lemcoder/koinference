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
helpers. `native/CMakeLists.txt` forces it on and links whichever common target exists.

**The pin is b10472, and b10472 is the floor for LFM2** — `LLM_ARCH_LFM2` does not exist before
it, so an LFM2 GGUF loads as "unknown model architecture" on anything older. Bumping from b5001
cost four breakages, all silent-ish:

- **`common` was renamed `llama-common`.** Getting this wrong does not fail at configure time:
  CMake treats an unknown name as a raw `-lcommon`, the static archive records it happily, and
  the error only surfaces when something finally links an executable. `CMakeLists.txt` now
  detects the target rather than naming it.
- **`llama_kv_cache_clear` was removed**, not deprecated-and-kept. Its replacement is
  `llama_memory_clear(llama_get_memory(ctx), true)`.
- **`common_params_sampling::grammar` became a tagged struct**, so a bare string no longer
  assigns: `common_grammar(COMMON_GRAMMAR_TYPE_USER, gbnf)`.
- **The common headers include `<nlohmann/json_fwd.hpp>`**, which lives in `vendor/`, and
  `json-schema-to-grammar.h` now only forward-declares the type — parsing needs
  `<nlohmann/json.hpp>` included explicitly.

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
too, or it fails with undefined `koilm_*`. The `dependsOn(buildFacade)` goes on
`binaries.all { linkTaskProvider }` for the same reason — naming link tasks individually covers
whichever ones existed that day. `cinterop` also needs `native/facade` as an explicit input:
the task tracks the `.def`, not the header it names, so a new facade function surfaces as an
unresolved reference in Kotlin rather than as a stale interop.

**Android cannot use the facade, and this is structural.** The AAR's `liblitertlm_jni.so` is
version-scripted (`VERS_1.0`) down to 24 `Java_..._LiteRtLmJni_*` entry points plus six section
markers — `nm -D` finds **zero** `litert_lm_*` symbols. There is nothing for a C facade to link
against, and no amount of NDK work changes that. So the Android leg is Google's Kotlin API, and
the seam sits at engine/conversation *objects* rather than at the C API's shape. Don't "unify"
it by making Android hold Long handles into a registry; the two bindings are genuinely
different.

**The seam is interfaces, not `expect class` handles, and that is a testability decision.** An
`expect class` can only be produced by a platform, so with handles at the seam nothing in
`LiteRtLmRuntime` — conversation reuse, engine reload on a backend change, unload while a
generation is running — could be checked without a 136 MB model. `LiteRtLmBridge` /
`LiteRtLmEngine` / `LiteRtLmConversation` are ordinary interfaces, `platformBridge()` is the one
`expect fun`, and `FakeLiteRtLmBridge` in commonTest covers all of it. The nesting is
deliberate: an engine hands out conversations, so neither can be used without its owner.

**The two runtimes ship separately and must be bumped together.** `litertlm` in the version
catalog and `KOILM_VERSION` in `native/CMakeLists.txt` are the same release; a skew between them
is a behaviour skew between targets.

**Our AAR contains no `.so`.** The runtime is a transitive dependency merged by the consuming
app. Hence `api(libs.litertlm.android)` rather than `implementation` — with `implementation`, a
consumer compiles and then dies at the first generate.

**A commonMain file with any top-level declaration collides with a same-named androidMain file.**
`expect` declarations generate no JVM class, which is why `:backends:llamacpp` can reuse
`LlamaCppBridge.kt` across source sets. Adding one `const` to the common bridge produced
`Duplicate JVM class name … LiteRtLmBridgeKt`. Hence the platform files are named after their
binding — `SdkBridge.kt`, `FacadeBridge.kt` — and never after the common file they implement.

**The reply buffer is sized by the reply, which cannot be known in advance.** `koilm_generate`
follows snprintf: it returns what the reply *needs*, writing only if it fits. A too-small buffer
is not an error and does not lose the reply — the facade keeps it thread-locally so
`koilm_last_response` can collect it. Generating again would be wrong twice over: a second
`send_message` is another turn in the conversation's history and another full decode.

**Sampler defaults exist twice on purpose.** Android's `SamplerConfig` has no default for
top-k/top-p/temperature (only `seed`, which is 0 — deterministic, unlike the facade), so common
code has to hold concrete numbers. `SessionDefaultsTest` compares them against
`koilm_default_session_params()`, which is what keeps the two copies from drifting.

**Instrumented tests need block bodies.** JUnit4 rejects a non-void test method, so
`fun x() = runBlocking { … }` fails at runtime with `Method x() should be void` rather than at
compile time. `runBlocking`, not `runTest` — real inference outruns runTest's 60s default.

**The cinterop package is named after the `.def` file, not the interop.**
`koinference_litertlm.def` gives package `koinference_litertlm`, however the interop is named in
Gradle.

**Models are `.litertlm` or `.task`; raw `.tflite` is rejected** by LiteRT-LM itself. The
smallest published one is SmolLM2-135M-Instruct at 136 MB — there is no `stories260K` equivalent,
so generation tests are env-gated rather than run in CI.

## The benchmark harness

- **cinterop does not track the headers behind its compiler options.** Editing a facade leaves
  the task UP-TO-DATE and the klib describing the previous API — "Unresolved reference koi_*"
  for a function plainly in the header. Both backends now declare `native/facade` as an input on
  `CInteropProcess`.
- **A klib does not carry the producing project's linker options.** Any module that links a
  binary against a backend names its libraries again; `:benchmark:core` repeats the `-L`, `-l`,
  `-rpath` and `-framework` flags for both backends. Same lesson as the `.a`, one level out.
- **AGP 9 cannot apply `com.android.application` in this build at all.** It sees the Kotlin
  plugin on the classpath and tries to create a `KotlinAndroidTarget`, which reaches for the
  removed variant API (`NoClassDefFoundError: com/android/build/gradle/api/BaseVariant`). The
  only Kotlin-capable Android plugin in AGP 9 is the multiplatform library one. Hence
  `benchmark/stub-app` as a separate included build for the APK Firebase Test Lab requires.
- **The device-test variant does not package `assets/`.** The prompt corpus is pushed to the
  device and passed with `-e promptFile` instead of being packaged.
- **LiteRT-LM's `getBenchmarkInfo()` is a function, not a property.** `conversation.benchmarkInfo`
  fails with "Unresolved reference", which reads like the accessor being internal or missing —
  it is neither. Check the Kotlin metadata before concluding a member is inaccessible: the
  property name simply is not in it, only `getBenchmarkInfo`. It also needs
  `@OptIn(ExperimentalApi::class)`, and `BenchmarkInfo`'s own members *are* properties.
  Whether it returns real numbers or zeros depends on the runtime being built with benchmarking
  on, which nothing in the public `EngineConfig` controls — so the harness treats zero as
  unmeasured and falls back to timing the first streamed chunk.
- **On Apple there is nothing to ask.** `litert_lm_session_get_benchmark_info` takes a `Session`,
  the facade drives a `Conversation`, and no C function bridges the two.

## Cinterop idioms that bite

- `koi_default_session_params()` returns `CValue<T>`, an immutable snapshot — `.apply { field = … }`
  does not compile. Build with `cValue<T> { … }`.
- A function parameter shadows a struct field of the same name inside that builder, so `this.temp`
  is required where `temp` is also a parameter.
- Pointer indexing (`buf[i]`) needs `import kotlinx.cinterop.get`; without it the compiler falls
  through to `MatchGroupCollection.get` and reports a nonsensical `MatchGroup?` type error.
