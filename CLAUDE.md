# Working in this repo

Things that cost time to discover. The README covers layout and commands, `docs/backends.md`
covers the shape both backends share and what a third one has to fill in; this is the stuff that
is not visible from the code.

## Three rules, learned the hard way in one session

Rules 1 and 3 are enforced by Konsist in `core/src/jvmTest/.../architecture/`, and so is the
checkable half of rule 2 — prose alone did not stop this session breaking all three. The tests were
verified by breaking each rule on purpose and watching them fail, because an architecture test that
cannot fail is decoration. What Konsist cannot see is C, so the rest of rule 2 stays a judgement
call.

**Do not create source sets to share code.** Not `jvmSharedMain`, not an `appleMain` that exists
only so two legs can avoid duplicating a file. `JniBridge.kt` is byte-identical in `jvmMain` and
`androidMain` and stays that way; so does `CpuPlacement*.kt`. Duplication is the cheaper trade here,
and for the JNI bridges an intermediate source set cannot even work — the generated `…jni` functions
land in each target's own source set, so a shared parent would not see them.

This is about *purpose*, not about which directories exist. The per-target and per-family source
sets the KMP default hierarchy already provides — `macosMain`, `iosMain`, `linuxMain`, `androidMain`,
`jvmMain` — are there to be used when platforms genuinely differ, which is the next rule. Adding one
to deduplicate is what is banned.

**If it can be Kotlin, it is Kotlin.** C is for reaching the engine, not for logic. `NativeSeamTest`
enforces the half of this that is mechanical: `koi_*`, `koilm_*` and `kniBridge*` may be named only
by the binding files, plus the two tests that exist to compare the two sides of the boundary. Once a
native symbol appears in a runtime, logic and marshalling have begun to mix. The CPU
placement heuristic lived in the facade for a while and the only way to learn what it had decided
was to infer it from throughput; moved to `CpuPlacementPolicy` it gained eleven tests against
topologies nobody here owns. Where a platform API is needed, reach it from Kotlin —
`sysconf(_SC_NPROCESSORS_ONLN)` is in `platform.posix`, `/proc` and `/sys` are `File.readText()`.
Every rule that ends up in C is a rule that has to be kept in step with the Kotlin one, and they
drift: `detect_decode_threads` and the Kotlin policy disagreed for most of a session, which cost
macOS 20% silently.

**If it is not identical on every platform, it is `expect`/`actual` — per platform, not per family.**
Splitting four ways where three would compile is correct when the fourth is genuinely different, and
"mac and iOS are both Apple" is not a reason to share. CPU placement is five actuals: Android pins
its big cluster, macOS cannot pin at all and wants `cores - 2`, iOS cannot pin either but its 2/4
core split is nothing like an M4's 4/6 so the number is not known to transfer, Linux *can* pin and
was silently getting the Darwin answer while it sat in a shared `nativeMain`, and the JVM cannot know
at compile time which of the two it is running on. A shared implementation hid a real bug in one of
those five. When a leg is unmeasured, say so in that leg's own source rather than letting it inherit
a comment about hardware it has never run on.

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

Deleting one is the same hazard. `koi_embed` (index 9) has no Kotlin caller since the unimplemented
embedding runtime was removed, and it **stays in the header anyway**: taking it out would renumber
`koi_json_schema_to_grammar` and the whole streaming trio. An unused C function costs nothing.

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

**The pin is b10516, and b10472 is the floor for LFM2** — `LLM_ARCH_LFM2` does not exist before
b10472, so an LFM2 GGUF loads as "unknown model architecture" on anything older. Bumping from b5001
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

**b10472 → b10516 cost nothing and gained nothing.** No API moved, both legs compiled unchanged,
and on an M4 with LFM2.5-1.2B Q4_0 the two are indistinguishable: medians 138.8 against 138.5 tok/s
over three interleaved rounds each, TTFT 63.0 against 62.5 ms. Worth knowing before spending an
afternoon on the next bump hoping for free throughput — the wins in this repo came from thread
placement and build flags, not from upstream.

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

