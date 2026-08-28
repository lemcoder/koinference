# Adding a backend

Both backends have the same shape. That is deliberate and recent — they did not, and the
differences were accidents rather than decisions. This document is the shape, so a third backend
is a matter of filling it in rather than inventing one.

It is also where the *reasons* live. They used to sit in KDoc on the interfaces themselves, which
is how the LiteRT-LM bridge spent a release telling readers that Android could not bind the C API
months after it did.

## Two seams, not one

There is the seam a **consumer** sees — `Backend`, `ModelConfig`, `BackendRegistry` in `:core` —
and the seam a **backend implementor** sees, below. A caller never names `LlamaCppModelLoader`:

```kotlin
val koi = Koinference(LlamaCpp, LiteRtLm, config = ModelConfig(contextTokens = 512))
val runtime = koi.load(path)
```

`ModelLoader.load` returns a `TextModelRuntime` — everything a loaded text model can do, as one
type — so a caller who wants a reply does not first cast to prove what it got. That cast was the
same hedge the deleted embedding runtime was: room kept for a case that does not exist. If an
embedding backend is ever added, `load` widens or the registry gains a typed variant, and that
problem arrives with the code that needs it.

`ModelConfig` is one vocabulary for knobs the engines spell differently — llama.cpp's
`nCtx`/`nPredict` are LiteRT-LM's `maxTokens`/`maxOutputTokens`. A knob an engine has no
equivalent for is **ignored, never approximated**, and `Backend.honours` states which ones those
are so a caller can tell whether the seed it set was applied rather than finding out from an
irreproducible run.

`:core` does not enumerate backends and cannot: every backend depends on it. The registry is
assembled by the consumer, which is the only place that knows which modules were linked. That is
also why `id` is a `String` and not an enum — an enum would mean a new engine could not be added
without editing `:core` and every exhaustive `when` over it, which is the opposite of the goal.

## The seam

Three interfaces per backend, nested to match the lifetime nesting of what they wrap, plus one
`expect fun` that produces the outermost one:

| | llama.cpp | LiteRT-LM |
|---|---|---|
| binding | `LlamaCppBridge` | `LiteRtLmBridge` |
| loaded weights | `LlamaCppModel` | `LiteRtLmEngine` |
| one decoding context | `LlamaCppSession` | `LiteRtLmConversation` |
| entry point | `platformBridge()` | `platformBridge()` |

The nesting is the point: a session can only be produced by a model, so a handle cannot be used
without the thing that owns it, and a fake cannot hand out a session it never opened.

**Interfaces, not `expect class` handles.** An `expect class` can only be produced by a platform,
so with handles at the seam nothing above it is reachable from a test: conversation reuse, session
rebuild on a parameter change, reload on a backend change, unload racing a generation. All of that
is common code and none of it needs a model. `FakeLlamaCppBridge` and `FakeLiteRtLmBridge` in
`commonTest` cover it.

This was learnt on LiteRT-LM first. `:backends:llamacpp` kept a set of top-level
`internal expect fun`s for a while longer, and the result was one test in `commonTest` asserting
that a path ends in `.gguf` — everything past that `require` reached llama.cpp and could not be
exercised.

**Options are data classes, not parameter lists.** `ModelOptions`/`EngineOptions` and
`SessionOptions`/`ConversationOptions` carry what a backend needs to open each thing. A test then
asserts against a value rather than against a call with nine positional arguments.

**Every function throws on failure.** Callers do not check for null, zero or -1; a binding turns
whatever its C API returns into an exception with the model path in the message.

## The runtime above it

`RuntimeGuard` in `:core` is the shared scaffolding, and both runtimes use it:

- `whileOpen { }` — one caller at a time, and a failure rather than a call into freed memory once
  the runtime is unloaded. The check is *inside* the lock: outside it, an unload could pass
  between the check and the call.
- `streamWhileOpen { }` — the same, held across a whole flow collection. Streaming a reply is one
  long turn, not a series of independent calls, and a second generation starting half way through
  would interleave into the decoder state.
- `close { }` — idempotent, and it waits for an in-flight generation rather than freeing under it.
- `markClosed()` — for the reload that has already freed the old handles and failed to produce new
  ones. There is nothing left to release and nothing left that may be called.

