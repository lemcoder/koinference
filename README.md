# koinference

Kotlin Multiplatform wrapper interfaces for inference runtimes.

## Modules

- `:library` - high-level common interfaces:
  - `ModelLoader` (`load` / `unload`)
  - `ModelRuntime` (response generation, generation params, runtime settings, schema constraints)
- `:llamacpp` - `llama.cpp` stub implementation based on GGUF model paths.

## Targets (current)

- Android
- iOS (`iosArm64`, `iosSimulatorArm64`)
- macOS (`macosArm64`, `macosX64`)