**Android binds the facade through JNI.** This paragraph used to say it could not, and that was
true of the Maven AAR: `liblitertlm_jni.so` is version-scripted (`VERS_1.0`) down to 24
`Java_..._LiteRtLmJni_*` entry points, and `nm -D` finds **zero** `litert_lm_*` symbols. Release
0.16.0 added `litert_lm_c_api-0.1.0.zip`, whose Android slices export 144 of them, so both legs
bind the same facade — cinterop on Apple, generated JNI bridges on Android — and `SdkBridge.kt`
is gone along with Google's Kotlin API and its Maven dependency.

Check a claim like the old one with `nm -D --defined-only` rather than by trusting a library
named after the product. Four things follow from the move:

- **`ANDROID_STL=c++_static` for the stub.** `liblitert-lm.so` statically links its own C++
  runtime — nine NEEDED entries, all system libraries — so a shared STL shares nothing and costs
  a `dlopen failed: library "libc++_shared.so" not found` unless it is packaged too.
- **The runtime ships beside the stub in `jniLibs/<abi>/`**, copied by the JNI target's
  POST_BUILD step, because the stub records it as a DT_NEEDED and Android resolves that from the
  same directory.
- **The reply reader and the backend ids live in commonMain now**, since both legs parse the
  same envelope; `BackendIdTest` compares the hand-written ids against the generated ones,
  because only the cinterop leg gets the enum.
- **A system prompt is a regression against the AAR.** The Kotlin API accepted one for
  LFM2.5-1.2B; `litert_lm_conversation_config_set_system_message` refuses it, and neither a typed
  content array nor the role-less Contents shape the Kotlin API sends changes that. Models whose
  template takes a system role, like SmolLM2, still work.

**The seam is interfaces, not `expect class` handles, and that is a testability decision.** An
`expect class` can only be produced by a platform, so with handles at the seam nothing in
`LiteRtLmRuntime` — conversation reuse, engine reload on a backend change, unload while a
generation is running — could be checked without a 136 MB model. `LiteRtLmBridge` /
`LiteRtLmEngine` / `LiteRtLmConversation` are ordinary interfaces, `platformBridge()` is the one
`expect fun`, and `FakeLiteRtLmBridge` in commonTest covers all of it. The nesting is
deliberate: an engine hands out conversations, so neither can be used without its owner.

`:backends:llamacpp` now has the identical shape — `LlamaCppBridge` / `LlamaCppModel` /
`LlamaCppSession` — and it did not for a long time, which is why its `commonTest` was one
assertion that a path ends in `.gguf`. **`docs/backends.md` is the reference for the seam**; read
it before adding a backend or changing either one's shape.

**Above that seam sits `Backend` / `BackendRegistry` / `ModelConfig` in `:core`.** A consumer
never names a loader class: it registers the backends it links and asks for one by id or by model
path. `id` is a `String` and the registry is consumer-assembled on purpose — `:core` cannot
enumerate its own dependents, and an enum would make adding an engine a `:core` edit plus every
exhaustive `when` over it.

**`Accelerator` is CPU-or-GPU; `Backend` is llama.cpp-or-LiteRT-LM.** The enum used to be called
`InferenceBackend`, so "backend" meant both things at once — including in `RuntimeSettings(backend
= …)`, which was about neither engine.

**`Backend.honours` is a claim, and nothing checks it at run time.** It says which
`GenerationParameters` knobs the engine actually applies, and the benchmark reads it instead of
hardcoding `seedApplied` per adapter. Get it wrong and a results file asserts a reproducibility
the run never had — so it is asserted in each backend's `commonTest`.

**`KOILM_VERSION` in `native/CMakeLists.txt` is the only place the runtime version is pinned.**
It used to also be `litertlm` in the version catalog, back when Android took its runtime from
Maven; that entry is gone, and so is the skew between targets it could produce.

**Our AAR ships `liblitert-lm.so` itself**, in `jniLibs/<abi>/` beside the JNI stub. This
paragraph used to say the opposite — that the AAR carried no `.so` and the runtime arrived
transitively from `api(libs.litertlm.android)` — which was true of the SDK leg and survived its
deletion by two releases. There is no Maven runtime dependency now.