A backend change reloads the weights on both engines, for the same underlying reason in two
different places: llama.cpp fixes GPU offload in `llama_model_params.n_gpu_layers` at load time,
LiteRT-LM decides where a model runs when the engine is created. If the reload fails, the runtime
is left unloaded and says so, rather than pretending to be on the new backend.

## What belongs on ModelRuntime

`ModelRuntime` carries what every engine has: the sampling parameters and device it was loaded
with, and suspending updates for both. It was an empty marker while both backends declared those
four members themselves, with near-identical KDoc explaining that the signatures matched but the
contracts did not.

They differ in cost, not in meaning — llama.cpp rebuilds a session and may reload the weights,
LiteRT-LM reopens a conversation and loses its prefilled history, and both are "this may throw away
work the engine had prepared". One contract states that, and stating it is what lets a caller
holding whatever `load()` returned retune it without knowing which engine answered. Before the
hoist that took a cast to a backend-specific interface, which put the caller straight back into
knowing its backend.

The test for it is typed as `ModelRuntime` on purpose: see `ModelRuntimeContractTest`.

**What stays on a backend's interface is what only that backend has.** LiteRT-LM's
`resetConversation` has no llama.cpp counterpart — that engine carries no conversation to forget —
so it is not hoisted. The bar is a counterpart that exists, not a signature that would compile.

## One runtime, and replies made of parts

`runtime` holds what every model has — settings, sampling parameters, `RuntimeGuard` — and
`GeneratingRuntime` is the one interface a backend implements to generate anything:

```kotlin
suspend fun generateResponse(prompt: List<PromptPart>, constraint: GenerationConstraint? = null): List<ResponsePart>
fun streamResponse(prompt: List<PromptPart>, constraint: GenerationConstraint? = null): Flow<ResponsePart>
```

**It was split by output type before, and a real model broke the split.** There was a
`runtime.text` with `TextRuntime` returning a `String` and a `runtime.vision` with `ImageRuntime`
returning one image. `Modality` was already a `Set`, so `setOf(TEXT, AUDIO)` was expressible — but a
model that interleaves speech with its transcript had no interface it could implement. Two return
types cannot express one ordered reply, and `Pair<String, List<ByteArray>>` throws away the ordering
that was the whole point.

`ResponsePart` is a sealed interface — `Text`, `Audio`, `Image` — so order is what the list carries.
`FakeOmniBackend` in `:core`'s tests is written as if it were such an engine, emitting
`Text("Hello") · Audio · Text(" there") · Audio`, and `MultiModalityTest` asserts the interleaving
survives both the blocking call and the stream. What it needs from `:core` is one `Modality`
constant and nothing else; `Backend`, `ModelLoader`, `ModelConfig`, `GeneratingRuntime` and
`RuntimeGuard` are all reused unchanged. `PromptPart` needed nothing either — it has carried
`ImageFile` and `AudioFile` from the start, which is why a vision-language model answering in words
is `Modality.TEXT`: **modality is named for the output**, because input was already multimodal.

`Audio` and `Image` are deliberately not `data class`es. `ByteArray` equality is by reference, so a
generated `equals` would report two identical replies as different; theirs compare the bytes.

**There is no `text()` helper in `:core`, and that is a decision.** A caller narrowing a reply to
text is discarding whatever else the model produced, and the discard belongs in the calling code
where it can be seen. `ServedModel` in the benchmark app does it explicitly, because a
chat-completions `delta` has nowhere to put audio. The tests have their own helper; the library does
not ship one.

`TokenCounting` stayed in `runtime.text`, because a token is a text notion. It is the only member of
that package now.

`koi_embed` is still in `koinference_facade.h`. **Leave it there.** The generated JNI bridges are
numbered by declaration position, so deleting a function from the middle of the header renumbers
every bridge after it and each hand-written `kniBridgeN` call in `JniBridge.kt` silently starts
calling a different C function. An unused C function costs nothing; a renumbered ABI costs an
afternoon. Same rule as ever: **append new functions at the end.**
## Three engines, and two of them read GGUF

