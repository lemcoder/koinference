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

## Two modalities, and what that cost

`runtime` holds what every model has — settings, sampling parameters, `RuntimeGuard`. Output-shaped
interfaces live under it: `runtime.text` for `TextRuntime` / `StreamingTextRuntime` /
`TokenCounting` / `TextModelRuntime`, `runtime.vision` for `ImageRuntime` / `ImageModelRuntime` /
`GeneratedImage`.

A second modality was added as a probe — `FakeVisionBackend` in `:core`'s tests, written as if it
were real, for a modality this repository has no engine for. What it needed from `:core` was a
`Modality` constant and an output interface. Reused unchanged: `Backend`, `ModelLoader`,
`ModelConfig`, `RuntimeGuard`, the whole settings surface, and `PromptPart` — which has carried
`ImageFile` and `ImageBytes` from the start, and is why a vision-language model answering in words
is `Modality.TEXT` rather than something new. `honours` needed nothing either: a diffusion model has
a seed and no top-k, which the same set already expresses.

**One thing did break, and it is the thing that was predicted.** `ModelLoader.load` returned
`TextModelRuntime`, which stopped being true the moment a loader could produce something else. It
returns `ModelRuntime` again, and `Koinference` gained `loadText` and `loadVision`, which check the
backend's declared `Modality` *before* reading the weights and narrow the result. The cast is the
library's now, once, with a message naming the mismatch — not every caller's.

Modality is named for the **output**, because that is the axis the interfaces split on. Input was
already multimodal.

## Text runtimes only

There is one kind of runtime and it produces text. There used to be a sealed hierarchy with a
`LlamaCppEmbeddingRuntime` in it that nothing implemented, which cost every caller of
`load()` a downcast that could never fail — including the benchmark adapter and the sample app.
`load()` now returns the backend's text runtime directly.

`koi_embed` is still in `koinference_facade.h`. **Leave it there.** The generated JNI bridges are
numbered by declaration position, so deleting a function from the middle of the header renumbers
every bridge after it and each hand-written `kniBridgeN` call in `JniBridge.kt` silently starts
calling a different C function. An unused C function costs nothing; a renumbered ABI costs an
afternoon. Same rule as ever: **append new functions at the end.**

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
6. `FakeXBridge` in `commonTest`, and the runtime tests that go with it. Assert `honours` against
   what the binding actually passes down; that set is a claim, and a wrong one is invisible at
   run time.
7. If it should be benchmarkable: add it to `benchmarkBackends` in `:benchmark:core`'s
   `Engines.kt`. **No adapter to write** — `BackendEngine` adapts any `Backend`.