**Types are grouped into sub-packages, and a package is not a pile.** `:core` is `backend` /
`runtime` / `prompt`; `:benchmark:core` is `config` / `result` / `engine` / `platform` / `prompts` /
`runner`; the app's OpenAI DTOs are `app.api`. `PackageLayoutTest` checks that a package matches its
directory and that no single directory holds more than twenty files — a loose cap, there to catch
the next dumping ground rather than to force a split at a number. It counts by package *and* source
set, since that pair is what a directory is: `llamacpp.internal` spans seven source sets and looks
like 39 files if you count it as one.

**One top-level class, interface, object or enum per file, named after it.** `OneTypePerFileTest`
enforces both halves. `BenchmarkResult.kt` used to hold thirteen types and `OpenAiApi.kt` twelve, so
finding `ThermalSample` meant knowing which grab-bag it lived in; twenty-three files were like that.
Nested declarations are untouched — `PromptPart.Text` belongs inside its sealed parent — and
top-level functions may sit alongside a type, which is what lets `CpuPlacement.kt` hold both the
type and the `expect fun` the naming rule below requires to be there.

**A file holding actuals is named `<Expect>.<platform>.kt`** — `CpuPlacement.kt` in commonMain is
answered by `CpuPlacement.android.kt`, `CpuPlacement.macos.kt` and so on. `ActualFileNamingTest`
enforces it, including that a commonMain file of that name exists.

This replaces an earlier convention of naming platform files after their *binding*
(`JniBridge.kt`, `FacadeBridge.kt`), which existed to dodge `Duplicate JVM class name …
LiteRtLmBridgeKt`: a commonMain file with real top-level declarations collides with a same-named
platform file, and `expect` declarations generate no JVM class, so a shared name worked right up
until the seam became interfaces. The dotted suffix sidesteps that on its own —
`CpuPlacement.android.kt` and `CpuPlacement.kt` produce different facade classes — so the
workaround is gone and the name can say which `expect` it answers instead.

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

## Cera

A Rust GGUF engine, reached through its **published UniFFI/JNA Kotlin bindings** rather than a
facade of ours — `com.hyeons-lab:cera-ffi-jvm` and `cera-ffi-android`, same generated API, different
natives. No CMake, no cinterop, no C in `:backends:cera` at all.

- **jvm and android only.** UniFFI's Kotlin bindings need a JVM and there is no Kotlin/Native one,
  so the other targets are not declared. That is why `:benchmark:core`'s backend list is now
  `expect fun benchmarkBackends()` — its macosArm64 leg cannot see Cera.
- **The chat template is not optional, and its absence looks like a broken decoder.** Cera exposes
  `applyChatTemplate` instead of applying it; handed raw text, LFM2.5 answers with token 540
  repeated to the budget (`????????????????????????`). `UniffiSession.templated` builds the turn.
- **Stream with the blocking `generateStreaming`, not `generateStreamingAsync`.** The async variant
  delivered zero batches here; the blocking one works, on an IO thread, with `trySendBlocking` so a
  fast model cannot overflow the channel and drop a chunk silently.
- **`EngineConfig`'s `contextSize` of 0 means the model's full declared context**, which is what
  `ModelConfig.contextTokens` of 0 already meant — the conventions agree, so nothing is translated.
- **The 0.4.0 artifact is not the `main` branch.** `EngineConfig` takes `contextSize`, not
  `maxSeqLen`, and `GenerateOutput` carries token ids and a summary with no text field. Read the
  published jar with `javap`, not the checked-out source, before coding against it.
- **`flushEveryTokens = 1`, against Cera's default of 16.** Otherwise a chunk is a burst and time
  to first chunk is time to the *sixteenth* token: 13 chunks for 64 tokens on an M4, first at 60ms.
  llama.cpp emits one token per chunk, so batching would make this engine's TTFT mean something
  different from the others' in the same file. Costs nothing measurable (714ms against 720ms). On a
  phone it changes nothing — decode is slower than the 50ms flush timer, so it already emitted per
  token, which is why the device numbers were honest before this was noticed.
