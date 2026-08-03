# koinference

Kotlin Multiplatform wrapper interfaces for inference runtimes.

## Modules

- `:core` — high-level common interfaces:
  - `ModelLoader` (`load` / `unload`)
  - `ModelRuntime` (response generation, generation params, runtime settings, schema constraints)
- `:backends:llamacpp` — `llama.cpp` backend, driving a C facade from every target.

## Layout

```
core/                          common interfaces
backends/llamacpp/
├── src/
│   ├── commonMain/            LlamaCppModelLoader, gguf parser
│   ├── jvmMain/               actuals over the generated JNI bridges
│   └── nativeMain/            actuals over cinterop
├── native/                    the C++ facade over llama.cpp
│   ├── CMakeLists.txt         pulls llama.cpp via CPM
│   ├── CMakePresets.json      one preset per target
│   ├── facade/                koinference_facade.{h,cpp} — the whole native API
│   └── tests/                 GoogleTest suite for the facade
└── build/prebuilt/<target>/   libkoinference-facade.a, built by CMake (never committed)
```

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
(`generateJvmInterop` → Kotlin bridges + a `.c` stub).

The plugin only *generates* — CMake compiles and links the stub (`KOI_BUILD_JNI=ON`, target
`koinference-jni`). Keeping the link in CMake keeps one toolchain end to end: the C++ runtime and the
Accelerate/Metal frameworks arrive transitively from `koinference-facade`, instead of being restated
as linker flags in Gradle.

`./gradlew :backends:llamacpp:jvmTest` runs the whole chain — generate, CMake configure, CMake build,
test — and loads the stub from the preset's build dir. Useful properties:

| property | purpose |
|---|---|
| `-PkoiStubDir=<dir>` | use an already-built stub and skip CMake entirely (this is what CI does) |
| `-PkoiCmake=<path>` | cmake binary, if the Gradle daemon's PATH cannot see it |
| `-PkoiJniHome=<jdk>` | a JDK shipping `include/jni.h` — IDE-bundled JBRs strip the headers |

## Targets (current)

- Android
- iOS (`iosArm64`, `iosSimulatorArm64`)
- macOS (`macosArm64`, `macosX64`)
- JVM
