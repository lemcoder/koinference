# Tuning llama.cpp on device

Measured on a Pixel 8a (Tensor G3: 4x Cortex-A510 @ 1.70 GHz, 4x Cortex-A715 @ 2.37 GHz,
1x Cortex-X3 @ 2.91 GHz) against `LFM2.5-1.2B-Instruct-Q4_0.gguf`, 32-token budget.

**5.8 tok/s to 15.2 tok/s**, from two changes that are both defaults rather than code paths.

## Threads: fewer than you would guess

Decoding one token is a GEMV over the whole model. It reads every weight once and does almost no
arithmetic per byte, so it is bound by **memory bandwidth, not by cores**. Two threads already
saturate this phone's DRAM; past that, extra threads pay ggml's per-node barrier and share the
SoC power budget, which clocks everything down.

Medians of interleaved runs, both orderings:

| threads | tok/s |
|---|---|
| 1 | 10.6 |
| **2** | **12.1 – 15.3** |
| 3 | 12.3 – 14.6 |
| 4 | 5.3 – 6.2 |
| 5 | 2.5 – 2.7 |
| 7 (the old default) | ~6 |

The old default was `hardware_concurrency() - 2`, which picked 7 here — about half speed.

**The count is deliberately not the number of big cores.** That is 4 on this SoC, and 4 is exactly
where throughput falls off a cliff. `detect_decode_threads()` takes *half* the largest cluster
above the slowest frequency tier, floored at 2: it lands on the measured optimum here and still
scales on a machine with a wider big cluster. The lone prime core is excluded — one core 23%
faster than four others still waits at the same barrier.

Pass `n_threads` explicitly when you know better. The balance moves with model size,
quantization, and how much bandwidth the rest of the device is using.

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