- **A Cera session accumulates, so reset it per turn.** `appendText` adds to one conversation and a
  generation appends its own reply, so the same prompt asked four times re-prefills a growing
  history: 4.8s, 5.1s, 6.0s, 6.7s on an M4. `Session.reset()` exists and `CeraRuntime` calls it
  before every turn, which flattens that to 4.4-4.7s. On device the growth eventually stalled a run
  outright — the process sat at 0% CPU on `long_context_v1` after a 512-token workload. Multi-turn
  chat over one Cera session is therefore not offered rather than offered wrongly.
- **An unreleased Cera can be measured without a Rust toolchain.** Its CI publishes the per-ABI
  `.so` as a workflow artifact and the generated Kotlin binding is checked into the repo, so
  `-PceraLocal=<dir>` swaps both in — `<dir>/kotlin/uniffi/cera_ffi/cera_ffi.kt` and
  `<dir>/jniLibs/arm64-v8a/libcera_ffi.so`. They must come from the **same commit**: UniFFI
  checksums the API, so a mismatched pair fails at load rather than misbehaving. The build refuses
  to run if a sideloaded `.so` is staged without the flag, because packaging it beside the AAR's own
  would measure the wrong binary silently.
- **`main` was slower than 0.4.0 here, measured back to back** (2026-09-01, Pixel 8a,
  LFM2.5-1.2B Q4_0): 9.1 tok/s blocking on the published 0.4.0 against 5.4 on `main`, and `main`
  returned an *empty* reply for a 24-token budget where 0.4.0 answered. Both are `--release` with the
  same profile — CI and publish differ only by `ffi-buffer`, which is Dart-only — so it is not a
  build-flag artifact. Unreleased code, so this is a snapshot rather than a verdict; the point is
  that the perf work landed since v0.4.0 targets Q4_K/Q5_K, prefill and GPU, and **nothing on
  `main` mentions Q4_0**, which is what this repository benchmarks.
- **Two backends now read `.gguf`.** Registration order decides; `backendById("cera")` is how a
  caller means one specifically. See `docs/backends.md`.

## ExecuTorch

Consumed as a published AAR (`org.pytorch:executorch-android`), Android only — no JVM or
Kotlin/Native artifact exists. Four facts cost time:

- **The tokenizer is a second file.** `LlmModule(modelPath, tokenizerPath, temperature)`, so
  `TokenizerFile` looks beside the `.pte`. Missing, it crashes in native code — hence the Kotlin
  check that names what it looked for.
- **`seqLen` is prompt + reply, not a token budget.** Passing `maxNewTokens` into it produced
  `Max new tokens resolved: 0, given pos_ 53, num_prompt_tokens 22, max_context_len 128`. The budget
  is enforced by counting emissions and calling `stop()`; one emission is one token on this binding.
- **`resetContext()` before every turn.** The module carries `pos_` across generations, and the
  second call fails outright rather than slowing down.
- **No tokenizer is exposed, so there is no `TokenCounting`** and `tok/s` is empty for this engine.
  Chunks are what it reports. Counting with a different tokenizer would make that column mean two
  things.
- `LlmGenerationConfig.Builder`'s constructor is Kotlin-`internal` — public to `javap`, unusable
  from here. Check Kotlin metadata, not `javap`, before building against an AAR's API.

## whisper.cpp

Second C-facade backend, same construction as `:backends:llamacpp`. Facts worth keeping:

- **It needed no `:core` change.** `PromptPart.AudioFile` in, `ResponsePart.Text` out, through the
  ordinary `GeneratingRuntime`. That is the parts-based seam finally meeting an engine of a
  different modality rather than a fake.
- **`Modality` is `TEXT`** — named for the output, so audio-in/text-out is a text engine.
- **No session tier.** `whisper_full` carries nothing between calls. The other three all needed a
  reset; this one has nothing to reset.
- **The facade pushes segments into a queue from its own thread and Kotlin pulls**, like the
  LiteRT-LM facade. A blocking transcription drains the same loop.
- **WAV decoding is Kotlin** (`WavAudio`): 16-bit PCM at 16 kHz, stereo averaged, unknown chunks
  walked past, everything else refused by name. A silent resample would transcribe as noise.
- **Strings cross the JNI seam through out-buffers, never as `const char*`.** A returned pointer
  arrives as an opaque `Long`; the generator does emit a `kniCString` helper, but the out-buffer is
  what the rest of this repository does.