`:backends:cera` reaches a Rust engine through Cera's published UniFFI/JNA Kotlin bindings —
`com.hyeons-lab:cera-ffi-jvm` and `cera-ffi-android`, one artifact per leg, differing only in which
natives they package. There is no C facade, no CMake and no cinterop in that module: the binding is
already Kotlin, so the whole backend is Kotlin.

**It declares `jvm()` and `android` only.** UniFFI's Kotlin bindings need a JVM, Apple gets a
separate Swift package, and there is no Kotlin/Native binding to consume — so those targets are not
declared, rather than declared and left throwing. `:backends:litertlm` set the precedent for a
backend covering a subset of the repository's targets. The knock-on is that `:benchmark:core`'s
backend list had to become `expect fun benchmarkBackends()`, since its macosArm64 leg cannot see
Cera: a genuine platform difference, answered per platform.

`UniffiBridge` / `UniffiModel` / `UniffiSession` are byte-identical in `jvmMain` and `androidMain`,
and stay that way. Same rule and same reason as `JniBridge.kt`: two copies of a file that only calls
a generated API is cheaper than a source set that exists to deduplicate.

**Cera and llama.cpp both claim `.gguf`.** `Koinference.backendFor` returns the first registered
backend that handles a path, so registration order decides, and `backendById("cera")` is how a
caller says which one it meant regardless of order. Deliberately not an error: an application that
registers both and loads a GGUF should get one, and which one is a question only the caller can
answer. The benchmark app sidesteps it entirely by picking an engine rather than a path.

Two things about that engine cost an afternoon to find, and both are visible in `UniffiSession`:

- **The chat template is not optional.** Cera exposes `applyChatTemplate` rather than applying it,
  and an instruct model handed raw text answers with one token repeated to the budget — LFM2.5
  emits `?` twenty-four times, which looks like a broken decoder rather than a missing template. The
  turn is built in the binding, where the engine is.
- **Streaming works through the blocking `generateStreaming`, not the async one.** The async variant
  never delivered a batch here; the blocking one delivers them as they decode, so it runs on an IO
  thread with `trySendBlocking` pushing back onto Cera's worker. Backpressure rather than `trySend`,
  because a dropped chunk is a silently short reply.

**A session accumulates, and `CeraRuntime` resets it before every turn.** `appendText` adds to one
conversation and a generation appends its reply, so an unreset session answers the same question
more slowly each time — 4.8s to 6.7s over four turns, and on device it eventually stalled a
benchmark with the engine idle. Every call is an independent turn, which is what the other backends
give and what a benchmark needs.

Constrained decoding is GBNF only. Cera's bindings expose no JSON-schema converter, so
`GenerationConstraint.JsonSchema` throws rather than generating unconstrained text that looks like
it honoured the schema.

## A backend may refuse the device it was installed on

`:core` has no notion of device capability, and deliberately gained none: no
`Backend.unsupportedReason` to override, no map of unrunnable engines on `Koinference`. Nor does
`:backends:llamacpp`'s common code — the requirement is Android's alone, so it lives in
`LlamaCppBridge.android.kt` and nowhere else. `platformBridge()` refuses to hand back a binding on
hardware that cannot run it, throwing `BackendUnsupportedException`, which surfaces out of
`Koinference.load` like any other load failure.

`BackendUnsupportedException` lives in `:core` because it is vocabulary a consumer catches, not
behaviour `:core` implements.

**The failure being prevented is not an exception.** ggml selects its ARM kernels at compile time —
the Q4_0 path is behind `#if defined(__ARM_FEATURE_DOTPROD)` and `ggml-cpu` calls `getauxval`
nowhere — so a CPU without the dot-product extension takes SIGILL partway through a decode. That
reaches the application as a process death with no stack trace and nothing to catch.

There is no `expect`/`actual` for this, on purpose. An `expect fun` would put the question in front
of all five legs so that four could answer null: macOS, iOS, Linux and the desktop JVM each link an
archive built for the machine that runs it, and none of them can be installed onto hardware it was
not built for. **The Android AAR is the only shipped binary that can.**

The cost is that the refusal cannot be unit-tested — there is nothing to inject, and the check reads
a file that only exists on the device. `LlamaCppDeviceTest` asserts the Pixel 8a is *not* refused,
which is the half that can be verified on hardware anyone here owns.

Two independent floors, and only the second is really about capability:

