// Sideload switch: -PceraLocal=<dir> swaps the published bindings for a local pair.
//
// Cera's CI publishes the per-ABI native library as a workflow artifact and the generated Kotlin
// binding is checked into its repo, so an unreleased commit can be measured without a Rust
// toolchain. The two must come from the *same* commit — UniFFI checksums the API and the binding
// refuses a library it does not recognise, which is what makes this safe to do at all.
//
//   <dir>/kotlin/uniffi/cera_ffi/cera_ffi.kt
//   <dir>/jniLibs/arm64-v8a/libcera_ffi.so
//
// Off by default: released artifacts are what the repository builds against.
val ceraLocal: String? = findProperty("ceraLocal")?.toString()

// A sideloaded library left behind would be packaged beside the published AAR's own, and the
// measurement after it would silently be of the wrong binary — the exact trap this repository has
// fallen into twice with stale .so files. Fail instead.
val strayNative = file("src/androidMain/jniLibs/arm64-v8a/libcera_ffi.so")
require(ceraLocal != null || !strayNative.exists()) {
    "A sideloaded libcera_ffi.so is staged at $strayNative but -PceraLocal was not passed. " +
        "Delete it to build against the published artifact, or pass -PceraLocal to use it."
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
}

// No native build of our own, and no Konan plugin: Cera ships UniFFI bindings as published
// artifacts — a JVM jar carrying natives for darwin-aarch64/linux-x86-64/win32-x86-64, and an
// Android AAR carrying jniLibs for four ABIs. Both legs consume the same generated Kotlin API,
// so this module is Kotlin only.
kotlin {
    jvm()

    // jvm and android only. Cera's Kotlin bindings are UniFFI over JNA, which needs a JVM;
    // Apple gets a separate Swift package and there is no Kotlin/Native binding, so those targets
    // are not declared rather than declared and left throwing. :backends:litertlm sets the
    // precedent for a backend covering a subset of the repository's targets.
    android {
        namespace = "io.github.lemcoder.koinference.cera"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        // Cera's own AAR floor, which is lower than :backends:llamacpp's 31 — this engine carries
        // no compile-time ISA requirement to refuse a device over.
        minSdk = libs.versions.androidMinSdk.get().toInt()

        // The runtime's own logic needs no engine to exercise, so it runs on this leg too: that is
        // what compiles the Android actual against it.
        withHostTest {}

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // No generated accessor: withDeviceTest creates this source set while this block configures.
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
        }

        // The same generated binding on both legs, from two artifacts: the JVM one bundles desktop
        // natives, the Android one bundles jniLibs and JNA's @aar.
        jvmMain.dependencies {
            implementation(libs.cera.ffi.jvm)
        }

        androidMain.dependencies {
            if (ceraLocal == null) {
                implementation(libs.cera.ffi.android)
            } else {
                // The AAR is what normally brings JNA and the native library; sideloading has to
                // bring both itself.
                // @aar: on Android JNA ships its per-ABI dispatch libraries in that artifact.
                implementation("net.java.dev.jna:jna:5.15.0@aar")
            }
        }

        if (ceraLocal != null) {
            androidMain.get().kotlin.srcDir("$ceraLocal/kotlin")
        }
    }
}