- **An opaque `typedef struct X X;` lands under `cnames.structs`** for cinterop, not in the interop's
  own package — "unresolved reference" for a type plainly in the header.
- **Binding files must be named `Jni*` or `Facade*`**, or `NativeSeamTest` refuses the native symbols
  in them. Splitting one file into bridge/model is also what `OneTypePerFileTest` wants.

## Performance on device

`docs/performance.md` has the measurements. Two things that cost time to find:

- **The thread default is not the core count, and not the big-core count either.** Decode is a
  bandwidth-bound GEMV, so 2 threads saturate a Pixel 8a and 4 — exactly the size of its big
  cluster — runs at *half* the speed of 2. `detect_decode_threads()` in the llama.cpp facade takes
  half the largest cluster above the slowest frequency tier. The old
  `hardware_concurrency() - 2` picked 7 and cost 2.6x.
- **`GGML_NATIVE=OFF` leaves ggml at the NDK baseline**, which compiles the dot-product kernels
  out of a Q4_0 build entirely. The arm64 preset sets `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod`. That
  raises the device floor to roughly 2018 hardware — SIGILL below it, no fallback. **Everything
  above dotprod is absent because it was measured, not assumed**: `+fp16`, `+i8mm` and
  `armv9-a+…+sve2` all land inside the noise on a Pixel 8a, and the same SVE2 binary swings 34.2 to
  38.2 tok/s on run order alone. `GGML_CPU_ARM_ARCH` is a compile-time *baseline*, so enabling all
  of them moves the floor to 2022 hardware for nothing. Runtime dispatch
  (`GGML_CPU_ALL_VARIANTS`, which has `android_*` tiers upstream) needs `GGML_BACKEND_DL` and so
  shared libs, plus `GGML_BACKEND_PATH` at run time — `ggml_backend_load_best` looks in
  `/proc/self/exe`'s directory, which on Android is `/system/bin/`, not the app's `lib/`.
- **The llama.cpp AAR declares `minSdk 31` while everything else declares 24**, and
  `androidMinSdkLlamaCpp` in the version catalog is the single place it lives. A consumer below it
  fails at manifest merge, which is the point. **The API level is not the real constraint** —
  A53/A55 arm64 parts ship on current Android — so `platformBridge()` in
  `LlamaCppBridge.android.kt` reads `asimddp` out of `/proc/cpuinfo` and throws
  `BackendUnsupportedException` before handing back a binding. **The check lives only in
  `androidMain`.** It was a `Backend` method first, then an `expect fun` with four null actuals,
  and both earned their removal: Android is the one leg shipping a binary that can meet hardware it
  was not built for, so four other legs answering "nothing to declare" is ceremony. The price is
  that only a device test can exercise it.
- **A CMake cache outlives the experiment that set it.** `KOI_KLEIDIAI` forced
  `GGML_CPU_KLEIDIAI` on and never off, so once any build enabled it every later build in that
  directory kept it — which invalidated the "KleidiAI is a wash" measurement in
  `docs/performance.md` and then failed two builds of an ISA sweep on undefined `kai_*` symbols,
  looking exactly like an i8mm problem. Fixed by forcing both ways. When an A/B moves a CMake
  variable, read `CMakeCache.txt` back.

- **GPU offload is Vulkan, opt-in at build time via `KOI_VULKAN`.** The run-time half was already
  there — `Accelerator.GPU` maps onto `n_gpu_layers` — but a build with no GPU backend ignores it
  and runs on the CPU. Enabling it moves the floor to API 28 (`vkGetPhysicalDeviceFeatures2` is
  Vulkan 1.1), which is why the default AAR keeps `KOI_VULKAN=OFF` and its API 24 floor. Worth
  ~15% on a Pixel 8a for ~500 MB more PSS.
- **A vendor native library needs `<uses-native-library>` from API 31**, even when it is already on
  the vendor public-libraries allowlist. Without it `dlopen` reports `library "libOpenCL.so" not
  found ... in namespace clns-<n>`, which reads like the file is missing rather than undeclared —
  it cost a wrong conclusion here that OpenCL was simply unreachable from an app. The declaration
  is in `:backends:llamacpp`'s `androidMain/AndroidManifest.xml` so it merges into consumers, and
  `KOI_OPENCL` builds the ICD loader as a *link target only*: its SONAME is `libOpenCL.so`, so the
  vendor driver resolves at run time, and packaging it would shadow that driver with a loader this
  device has no ICD for. Unmeasured — it landed after the test device went away.