- `:backends:llamacpp` declares `minSdk 31` (`androidMinSdkLlamaCpp` in the version catalog) against
  24 everywhere else, so a consumer below it fails at manifest merge. A policy choice — dotprod
  predates API 31.
- **API level does not imply dotprod.** Cortex-A53 and A55 class arm64 parts ship on current
  Android, so the check reads `asimddp` out of `/proc/cpuinfo`. Kotlin, not JNI: it is the same file
  `CpuPlacement` already reads.

This is also why `LlamaCppBridge.android.kt` is no longer byte-identical to `LlamaCppBridge.jvm.kt`,
while `JniBridge.kt` still is. The pair that must not diverge is the generated-bridge caller; the
`platformBridge()` actuals are per-platform files precisely so a platform can differ here.

LiteRT-LM refuses nothing. Its runtime contains `sdot` and `smmla`, but XNNPACK dispatches on
runtime CPU detection, so its floor is unknown rather than known to be the same — untested for want
of a device without dotprod.

## Platform files are named `<Expect>.<platform>.kt`

`LlamaCppBridge.kt` in commonMain is answered by `LlamaCppBridge.jvm.kt`,
`LlamaCppBridge.android.kt` and `LlamaCppBridge.native.kt`; `CpuPlacement.kt` by
`CpuPlacement.android.kt`, `CpuPlacement.macos.kt`, `CpuPlacement.ios.kt` and so on.
`ActualFileNamingTest` enforces both halves — the suffix must match the source set, and a
commonMain file of that name must exist.

An earlier convention named these after the *binding* (`JniBridge.kt`, `FacadeBridge.kt`) to avoid
`Duplicate JVM class name … LlamaCppBridgeKt`, which a commonMain file with real top-level
declarations triggers against a same-named platform file. The dotted suffix avoids it too, since
`CpuPlacement.android.kt` and `CpuPlacement.kt` produce different facade classes, so the name is
free to say what it answers.

## No source sets for sharing; per-platform source sets for differences

`JniBridge.kt` is byte-identical in `jvmMain` and `androidMain`, and `GgufFileSource.kt` is
byte-identical too. **Keep both copies. Do not add an intermediate source set for them.**

The generated `…llamacpp.jni` bridges are produced per compilation — `generateJvmInterop` for the
JVM target, the Android interop for each ABI — into each target's own source set. An intermediate
`jvmSharedMain` holding the hand-written actual would not see them, so the dedup that looks free
costs a source-set layout that fights the generator. Two copies of a file that only calls
generated functions is the cheaper trade.

The reverse is also a rule: where platforms genuinely differ, use a source set *per platform* rather
than per family. `platformCpuPlacement()` has five actuals — `androidMain`, `jvmMain`, `macosMain`,
`iosMain`, `linuxMain` — because all five want different answers, and a shared `nativeMain`
implementation was silently giving Linux the Darwin one. See the rules at the top of `CLAUDE.md`.

## Checklist

1. `internal interface XBridge` / `XModel` / `XSession` in `commonMain/internal`, plus
   `internal expect fun platformBridge(): XBridge` and the two options data classes.
2. A `FacadeBridge.kt` and/or `JniBridge.kt` per leg.
3. A runtime in `commonMain` that owns a `RuntimeGuard` and holds no platform types.
4. A loader in `commonMain` with an `internal constructor` taking the bridge, and a public one
   taking a `ModelConfig` and defaulting to `platformBridge()`. That constructor pair is what lets
   tests inject a fake. Map `ModelConfig`'s fields onto the engine's own names here — this is the
   only place that should know both vocabularies.
5. An `object X : Backend` — id, `handles`, `honours`, `loader`. Ten lines, and the only thing a
   consumer needs to see.
6. If the engine can be installed on hardware it cannot run on, refuse in the leg that ships that
   binary — `platformBridge()` throwing `BackendUnsupportedException` — not in common code.
7. `FakeXBridge` in `commonTest`, and the runtime tests that go with it. Assert `honours` against
   what the binding actually passes down; that set is a claim, and a wrong one is invisible at
   run time.
8. If it should be benchmarkable: add it to `benchmarkBackends` in `:benchmark:core`'s
   `Engines.kt`. **No adapter to write** — `BackendEngine` adapts any `Backend`.
