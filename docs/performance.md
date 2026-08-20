# Tuning llama.cpp on device

Measured on a Pixel 8a (Tensor G3: 4x Cortex-A510 @ 1.70 GHz, 4x Cortex-A715 @ 2.37 GHz,
1x Cortex-X3 @ 2.91 GHz) against `LFM2.5-1.2B-Instruct-Q4_0.gguf`, 32-token budget.

**5.8 tok/s to ~38 tok/s.** All of it from defaults and build flags; no new code paths in the
inference loop.

## Threads: pin them, then use the whole big cluster

The thread count and thread *placement* are one question, and getting the placement wrong makes
the count look like a bandwidth wall that isn't there.

| threads | unpinned | pinned to the big cluster |
|---|---|---|
| 1 | 10.6 | — |
| 2 | 12.1 – 15.3 | 27.3 |
| 3 | 12.3 – 14.6 | 36.6 |
| **4** | **5.3 – 6.2** | **34.6 – 39.8** |
| 5 | 2.5 – 2.7 | 25.4 |
| 6 | — | 0.6 |
| 7 (the original default) | ~6 | — |

Unpinned, throughput fell off a cliff at 4 threads and it was tempting to call that DRAM
saturation. It was not. Android is free to schedule a ggml worker onto an A510 little core, and
because every graph node ends in a barrier, the whole batch then runs at that core's pace. One
misplaced worker halves the result.

`koi_session_create` therefore builds a `ggml_threadpool` with a `cpumask` over the big cluster and
`strict_cpu` set, so worker *i* is pinned to the *i*-th core in the mask instead of drifting. With
that, 4 threads go from 5-6 tok/s to 34-40, and the run-to-run spread collapses too — consecutive
runs land within 0.2 tok/s of each other where before they swung 3x.

`detect_decode_threads()` then takes the **whole** largest cluster above the slowest frequency
tier. It used to take half, which was compensating for migration rather than for bandwidth. The
lone prime core is still excluded: one core 23% faster than four others waits at the same barrier.

**Never ask for more workers than the mask holds.** `strict_cpu` places worker *i* on the *i*-th
core of the mask, so the surplus goes unplaced — 6 threads against this phone's 4 big cores
measured **0.6 tok/s**. The facade clamps `n_threads` to the mask size for that reason.

### The mask is narrowed by what the app may use, and it is reported

Two things a mask built from SoC topology cannot know, both handled in `detect_big_cores()`:

- **Which cpuset the app is in.** On this phone `foreground` is `0-7` and `background` is `0-3`, so
  a mask naming cpu4-7 is entirely outside the permitted set for a backgrounded app — and
  `sched_setaffinity` fails rather than degrading. The candidate set is intersected with
  `sched_getaffinity`.
- **Which cores are online.** `/sys/devices/system/cpu/online` is intersected too, since cores go
  offline under thermal pressure.

If either leaves nothing, the session runs unpinned rather than pinned to cores it cannot have.
Frequency is also not always enough to separate clusters — some SoCs clock a big and a little core
to the same ceiling — so a single frequency tier falls back to grouping by the `CPU part` id from
`/proc/cpuinfo` (this device: `0xd46` A510, `0xd4d` A715, `0xd4e` X3).

**The placement is in the results.** `engineMetadata.pinnedCpus` records what the facade actually
did — `4,5,6,7` here — or `default` when unpinned, and is absent for an engine that exposes no
placement. Those are three different answers and the schema keeps them apart. It matters because
every heuristic above is validated on exactly one device; a results file from an unfamiliar
topology now says which cores it ran on instead of leaving it to be inferred from timings.

`ThreadPlacement` in `:core` also lets a caller re-pin at run time — `pinToCpus` rebuilds the pool
between decodes, under the runtime's guard. There is **no evidence yet** that reacting to anything
beats picking once at load; the mechanism exists so that question can be measured.

## KleidiAI: no effect on decode

`KOI_KLEIDIAI=ON` builds ggml's CPU backend with ARM's own quantized-matmul microkernels. Measured
against the pinned baseline it is a wash — 37.8 tok/s against 37.9 — which is what it should be:
decoding one token is a batch-1 GEMV, and KleidiAI's kernels are GEMM-shaped. Expect it to matter
for prefill, not for tokens per second. Left in as an option, off by default.

## Arch flags: dotprod matters, i8mm does not

`GGML_NATIVE` is off because these are cross-compiles, which leaves ggml at the NDK's baseline —
plain `armv8-a`. That compiles the dot-product kernels *out*, and they are what Q4_0 decoding
runs on.

| build | tok/s (median of 3) |
|---|---|
| `armv8.2-a+dotprod` | 14.8 |
| `armv8.2-a+dotprod+i8mm` | 15.2 |

i8mm is inside the noise, which is what a bandwidth-bound kernel should look like — so the preset
carries **dotprod only**. i8mm is ARMv8.6 and would raise the device floor for nothing.