**A device measurement is not trustworthy until the APK is checked.** `cmakeBuildKoinferenceAndroid`
does not declare the facade sources as inputs, so an edited `.cpp` can rebuild while AGP's
`mergeAndroidMainJniLibFolders` still packages its cached `.so`. Two experiments in a row measured
the old binary. `strings` the `.so` out of the APK for something only the new code contains before
believing any number, and delete `merged_jni_libs` / `merged_native_libs` / `stripped_native_libs`
/ `outputs/apk` to force an honest repackage. This is the same missing-input bug the cinterop
tasks had, in a different task.

**A pulled results file can be stale too.** `adb pull` of `benchmark-results.json` returns the
previous run's file when the run wrote none — `adb shell rm -f` it first. An A/B here reported four
identical rows because `timeout` is not installed on macOS, so `am instrument` never ran at all.

**Single device runs are noise.** The same configuration measured 8.5 and 2.3 tok/s minutes apart.
Interleave configurations across several rounds and check both orderings; `batteryTemperaturePeakC`
lags the SoC badly enough to read identical across a 2x swing.

## The benchmark harness

- **cinterop does not track the headers behind its compiler options.** Editing a facade leaves
  the task UP-TO-DATE and the klib describing the previous API — "Unresolved reference koi_*"
  for a function plainly in the header. Both backends now declare `native/facade` as an input on
  `CInteropProcess`.
- **A klib does not carry the producing project's linker options.** Any module that links a
  binary against a backend names its libraries again; `:benchmark:core` repeats the `-L`, `-l`,
  `-rpath` and `-framework` flags for both backends. Same lesson as the `.a`, one level out.
- **AGP 9 compiles Kotlin itself.** `org.jetbrains.kotlin.android` is rejected outright ("no
  longer required for Kotlin support since AGP 9.0"), and `com.android.application` alone
  handles Kotlin sources. The serialization *compiler* plugin still has to be applied by hand,
  or `@Serializable` classes compile with no generated serializer and every `.serializer()` is
  unresolved.
- **What AGP 9 cannot do is apply `com.android.application` in a build that has the Kotlin
  Multiplatform plugin on its classpath.** It tries to create a `KotlinAndroidTarget`, which
  reaches for the removed variant API (`NoClassDefFoundError:
  com/android/build/gradle/api/BaseVariant`). So `benchmark/app` is its own build and includes
  the main one — the reverse would be a cycle. Two toolchains in one daemon exhaust Metaspace,
  which surfaces as unrelated task-creation errors; its gradle.properties raises the limit.
- **The app has no Gradle wrapper of its own.** `benchmark/app` is a separate build included the
  other way round, and there is no `benchmark/app/gradlew` — `cd benchmark/app && ./gradlew …` fails
  with "no such file or directory", which a grep for compiler errors silently swallows. Two "the app
  builds" checks in one session were that failure. Drive it with `../../gradlew` from that directory.
- **Each engine is a service in its own process, reached over AIDL** (`:llamacpp`, `:litertlm`), and
  the benchmark runs *inside* that process — only the finished results file crosses the boundary, as
  JSON. Putting the runner in the app would put a binder round trip inside every timing and Compose
  inside every memory reading. `android:process` is fixed per manifest entry, which is why there is a
  service class per backend rather than one service told which engine to be.
- **The app is drivable from a shell**: `BenchmarkService` takes `--es engines`, `--es model` and
  the harness's own option names, runs the same `BenchmarkSession` the UI does, logs one `RESULT`
  line per record under tag `koinference-benchmark`, and writes the merged results file. Use it for
  anything repeatable; the screen is for one look at one device.
- **The device-test variant does not package `assets/`.** The prompt corpus is pushed to the
  device and passed with `-e promptFile` instead of being packaged.
- **Give LiteRT-LM a writable `cacheDir` or it will not run a 1.2B model.** Without one it puts
  XNNPACK's weight cache next to the model; when that is `/data/local/tmp` (shell's) SELinux
  denies the write, the delegate rebuilds all nine prefill signatures on every load, and lmkd
  kills the process at 3.4 GB RSS — `Kill … reason: min watermark is breached`. With
  `cacheDir` pointed at the app's own cache, LFM2.5-1.2B-Instruct int4 loads and generates on a
  Pixel 8a. A model may live on /data/local/tmp (readable); the cache may not.
