# Adding a backend

Both backends have the same shape. That is deliberate and recent — they did not, and the
differences were accidents rather than decisions. This document is the shape, so a third backend
is a matter of filling it in rather than inventing one.

It is also where the *reasons* live. They used to sit in KDoc on the interfaces themselves, which
is how the LiteRT-LM bridge spent a release telling readers that Android could not bind the C API
months after it did.

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

## Platform files are named after their binding

`FacadeBridge.kt` for the cinterop leg, `JniBridge.kt` for the ART/JNI leg — never after the
common file they implement.

A commonMain file with any top-level declaration collides with a same-named platform file
(`Duplicate JVM class name … LlamaCppBridgeKt`). `expect` declarations generate no JVM class,
which is why a shared name worked while the seam was expect/actual functions and stopped working
the moment the seam became interfaces.

## No shared jvm/android source set

`JniBridge.kt` is byte-identical in `jvmMain` and `androidMain`, and `GgufFileSource.kt` is
byte-identical too. **Keep both copies. Do not add an intermediate source set for them.**

The generated `…llamacpp.jni` bridges are produced per compilation — `generateJvmInterop` for the
JVM target, the Android interop for each ABI — into each target's own source set. An intermediate
`jvmSharedMain` holding the hand-written actual would not see them, so the dedup that looks free
costs a source-set layout that fights the generator. Two copies of a file that only calls
generated functions is the cheaper trade.

## Checklist

1. `internal interface XBridge` / `XModel` / `XSession` in `commonMain/internal`, plus
   `internal expect fun platformBridge(): XBridge` and the two options data classes.
2. A `FacadeBridge.kt` and/or `JniBridge.kt` per leg.
3. A runtime in `commonMain` that owns a `RuntimeGuard` and holds no platform types.
4. A loader in `commonMain` with an `internal constructor` taking the bridge, and a public one
   defaulting to `platformBridge()`. That constructor pair is what lets tests inject a fake.
5. `FakeXBridge` in `commonTest`, and the runtime tests that go with it.
6. If it should be benchmarkable: a `BenchmarkInferenceEngine` in `:benchmark:core`'s `Engines.kt`
   and an entry in `availableEngines()`. Implement `applyWorkload` — both current engines fix
   their output limit and sampler at load time, so the runner sets them before `initialize`.