**This is a floor, not a detection.** A device older than dotprod (pre-2018, roughly Cortex-A53)
takes SIGILL rather than falling back, and `minSdk` is 24. Supporting below that floor means
building several variants and picking at runtime, the way
[llama.rn](https://github.com/mybigday/llama.rn) does — five arm64 variants from `armv8-a` up to
`armv8.2-a+dotprod+i8mm`. That work is not done here.

## GPU offload

Off by default. Turn it on at build time and select it at run time:

```bash
cmake --preset androidNativeArm64 -DKOI_VULKAN=ON -DANDROID_PLATFORM=android-28
```

```kotlin
backend.loader(ModelConfig(settings = RuntimeSettings(accelerator = Accelerator.GPU)))
```

The run-time half is the same `Accelerator` LiteRT-LM uses — the facade already mapped it onto
`llama_model_params.n_gpu_layers`. What was missing was a build that had a GPU backend in it at
all: without one, `Accelerator.GPU` silently runs on the CPU, which is llama.cpp's own behaviour
for a backend that was never compiled in.

Measured on the Pixel 8a (Mali-G715), LFM2.5-1.2B Q4_0:

| | tok/s | peak PSS |
|---|---|---|
| CPU-only build, 2 threads | 15.2 | 733 MB |
| Vulkan offload | 17.9 | 1,224 MB |
| OpenCL offload | 18.6 | 1,386 MB |

**Treat the ordering as unproven.** Interleaving CPU against OpenCL on the same binary inverted it
— 16.9 CPU against 15.2 GPU, individual runs spanning 14.7 to 21.4 — so all three sit inside each
other's noise on this device. That is the expected shape for a bandwidth-bound GEMV: the GPU
shares the same DRAM as the CPU, so offload moves the work without widening the pipe. The memory
cost, unlike the speed, is unambiguous.

A further wrinkle: peak PSS was the same ~1,384 MB in *both* modes of the OpenCL build. The
backend initialises and allocates even at `n_gpu_layers = 0`, so compiling OpenCL in costs about
650 MB whether or not anything is offloaded.

### OpenCL needs a manifest declaration

`KOI_OPENCL=ON` builds the other backend. Loading it takes one more thing, and missing it produces
an error that reads like a missing file:

```
dlopen failed: library "libOpenCL.so" not found: needed by
  .../libiogithublemcoderkoinferencellamacppjnistubs.so in namespace clns-9
```

The library is present, in `/vendor/lib64`, and on the vendor public-libraries allowlist. From
**API 31** an app must additionally declare it:

```xml
<uses-native-library android:name="libOpenCL.so" android:required="false" />
```

That lives in `:backends:llamacpp`'s `src/androidMain/AndroidManifest.xml` and merges into
consumers, so an app gets it by depending on the module.

Linking needs a `libOpenCL.so` too, which the NDK does not ship. `KOI_OPENCL` builds the Khronos
ICD loader from source purely as a **link target** — its SONAME is `libOpenCL.so`, so at run time
the vendor driver of that name resolves instead. It is deliberately not packaged into `jniLibs`:
this device registers no ICD (`/vendor/etc/OpenCL/vendors/` does not exist), so a packaged loader
would shadow the real driver with one that enumerates zero platforms.

**Unmeasured.** The manifest declaration and the build both landed after the test device was
disconnected, so the OpenCL path is verified to configure and compile, and not verified to run.
Vulkan is the path with numbers behind it.

**Enabling it raises the floor to API 28.** ggml-vulkan calls Vulkan 1.1 entry points
(`vkGetPhysicalDeviceFeatures2`), which the NDK stub only exports from 28; this module's `minSdk`
is 24. That is why `KOI_VULKAN` is opt-in rather than on by default — the shipped AAR keeps its
API 24 floor and no `libvulkan.so` dependency.

The Vulkan C++ bindings (`vulkan.hpp`) and the SPIR-V headers are not in the NDK sysroot, so
`KOI_VULKAN=ON` pulls both through CPM, pinned like llama.cpp itself. `glslc` is found inside the
NDK's `shader-tools`.

## Measuring without fooling yourself

Two traps cost real time on this hardware.

**The APK can ship a stale `.so`.** `cmakeBuildKoinferenceAndroid` does not track the facade
sources as inputs, so editing `koinference_facade.cpp`, rebuilding and running can measure the
*previous* binary — AGP's `mergeAndroidMainJniLibFolders` serves its cached copy. Two experiments
in a row were void this way. Verify before believing a number:

```bash
unzip -p benchmark/core/build/outputs/apk/androidTest/core-androidTest.apk \
    lib/arm64-v8a/libiogithublemcoderkoinferencellamacppjnistubs.so \
  | strings | grep -c cpuinfo_max_freq       # 0 means the change is not in there
```

Deleting `*/intermediates/merged_jni_libs`, `merged_native_libs`, `stripped_native_libs` and the
`outputs/apk` directory forces an honest repackage.

**The results file is pulled, not produced — so it can be stale too.** `adb pull` of
`benchmark-results.json` happily returns the previous run's file when the run did not write one.
`adb shell rm -f` it before every run. One A/B in this session reported four identical rows
because `timeout` is not installed on macOS, so `am instrument` never ran and every pull returned
the same old file.

**Single runs are worthless here.** The same configuration measured 8.5 and 2.3 tok/s minutes
apart. Run configurations interleaved, several rounds, and check the ordering both ways — a sweep
that always runs the same config last has a thermal ramp built into it. `batteryTemperaturePeakC`
in the results lags the SoC and will happily read identical across a 2x swing.

## Running it

`connectedAndroidDeviceTest` destroys its own results: AGP uninstalls the APK when the run ends,
wiping the app directory the instrumentation wrote to. Drive it directly instead — this also gives
each engine a fresh process, which is what the harness wants anyway.

```bash
adb install -r -g benchmark/core/build/outputs/apk/androidTest/core-androidTest.apk

adb shell am instrument -w \
  -e engine llama.cpp \
  -e model /data/local/tmp/koinference/LFM2.5-1.2B-Instruct-Q4_0.gguf \
  -e promptFile /data/local/tmp/koinference/prompts.json \
  -e modelId LFM2.5-1.2B-Instruct -e quantization q4_0 \
  -e iterations 3 -e warmup 1 -e maxNewTokens 32 \
  io.github.lemcoder.koinference.benchmark.test/androidx.test.runner.AndroidJUnitRunner

adb pull /sdcard/Android/data/io.github.lemcoder.koinference.benchmark.test/files/benchmark-results.json
```