- **The SDK's `sendMessageAsync` Flow overload throws on a current kotlinx-coroutines.**
  `NoSuchMethodError: SendChannel.close$default`, from inside `Conversation$sendMessageAsync$1$1
  .onDone` — the AAR was compiled against an older coroutines and calls a synthetic that 1.10.x
  no longer has. It compiles and installs and dies on device. The bridge uses the
  `MessageCallback` overload instead, which has no coroutine types in its signature and so
  cannot drift with the version.
- **`/sdcard/Android/data/<pkg>/` is wiped when the package is reinstalled**, which AGP does on
  every `connectedAndroidDeviceTest`. A model pushed there before a run is gone by the time the
  test looks for it.
- **LiteRT-LM's temperature 0 is not greedy.** Its sampler keeps sampling and answers the same
  question differently on consecutive calls, which quietly broke the benchmark's reproducibility
  claim. `ConversationOptions.greedy` maps temperature 0 onto top-k of 1 on both legs.
  `kLiteRtLmSamplerTypeGreedy` exists and looks like the right answer, but a conversation created
  with it fails *every* send_message in the v0.15.0 prebuilt, on both models tested.
- **Its sampler RNG is seeded per engine, not per conversation.** Two fresh engines with the same
  seed replay each other exactly; reopening a conversation does not rewind the stream, so a
  second generation continues rather than repeating. Any test of seeding has to compare engines.
- **The first generation on a freshly loaded engine differs from every later one.** Under argmax
  it answers "The color blue." once and "The colour **blue**." every time after, and it does so
  on a single thread too, so it is systematic rather than reduction-order noise. Reopened
  conversations do agree with each other, which is the only property worth relying on. For
  benchmarking this means warmup iterations are discarded for *correctness*, not just timing.
- **A system prompt is a model-dependent feature.** SmolLM2-135M-Instruct accepts one;
  LFM2.5-1.2B-Instruct refuses and the runtime reports only "send_message failed" either way.
  `LiteRtLmRuntime` adds the likely cause when a system prompt is set.
- **The runner takes its engines as a parameter**, defaulting to `availableEngines()`. It used to
  call that registry itself, which meant the protocol — warmup kept out of the samples, what a
  FAILED record carries, the contamination and buffered-chunk notes — could only be exercised on
  macosArm64 with both real models present. `BenchmarkRunnerTest` now covers it with a fake engine
  and a fake clock.
- **Argument parsing lives in `BenchmarkArguments`, not in the instrumentation.** Defaulting,
  splitting and the per-prompt token budgets decide what a run measures, and none of it is
  Android; inside the device test it was reachable only from an emulator. `modelIdOf` /
  `quantizationOf` moved out of `HostBenchmarkTest` for the same reason, and their two copies of
  the quantization-label list became one.
- **`applyWorkload` is on `BenchmarkInferenceEngine`.** It used to be a `when (this)` over the two
  private adapter classes, so a new backend silently ran with the wrong token budget instead of
  failing to compile.
- **Backends stream; the harness measures.** Neither backend reports timings any more. Both
  implement `GeneratingRuntime`, and `measureGeneration` in `:benchmark:core` is the only
  code that touches a clock — one definition of time to first token for every engine, instead
  of one per binding. Resist adding a metric to a backend: it would only be comparable with
  itself.
- **The llama.cpp facade streams as a pull loop** (`koi_generate_begin`/`_next`/`_end`), not a
  callback, because the JVM leg reaches it through generated JNI bridges that cannot hand a C
  callback back into the JVM. `koi_generate` is that loop drained, so there is one decode path.
- **LiteRT-LM's C streaming is push, from the runtime's own thread**, so the facade buffers into
  a queue and the Kotlin side pulls — same loop shape as llama.cpp, and no Kotlin running on a
  thread it does not own.
