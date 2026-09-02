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

### The heuristic is Kotlin, tested, and re-decided per decode

`CpuPlacementPolicy` owns the whole rule. It reads the same files C used to, through an injectable
`SystemFiles`, which is what lets it be tested against topologies nobody here owns — a three-cluster
SoC, a machine whose cores all clock the same, an app confined to the little cluster. The facade is
mechanism only now (`koi_session_set_cpu_mask`); it holds no second copy of the heuristic to drift
from this one.

**Only the ART legs activate it.** `platformCpuPlacement()` is an `expect`: the JVM and Android
actuals run the policy, and the native one answers that there is nothing to place. Darwin has no
`/proc` or `/sys` to read and no equivalent of `sched_setaffinity` — `thread_policy_set` affinity
tags are advisory and ignored on Apple silicon — and linuxX64 is a desktop target that is not
fighting a little cluster. The rule stays in common code anyway, because it is pure given
`SystemFiles` and that is what lets its topology cases be tested everywhere rather than only where
it runs. `CpuPlacementAppleTest` holds the native leg to declining, so the heuristic cannot start
half-working on a platform it was never meant for.

Two things a mask built from SoC topology cannot know, both handled by the policy:

- **Which cpuset the app is in.** On this phone `foreground` is `0-7` and `background` is `0-3`, so
  a mask naming cpu4-7 is entirely outside the permitted set for a backgrounded app — and
  `sched_setaffinity` fails rather than degrading. The candidate set is intersected with
  `sched_getaffinity`.
- **Which cores are online.** `/sys/devices/system/cpu/online` is intersected too, since cores go
  offline under thermal pressure.

If either leaves nothing, the session runs unpinned rather than pinned to cores it cannot have.

**The decision is re-made immediately before every decode**, not once at load, because it expires:
an app moved to the background is confined to the little cluster, and a mask chosen in the
foreground then names cores it is forbidden from touching. Choosing costs a handful of small file
reads and re-pinning only happens when the answer actually changed, so the steady state costs the
reads and nothing else. A decode boundary is also the only safe moment — the pool is in use during
one.

Placement is not on the public API. Nothing outside `:backends:llamacpp` selects or reports it:
LiteRT-LM manages its own threads and has no counterpart, so there is nothing to abstract over,
and the benchmark does not need to know that llama.cpp pins anything.
Frequency is also not always enough to separate clusters — some SoCs clock a big and a little core
to the same ceiling — so a single frequency tier falls back to grouping by the `CPU part` id from
`/proc/cpuinfo` (this device: `0xd46` A510, `0xd4d` A715, `0xd4e` X3).

**`CpuPlacementDeviceTest` runs the policy on a real device.** The unit tests cover the branches
against fake files; what they cannot check is whether those files exist and mean what the rule
assumes on actual hardware — whether an app sandbox can read `/proc/self/status`, whether every core
reports `cpuinfo_max_freq`, whether the numbers separate the clusters at all. The instrumented test
asserts the properties that must hold on any device rather than a specific core list: every chosen
core is in the cpuset and online, none is at the slowest frequency tier, and they all run at the
same speed. There is **no evidence yet** that reacting to anything
beats picking once at load; the mechanism exists so that question can be measured.

## macOS: no pinning, and the opposite thread count

Darwin **cannot** pin. ggml says so itself:

```c
#elif defined(__APPLE__)
static bool ggml_thread_apply_affinity(const bool * mask) {
    // Not supported on Apple platforms
    UNUSED(mask);
    return true;
}
```

The mask is discarded and success is returned, so a Darwin build that passed one would look like it
had worked. `platformCpuPlacement()`'s native leg therefore declines outright, and
`CpuPlacementAppleTest` holds it to that. Thread *priority* is implemented on Apple (SCHED_FIFO), so
that — not affinity — is the lever if one is ever wanted.

Which makes the thread count the only knob, and its best value is the opposite of Android's. On an
M4 (4 performance + 6 efficiency cores), LFM2.5-1.2B Q4_0, medians of repeated runs:

| threads | tok/s |
|---|---|
| 4 | 102 |
| 5 (the old default) | 120 |
| 7 | 139 |
| **8** | **144** |
| 9 | 135 |
| 10 | 123 |

More threads win here, where on Android 4 beat 8 by a wide margin. The difference is pinning:
Darwin's scheduler places heterogeneous cores well on its own, so the efficiency cores contribute
instead of stalling a barrier — which is precisely what the little cluster did on Android until the
threads were pinned to the big one.

So the native leg's policy is "do not pin, run `cores - 2` workers", which is 8 here and measures
145 as the default. It is deliberately *not* the Android answer, and the two should not be made to
match.

**Every platform has its own policy**, as an actual of one `expect` in commonMain. Both halves of
the decision — the mask and the worker count — travel together because they are one choice:

| leg | pins | workers | measured on |
|---|---|---|---|
| `androidMain` | the big cluster | one per pinned core | Pixel 8a, 5.8 → 38 tok/s |
| `macosMain` | no, Darwin ignores affinity | `cores - 2` | M4, 120 → 144 tok/s |
| `iosMain` | no, same as macOS | `cores - 2` | **nothing** — see below |
| `linuxMain` | the big cluster, same rule as Android | one per pinned core | **nothing** |
| `jvmMain` | the big cluster when the files are there | one per pinned core | — |

The two unmeasured legs are marked as such in their own source. `iosMain` is deliberately not shared
with `macosMain` through `appleMain`: an iPhone's split is narrower (2 performance cores against 4
efficiency, where the M4 has 4 against 6), so `cores - 2` may not hold, and keeping the file separate
means it can change without touching a platform where the number is known. `linuxMain` reuses the
Android rule rather than inventing one, and self-disables on a machine with one kind of core.

`sysconf` is called from Kotlin through `platform.posix`; none of this is our C. The facade keeps a
`cores - 2` fallback purely for a caller reaching the C API directly — every Kotlin binding supplies
a count, so there is no second rule in C to drift from the tested one.

`KOI_BENCH_THREADS` sweeps it: `KOI_TEST_GGUF=… KOI_BENCH_THREADS=8 ./gradlew
:benchmark:core:macosArm64Test --rerun-tasks`.

## KleidiAI: unmeasured, and the old number was not a measurement

`KOI_KLEIDIAI=ON` builds ggml's CPU backend with ARM's own quantized-matmul microkernels. This
section used to report a wash — 37.8 tok/s against 37.9 — and that comparison was not valid.

`KOI_KLEIDIAI` only ever forced `GGML_CPU_KLEIDIAI` **on**. A build directory that had KleidiAI
enabled once kept it in its CMake cache, so switching the flag off changed nothing and the "off"
row was very likely a second KleidiAI build. The flag is forced both ways now, but the numbers were
taken on a device configuration that no longer exists, so they are gone rather than restated.

What surfaced the bug is worth remembering, because it did not look like a stale flag: changing
`GGML_CPU_ARM_ARCH` to `armv8.6-a+i8mm` made ggml ask for kai kernels the cached KleidiAI build had
never produced, and the link failed on two dozen undefined `kai_*` symbols. That reads like an i8mm
problem. **A CMake cache that outlives an experiment is a measurement hazard and a build hazard at
the same time.**

The expectation is still that it does nothing for decode — KleidiAI's kernels are GEMM-shaped and
decoding one token is a batch-1 GEMV — so if it matters anywhere it is prefill. Left in as an
option, off by default, unmeasured.

## Arch flags: dotprod matters, nothing above it does

`GGML_NATIVE` is off because these are cross-compiles, which leaves ggml at the NDK's baseline —
plain `armv8-a`. That compiles the dot-product kernels *out*, and they are what Q4_0 decoding
runs on. Turning dotprod on is the whole win; every ISA level above it was measured and none of
them can be told apart from the noise.

