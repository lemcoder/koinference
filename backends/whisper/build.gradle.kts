@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import io.github.lemcoder.model.NativeTarget
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.konan)
    alias(libs.plugins.androidKmpLibrary)
}

// The preset for the machine running the build — the only one the JVM target can load.
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

    // ART takes the JNI leg, exactly as it does for llama.cpp.
    android {
        namespace = "io.github.lemcoder.koinference.whisper"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()

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
            api(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
        }
    }
}

konan {
    config { konanHome.set(kotlinNativeDistribution()) }

    // The facade and whisper.cpp for each Kotlin/Native target, built by CMake. The plugin merges the
    // archives the build leaves into one and embeds it in the cinterop klib.
    val facade = libraries.create("koinference-whisper") {
        targets(
            NativeTarget.MACOS_ARM64, NativeTarget.MACOS_X64,
            NativeTarget.IOS_ARM64, NativeTarget.IOS_SIMULATOR_ARM64,
            NativeTarget.LINUX_X64,
        )
        cmake {
            path.set(file("native/CMakeLists.txt"))
            buildTargets.add("koinference-whisper-facade")
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
    // bindings on the JVM and Android. CMake links the JNI library, per target.
    bindings {
        create("koinferenceWhisper") {
            includeDirs.from("native/facade")
            library(facade)
            jni {
                cmake {
                    path.set(file("native/CMakeLists.txt"))
                    buildTargets.add("koinference-whisper-jni")
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
                            // The NDK compiles with -g whatever the build type, and DWARF rides
                            // inside the .so.
                            arguments.add("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--strip-debug")
                        }
                    }
                }
            }
        }
    }
}

// cinterop tracks the .def, not the header it names, so a facade edit would leave the klib
// describing the previous API — "unresolved reference koiw_*" for a function plainly in the header.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.CInteropProcess>().configureEach {
    inputs.files(fileTree("native/facade"))
        .withPropertyName("facadeHeaders")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
