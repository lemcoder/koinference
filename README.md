# koinference

Kotlin Multiplatform wrapper interfaces for inference runtimes.

## Modules

- `:core` — high-level common interfaces:
  - `Backend` / `BackendRegistry` / `ModelConfig` — pick an engine and configure it without naming
    its classes
  - `ModelLoader` (`load` / `unload` / `unloadAll`)
  - `TextRuntime` / `StreamingTextRuntime` / `TokenCounting` (generation, streaming, tokenizing)
  - `RuntimeGuard`, the locking and use-after-unload scaffolding every backend shares
- `:backends:llamacpp` — `llama.cpp` backend, driving a C facade from every target.
- `:backends:litertlm` — LiteRT-LM backend over Google's prebuilt runtime. macOS arm64 and
  Android.

`Koinference` is the entry point. Register the backends the application links, then load a model by
path — which engine reads a container is the backend's own answer, so switching engines is changing
the model file:

```kotlin
val koi = Koinference(LlamaCpp, LiteRtLm, config = ModelConfig(maxOutputTokens = 128))

val runtime = koi.loadText("/models/model.gguf")
val reply = runtime.generateResponse("What is the capital of France?")

runtime.streamResponse("Once upon a time").collect(::print)
```

A class rather than an object with `init`: `load` before `init` would fail at run time instead of
compile time, two consumers in one process would fight over one registry, and tests would have to
reset it. An application that wants one instance everywhere can hold this in its own object.
`CallerExampleTest` compiles and runs the snippet above, so it cannot drift.

Adding a third backend is documented in [docs/backends.md](docs/backends.md); all of them have the
same shape on purpose, and adding one touches no file in `:core`.

Published to Maven Central as `io.github.lemcoder:koinference-core`, `…:koinference-llamacpp` and
`…:koinference-litertlm`. Each backend depends on `:core` with `api`, so adding a backend is
enough.

## Layout

```
core/                          common interfaces
backends/llamacpp/
├── src/
│   ├── commonMain/            LlamaCppModelLoader, LlamaCppRuntime, the bridge interfaces
│   ├── commonTest/            the fake bridge and the runtime tests over it
│   ├── jvmMain/               actuals over the generated JNI bridges
│   ├── androidMain/           the same bridges, for the ART leg
│   └── nativeMain/            actuals over cinterop
├── native/                    the C++ facade over llama.cpp
│   ├── CMakeLists.txt         pulls llama.cpp via CPM
│   ├── CMakePresets.json      one preset per target
│   ├── facade/                koinference_facade.{h,cpp} — the whole native API
│   └── tests/                 GoogleTest suite for the facade
└── build/prebuilt/<target>/   libkoinference-facade.a, built by CMake (never committed)
```

## Running the tests

Most of both backends is covered by `commonTest` against a fake binding and needs no model or
native library — `./gradlew :backends:llamacpp:jvmTest` runs it. Only the tests that exercise real
inference need weights.

Every generation test is gated on an environment variable pointing at a model, and skips without
one — the two backends take different containers, so they take different variables:

```bash
# llama.cpp: llama.cpp's own 1.2 MB test model, which CI downloads and uses too.
curl -fsSLO https://huggingface.co/ggml-org/models/resolve/main/tinyllamas/stories260K.gguf
KOI_TEST_GGUF=$PWD/stories260K.gguf ./gradlew :backends:llamacpp:jvmTest :backends:llamacpp:macosArm64Test
```

`:backends:llamacpp:allTests` links a test executable for *every* native target, and each one
needs that target's `libkoinference-facade.a` under `build/prebuilt/`. Locally, run the per-target
task for the archive you actually built.

## Building the natives

The Kotlin build consumes a static library per target from `backends/llamacpp/build/prebuilt/`. CI
builds them (`.github/workflows/build-artifacts.yml`) and passes them along as workflow artifacts.
Locally, build the one you need:

```bash
cd backends/llamacpp/native
cmake --preset macosArm64
cmake --build --preset macosArm64 -j$(sysctl -n hw.ncpu)
mkdir -p ../build/prebuilt/macos_arm64
find build/macosArm64 -name "*.a" | xargs libtool -static -o ../build/prebuilt/macos_arm64/libkoinference-facade.a
```

Presets: `macosArm64`, `macosX64`, `linuxX64`, `iosArm64`, `iosSimulatorArm64`, `androidNativeArm64`,
`androidNativeX64`. The Android ones need `ANDROID_NDK_HOME`.

## The JNI leg

`facade/koinference_facade.h` is the single source of truth for both legs. Native targets bind it
through cinterop; the JVM target goes through JNI bridges that the Konan plugin generates from it
(`generateJvmInteropKoinference` → Kotlin bridges + a `.c` stub).

The plugin only *generates* — CMake compiles and links the stub (`KOI_BUILD_JNI=ON`, target
`koinference-jni`). Keeping the link in CMake keeps one toolchain end to end: the C++ runtime and the
Accelerate/Metal frameworks arrive transitively from `koinference-facade`, instead of being restated
as linker flags in Gradle.

The interop declares that CMake owns the link (`externalNativeBuild { cmake { … } }`), so the plugin
drives it: `cmakeBuildKoinference` generates the bridges, configures CMake with
`KONAN_JNI_STUB_DIR` / `KONAN_JNI_LIB_NAME`, and builds the `koinference-jni` target.
`./gradlew :backends:llamacpp:jvmTest` runs that chain and loads the result.

| property | purpose |
|---|---|
| `-PkoiStubDir=<dir>` | use an already-built stub and skip CMake entirely (this is what CI does) |

`cmake` and a JDK with `include/jni.h` are located by the plugin; set `$CMAKE` if your `cmake` lives
somewhere unusual, since the Gradle daemon does not inherit a login shell's PATH.