Four builds, each verified distinct by disassembling the packaged `.so` rather than trusting the
preset — dotprod-only has 0 `smmla`, the i8mm build 244, the SVE2 build 376 plus 12177 SVE ops.
Three rounds, config order reversed every round, 75 s of cooldown before each run, one workload
(`short_generation_v1`, 32 tokens), 3 iterations after a discarded warmup:

| build | median tok/s | per round (1 / 2 / 3) |
|---|---|---|
| `armv8.2-a+dotprod` | 38.1 | 38.1 / 35.6 / 38.9 |
| `armv8.2-a+dotprod+fp16` | 39.2 | 35.8 / 39.2 / 40.2 |
| `armv8.6-a+i8mm+fp16` | 37.2 | 38.2 / 38.4 / 33.4 |
| `armv9-a+i8mm+fp16+sve2` | 36.2 | 34.2 / 38.2 / 36.2 |

**No config wins twice in a row, and each one's own spread is wider than every gap between them.**
The clearest evidence is the same SVE2 binary reading 34.2 tok/s running last in round 1 and 38.2
running first in round 2: a 12% swing from position alone, larger than any difference the table
claims. Reading a winner out of these numbers would be reading the thermal ramp.

That is what a bandwidth-bound GEMV should look like. Decoding one token multiplies the weights by
a vector; the arithmetic is not the bottleneck, so wider arithmetic buys nothing. i8mm and SVE2
are GEMM instructions and belong to prefill, which this workload barely exercises.

So the preset carries **dotprod only** — the lowest floor of the four, for the same throughput.

**Enabling everything is not free, and not a feature list.** `GGML_CPU_ARM_ARCH` sets a compile-time
baseline: the compiler may emit SVE2 anywhere in the CPU backend, so a device without it takes
SIGILL rather than falling back. `armv9-a+i8mm+fp16+sve2` moves the floor from 2018 hardware to
2022 hardware (Pixel 7 and up) for a difference this measurement cannot see.