- **LiteRT-LM's stream chunks are JSON envelopes carrying deltas**, the same
  `{role, content}` shape as a blocking reply. Emitting them raw concatenates to about 14× the
  reply length, which looks like a plausible output size unless you check it; `extractResponseText`
  unwraps each one. `LiteRtLmDeviceTest` asserts streamed chunks concatenate to the blocking
  reply, because the Android SDK is a different binding and could have chosen cumulative chunks.
- **LiteRT-LM's `getBenchmarkInfo()` exists and is reachable** — it is a function, not a
  property, so `conversation.benchmarkInfo` fails with "Unresolved reference" and looks like an
  access problem. Unused now, but check the Kotlin metadata before concluding a member is
  inaccessible.

## A reply is a list of parts, and there is no shortcut to its text

`GeneratingRuntime` is the only generating interface, and both of its methods answer in
`ResponsePart` — `Text`, `Audio`, `Image`. The split it replaced was by output type
(`TextRuntime: String`, `ImageRuntime: GeneratedImage`), which cannot express one reply that
interleaves speech with its transcript; two return types have no ordering between them.
`FakeOmniBackend` in `:core`'s tests is that engine, and `MultiModalityTest` asserts the
interleaving survives both the blocking call and the stream.

**Do not add a `text()` or `streamText()` convenience to `:core`.** It was asked for and refused:
a caller narrowing a reply to text is discarding what else the model produced, and that discard
belongs in the calling code. The benchmark app's `ServedModel` filters explicitly because an SSE
`delta` has nowhere to put audio; the tests carry their own helper per module.

`ResponsePart.Audio` and `.Image` are not `data class`es — `ByteArray` equality is by reference, so
a generated `equals` would call two identical replies different.

`TokenCounting` is the only thing left in `runtime.text`, because a token is a text notion.

## A stream has to arrive in pieces, and that is asserted

An engine's flush policy decides what a chunk *is*, and it is not always one token. Cera batches 16
by default; the backend sets `flushEveryTokens = 1` so that a chunk is a token on every engine, and
`tok/s` is unaffected either way because the harness counts tokens with the model's own tokenizer
rather than counting emissions. What batching would corrupt is **TTFT**, which is why the setting
matters: measured two ways on a Pixel 8a, Cera does 10.8 tok/s through a blocking generate with no
chunks at all and 8.4 tok/s streamed, so the slowness is the decode and not the chunking.

A binding that buffered a whole reply and delivered it in one chunk would satisfy every other
property of streaming — the chunks concatenate, the text is right, the flow completes — while
making time to first token equal to total latency. Every streaming test therefore asserts more
than one chunk, and the harness records a note when a sample generates several tokens but
arrives as one chunk, rather than letting that number sit in a TTFT column next to engines that
really do stream.

Measured on a Pixel 8a: llama.cpp emits 24 chunks for 84 characters, LiteRT-LM 9 for 34.

## Token counts come from the harness, not the engines

Both facades expose the model's own tokenizer — `koilm_token_count` over
`litert_lm_engine_tokenize`, `koi_token_count` over `common_tokenize` — and `:benchmark:core`
calls it on the finished reply. Same rule as the timings: one code path, so a token means the
same thing in every row. An engine's own count would mean whatever that engine counts.

Both count *content*: no BOS, no chat template. A prompt tokenizes to more than this once a
backend wraps it in a turn, which is why `koi_generate_begin` reports the prompt's real length
separately.

Chunks stay in the schema beside tokens. Where the two agree — 32 and 32 on both engines for a
32-token budget — chunks were tokens; the day they disagree, that is worth seeing rather than
having been assumed away.

## Cinterop idioms that bite

- `koi_default_session_params()` returns `CValue<T>`, an immutable snapshot — `.apply { field = … }`
  does not compile. Build with `cValue<T> { … }`.
- A function parameter shadows a struct field of the same name inside that builder, so `this.temp`
  is required where `temp` is also a parameter.
- Pointer indexing (`buf[i]`) needs `import kotlinx.cinterop.get`; without it the compiler falls
  through to `MatchGroupCollection.get` and reports a nonsensical `MatchGroup?` type error.
