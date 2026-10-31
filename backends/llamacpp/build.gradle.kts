@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import io.github.lemcoder.model.NativeTarget
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.konan)
    alias(libs.plugins.androidKmpLibrary)
}

// CMake preset for the machine running the build — the only one the JVM target can load.
val hostPreset: String = System.getProperty("os.name").lowercase().let { os ->
    val arm = System.getProperty("os.arch").lowercase()
        .let { it.contains("aarch64") || it.contains("arm64") }
    when {
        os.contains("mac") -> if (arm) "macosArm64" else "macosX64"
        else -> "linuxX64"
    }
}

kotlin {
    jvm()

    // Android runs on ART, so it takes the JNI leg like the JVM does — not androidNative*, which is
    // Kotlin/Native and would need konan to link the CMake/NDK archive itself.
    android {
        namespace = "io.github.lemcoder.koinference.llamacpp"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        // Higher than the rest of the repo, on purpose: see androidMinSdkLlamaCpp in the catalog.
        // A consumer with a lower minSdk fails at manifest merge, which is the intended answer —
        // the alternative is an install that dies on SIGILL on hardware without dotprod.
        minSdk = libs.versions.androidMinSdkLlamaCpp.get().toInt()

        // Instrumented tests are the only place ART actually loads the packaged .so; everything else
        // about Android is verified by inspecting the AAR.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    macosArm64()
    macosX64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: TextRuntime is now a supertype of LlamaCppTextRuntime,
            // so consumers need :core to call anything on a loaded runtime.
            api(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // No generated accessor for this one: withDeviceTest creates it while this block is being
        // configured.
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
        }
    }
}

konan {
    config { konanHome.set(kotlinNativeDistribution()) }

    // The facade and llama.cpp for each Kotlin/Native target, built by CMake. The plugin merges the
    // archives the build leaves into one and embeds it in the cinterop klib, so nothing that links
    // this module — the test executables, :benchmark:core — names a library or a framework.
    val facade = libraries.create("koinference-llama") {
        targets(
            NativeTarget.MACOS_ARM64, NativeTarget.MACOS_X64,
            NativeTarget.IOS_ARM64, NativeTarget.IOS_SIMULATOR_ARM64,
            NativeTarget.LINUX_X64,
        )
        cmake {
            path.set(file("native/CMakeLists.txt"))
            buildTargets.add("koinference-facade")
            arguments.addAll("-DKOI_BUILD_JNI=OFF", "-DBUILD_TESTS=OFF")
            listOf(
                NativeTarget.MACOS_ARM64 to "macosArm64",
                NativeTarget.MACOS_X64 to "macosX64",
                NativeTarget.IOS_ARM64 to "iosArm64",
                NativeTarget.IOS_SIMULATOR_ARM64 to "iosSimulatorArm64",
            ).forEach { (nativeTarget, presetName) ->
                target(nativeTarget) {
                    preset.set(presetName)
                    // Not build/<preset>, which the JNI build of the same preset uses.
                    buildDirectory.set(layout.buildDirectory.dir("cmake/library-$presetName"))
                }
            }
            // No preset: off a Linux host the plugin hands CMake Kotlin/Native's toolchain, so the
            // archive matches Kotlin/Native's sysroot. The preset's variables, spelled out.
            target(NativeTarget.LINUX_X64) {
                arguments.addAll(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                )
            }
        }
    }

    // One binding for every consumer: KGP's cinterop on the Kotlin/Native targets, generated JNI
    // bindings on the JVM and Android. CMake links the JNI library, so one toolchain builds the
    // stub and the facade together.
    bindings {
        create("koinference") {
            includeDirs.from("native/facade")
            library(facade)
            jni {
                cmake {
                    path.set(file("native/CMakeLists.txt"))
                    buildTargets.add("koinference-jni")
                    arguments.add("-DKOI_BUILD_JNI=ON")
                    target(NativeTarget.host) { preset.set(hostPreset) }
                    // No preset on Android: the plugin hands CMake the NDK's toolchain file, the ABI
                    // and minSdk as ANDROID_PLATFORM. The preset's variables, spelled out.
                    mapOf(
                        NativeTarget.ANDROID_ARM64 to listOf("-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod"),
                        NativeTarget.ANDROID_X64 to emptyList(),
                    ).forEach { (nativeTarget, targetArguments) ->
                        target(nativeTarget) {
                            arguments.addAll(
                                "-DCMAKE_BUILD_TYPE=Release",
                                "-DGGML_LLAMAFILE=OFF",
                                "-DANDROID_STL=c++_static",
                            )
                            arguments.addAll(targetArguments)
                            // The NDK compiles with -g whatever the build type, and ELF carries
                            // DWARF in the .so — 44 MB of the 48 MB before stripping.
                            arguments.add("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--strip-debug")
                            // Android ships no libomp.so and ggml links OpenMP by default, so the
                            // library fails to dlopen on device: UnsatisfiedLinkError, every call.
                            arguments.add("-DGGML_OPENMP=OFF")
                            // GPU offload via Vulkan, opt-in: it moves the API floor to 28 and
                            // costs ~500 MB PSS, so the default AAR stays CPU-only.
                            if (project.hasProperty("koiVulkan")) arguments.add("-DKOI_VULKAN=ON")
                        }
                    }
                }
            }
        }
    }
}

// cinterop tracks the .def file and its compiler options, not the headers those options point
// at, so editing the facade leaves the task UP-TO-DATE and the klib describing the previous API.
// It surfaces as "Unresolved reference koi_*" for a function that is plainly in the header.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.CInteropProcess>().configureEach {
    inputs.files(fileTree("native/facade"))
        .withPropertyName("facadeHeaders")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