**Runtime dispatch is the only way to have both, and upstream now ships it.**
`GGML_CPU_ALL_VARIANTS` builds a backend per ISA tier and picks at load — with Android-specific
tiers, `android_armv8.0_1` through `android_armv9.2_2` — which is what
[llama.rn](https://github.com/mybigday/llama.rn) does by hand. Two things block it here, both read
out of the b10516 tree rather than guessed:

- It `FATAL_ERROR`s without `GGML_BACKEND_DL`, which requires `BUILD_SHARED_LIBS`. This build forces
  that off and links a static facade, so it is a link-model change, not a flag.
- `ggml_backend_load_best` searches `get_executable_path()`, which on Android is `/proc/self/exe` —
  `/system/bin/app_process64`, not the app's `lib/<abi>/`. Finding the variants would need
  `GGML_BACKEND_PATH` set at run time from `ApplicationInfo.nativeLibraryDir`.

Worth doing to *lower the floor* — an `armv8.0` tier would run on hardware that SIGILLs today. Not
worth doing for speed: the table above is what the higher tiers are worth on this device.

## Cera against llama.cpp, same weights

Pixel 8a, `short_generation_v1`, 32-token budget, one warmup discarded and three measured, all three
engines in one sitting through the app. llama.cpp and Cera read the *same* `LFM2.5-1.2B-Instruct-Q4_0.gguf`;
LiteRT-LM reads its own int4 `.litertlm` of the same model.

| engine | tok/s | ttft ms |
|---|---|---|
| llama.cpp | 31.0 | 388 |
| LiteRT-LM | 24.9 | 442 |
| Cera | 2.4 | 2092 |

**Cera is roughly 13x slower here on identical weights, and this is not the ISA trap.** The obvious
suspect was the one that cost this repository 2.6x already — a baseline ARM build with the
dot-product kernels compiled out — and it is ruled out: the published `libcera_ffi.so` disassembles
to 242 `sdot` and 24 `smmla`, so dotprod and i8mm are both in there.

What has *not* been ruled out is thread placement, which is the other thing that cost this repository
6.5x. llama.cpp reaches its number by pinning two big cores; Cera picks its own workers, was measured
at ~3.1 cores busy, and exposes no thread or affinity knob through its bindings — so on a big.LITTLE
phone it is likely decoding partly on A510s. That is a hypothesis, not a measurement: no experiment
here has separated it from kernel maturity, and Cera 0.4.0 is an early release.

Nothing about this makes the backend wrong; it makes the number worth re-taking against a later Cera
and, if the bindings ever expose one, a thread count.

## Pinning a process, for engines with no thread knob

llama.cpp takes a CPU mask through its facade, and that is where its 6.5x came from. Cera and
ExecuTorch expose no threading control at all: Cera's bindings have no such field, and ExecuTorch's
`libexecutorch.so` exports six symbols — `JNI_OnLoad` and five `AsrModule` entry points — with the
threadpool hidden, so there is nothing for a shim to bind to.

What is still reachable is the *process*. `CpuAffinity` in the benchmark app pins every thread to the
fast clusters, from Java, with no native code:

- `taskset -ap <pid>` **does not work on Android.** The child process cannot read
  `/proc/<pid>/task/` of another process under `hidepid`, even at the same uid, and reports "No such
  file or directory" — which reads like the process is gone rather than unreadable.
- `/proc/self/task` *is* readable, so it enumerates there and pins each TID with `taskset -p`. That
  needs no `/proc` access from the child at all. Threads created later inherit from their creator, so
  pinning before the engine builds its pool covers the pool.
- The mask is read from sysfs, not hardcoded. Asking for cpus 4-8 on a Pixel 8a yields `4-7`: **the
  X3 prime core is refused**, because the app's cpuset excludes it.

**It helps one engine and ruins the other.** Three rounds, order flipped each round, 45 s of cooldown
before every run, `short_generation_v1`:

| engine | affinity off | affinity on | ttft off → on |
|---|---|---|---|
| ExecuTorch (stories110M) | 3.6 tok/s | **8.4** | 358 → 88 ms |
| Cera (LFM2.5-1.2B Q4_0) | **11.4** tok/s | 3.3 | 2139 → 1205 ms |

Per round, with no crossover: ExecuTorch 3.3 / 3.9 / 3.6 against 8.5 / 8.7 / 8.1; Cera 11.9 / 10.9 /
11.4 against 3.4 / 3.0 / 3.3.

Prefill improves for both — it is compute-bound and likes the fast cores. Decode splits, and the
mechanism is oversubscription: both engines size their pools for a nine-CPU machine and then contend
on four. ExecuTorch was losing more than that to little-core migration, so it wins; Cera was not, so
it loses. Pinning also collapses ExecuTorch's spread — 3.3-7.9 unpinned against 8.1-8.7 pinned — the
same steadying llama.cpp got.

**So it is opt-in per engine and off by default** (`--es affinity big`). A process-wide pin is not a
free win, and "pin to the big cluster" is not advice that survives contact with a second engine.

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

Three traps cost real time on this hardware.

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
`outputs/apk` directory forces an honest repackage. The llama.cpp pin bump to b10516 was first
"verified" against an APK still carrying the b10472 binary, which passed all nine device tests and
proved nothing; `strings … | grep -oE 'b95502ba9|60eeeb608'` on the packaged `.so` is what caught
it, ggml's commit being the one thing in there that differs between the two.

For an ISA sweep the same check has to look at instructions rather than strings, since the source is
identical and only codegen differs:

```bash
$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-objdump -d /tmp/v.so \
  | grep -cE '\bsmmla\b'    # 0 for a dotprod-only build, 244 for +i8mm
```

**A CMake cache outlives the experiment that set it.** `GGML_CPU_KLEIDIAI` stayed on for a whole
session of builds that asked for it off, because the option was forced one way only. See the
KleidiAI section: it invalidated one published measurement and then broke two builds of an unrelated
sweep. When an A/B changes a CMake variable, check `CMakeCache.txt` says what you think it says.

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