### Constrained output

`GenerationConstraint.JsonSchema` is converted to a GBNF grammar by the facade
(`koi_json_schema_to_grammar`, over llama.cpp's own `json_schema_to_grammar`) and handed to the
sampler, which then constrains decoding token by token. The conversion is not reimplemented in
Kotlin on purpose: it is a thousand lines with a regex compiler in it, and a second copy would
drift from the sampler consuming its output. A schema that does not convert raises
`IllegalArgumentException` rather than generating unconstrained.

## Publishing

`com.vanniktech.maven.publish`, configured once for every module in the root build file; the
coordinates, POM and license come from `gradle.properties` and each module's `POM_ARTIFACT_ID`.
`.github/workflows/publish.yml` runs on a GitHub release and passes the tag as `-PVERSION_NAME`,
so the checked-in version stays a `-SNAPSHOT` that cannot reach Central by accident.

## The LiteRT-LM backend

The runtime under it arrives as a binary rather than as source, and the two legs reach it
differently:

```
backends/litertlm/
├── src/
│   ├── commonMain/            LiteRtLmModelLoader, LiteRtLmRuntime, the bridge interfaces
│   ├── nativeMain/            actuals over cinterop + the reply-JSON reader
│   ├── androidMain/           actuals over the generated JNI bridges
│   └── androidDeviceTest/     on-device generation
├── native/
│   ├── CMakeLists.txt         downloads the C API archive, builds the facade + JNI stub
│   ├── CMakePresets.json      macosArm64, macosX64, iosArm64, iosSimulatorArm64
│   ├── facade/                koinference_litertlm_facade.{h,cpp}
│   └── tests/                 facade smoke test
└── native/build/<preset>/     libkoinference-litertlm-facade.a + the staged runtime dylib
```

Both legs bind the same C facade, the way `:backends:llamacpp` does — cinterop on Apple, generated
JNI bridges on Android. That was not always true: the Maven AAR's `liblitertlm_jni.so` is
version-scripted down to its 24 `Java_…_LiteRtLmJni_*` entry points and exports no `litert_lm_*`
symbols, so the Android leg used to go through `com.google.ai.edge.litertlm`'s Kotlin API. Release
0.16.0 added `litert_lm_c_api-0.1.0.zip`, whose Android slices export 144 of them, and that
dependency is gone.

The shared seam is a trio of `internal` interfaces — bridge → engine → conversation — with a
single `expect fun platformBridge()` behind it, so a fake stands in for both legs in `commonTest`.
`:backends:llamacpp` has the same shape; see [docs/backends.md](docs/backends.md).

A runtime is a resource. `unload`/`unloadAll` free the engine, and every call that frees
something native suspends (`updateGenerationParameters`, `updateRuntimeSettings`,
`resetConversation`) so that it waits for an in-flight generation instead of pulling the
handles out from under it. Changing the backend reloads the model, because LiteRT-LM decides
where a model runs when the engine is created.

The AAR this module produces carries `liblitert-lm.so` beside the JNI stub in `jniLibs/<abi>/`:
the stub records it as a `DT_NEEDED` and Android's loader resolves that from the same directory,
so CMake stages it there in a POST_BUILD step. There is no Maven runtime dependency any more, and
`KOILM_VERSION` in `native/CMakeLists.txt` is the only place the runtime version is pinned.

LiteRT-LM cannot be built from source here: its data processors include
`support/preprocessor/*.h` "from @litert", which is not published in the open-source LiteRT
tree, so the CMake build fails on every platform. Google ships the C API prebuilt, and the
`engine.h` in the xcframework is byte-identical to the one at the tag it was cut from, so the
header and the binary agree. `native/CMakeLists.txt` downloads and hash-checks that archive.

On Apple targets Gradle drives CMake itself (`buildFacade`) rather than through the Konan plugin.
`./gradlew :backends:litertlm:macosArm64Test` builds the facade, runs cinterop and links against
both the facade archive and the runtime dylib. The Android leg goes through the plugin, which
generates the bridges and lets CMake build the stub — the same arrangement `:backends:llamacpp`
uses.

Generation tests need a real model and are skipped without one. The smallest published
`.litertlm` is SmolLM2-135M-Instruct at 136 MB, too large for CI:

```bash
# macOS
KOI_TEST_LITERTLM=/path/to/SmolLM2_135M_Instruct.litertlm \
    ./gradlew :backends:litertlm:macosArm64Test

# Android — the device test reads a fixed path rather than an env var
adb shell mkdir -p /data/local/tmp/koinference
adb push SmolLM2_135M_Instruct.litertlm /data/local/tmp/koinference/
./gradlew :backends:litertlm:connectedAndroidDeviceTest
```

Structured output works on both legs — verified generating against a real model, not assumed.
`GenerationConstraint.JsonSchema` is passed to the facade unchanged and reaches llguidance, which
is linked into the prebuilt runtime and constrains decoding token by token.

## Targets (current)

| target | `:core` | `:backends:llamacpp` | `:backends:litertlm` |
|---|---|---|---|
| Android | ✅ | ✅ | ✅ |
| JVM | ✅ | ✅ | — |
| `macosArm64` | ✅ | ✅ | ✅ |
| `macosX64` | ✅ | ✅ | — |
| `iosArm64`, `iosSimulatorArm64` | ✅ | ✅ | — |
| `linuxX64` | ✅ | ✅ | — |

LiteRT-LM's remaining Apple targets have CMake presets ready but no Kotlin target: the facade has
to build for one before it can be added, or the link fails with undefined `koilm_*`. Linux would
additionally need a third runtime source (`libLiteRt.so`).
