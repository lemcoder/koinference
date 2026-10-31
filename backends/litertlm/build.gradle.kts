@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import io.github.lemcoder.model.NativeTarget
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.konan)
    alias(libs.plugins.androidKmpLibrary)
}

// Where CMake builds the macOS facade. It also stages the LiteRT-LM dylib here at configure time,
// which is what the macOS link below reads.
val macosFacadeBuildDir: File = layout.buildDirectory.dir("cmake/library-macosArm64").get().asFile

// macOS arm64 and Android. The other Apple targets have presets ready in
// native/CMakePresets.json but are untried; Linux needs a third runtime source
// (libLiteRt.so) and is not wired up.
kotlin {
    android {
        namespace = "io.github.lemcoder.koinference.litertlm"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()

        // commonTest holds the runtime's own logic and needs no runtime to run, so it is worth
        // running on this leg too: it compiles the Android actuals against it, which is where
        // a duplicate JVM class name or a drifted SamplerConfig would show up.
        withHostTest {}

        // On-device is the only place the packaged .so is actually loaded and the only place
        // generation can be proven; everything else about Android is structural.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    macosArm64 {
        // The facade archive travels inside the cinterop klib; the runtime it calls into is a
        // dylib, which an archive records nothing about, so it is named here. The rpath is what
        // lets the linked binary find it at run time.
        //
        // Both paths are absolute and belong to this checkout, so they are right for building
        // and testing here and wrong the moment this klib is published: a consumer needs the
        // dylib shipped alongside and an rpath of their own. Publishing this target needs that
        // question answered first.
        val linkerOptions = listOf(
            "-L${macosFacadeBuildDir.path}",
            "-llitert-lm",
            "-rpath", macosFacadeBuildDir.path,
        )

        compilations["main"].compileTaskProvider.configure {
            compilerOptions {
                freeCompilerArgs.addAll("-linker-options", linkerOptions.joinToString(" "))
            }
        }

        // The klib carries the options above for whoever links it, but every binary this
        // project links — the test executable today, a framework tomorrow — needs them
        // separately, or it fails with undefined litert_lm_*.
        binaries.all { linkerOpts(linkerOptions) }
    }

    sourceSets {
        commonMain.dependencies {
            // api: TextRuntime is a supertype of LiteRtLmTextRuntime and GenerationConstraint
            // appears in its signature, so a consumer cannot call generateResponse without
            // :core on the compile classpath.
            api(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
            // Both legs now parse the facade's reply envelope, so this is common rather than
            // native-only: Android used to get a typed Message from Google's Kotlin API.
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // No generated accessor: withDeviceTest creates this while the block is configuring.
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
        }
    }
}

konan {
    config { konanHome.set(kotlinNativeDistribution()) }

    // The facade for macOS, built by CMake against the prebuilt runtime. The plugin embeds the
    // archive in the cinterop klib.
    val facade = libraries.create("koinference-litertlm") {
        targets(NativeTarget.MACOS_ARM64)
        cmake {
            path.set(file("native/CMakeLists.txt"))
            buildTargets.add("koinference-litertlm-facade")
            target(NativeTarget.MACOS_ARM64) {
                preset.set("macosArm64")
                buildDirectory.set(macosFacadeBuildDir)
            }
        }
    }

    // One binding for both legs: KGP's cinterop on macOS, generated JNI bindings on Android.
    // CMake links Android's JNI library together with the facade and the runtime, so one NDK
    // clang sees all three.
    bindings {
        create("koinferenceLiteRtLm") {
            defFile.set(file("src/nativeInterop/cinterop/koinference_litertlm.def"))
            includeDirs.from("native/facade")
            library(facade)
            jni {
                cmake {
                    path.set(file("native/CMakeLists.txt"))
                    buildTargets.add("koinference-litertlm-jni")
                    arguments.add("-DKOI_BUILD_JNI=ON")
                    // No preset on Android: the plugin hands CMake the NDK's toolchain file, the ABI
                    // and minSdk as ANDROID_PLATFORM. The preset's variables, spelled out.
                    listOf(NativeTarget.ANDROID_ARM64, NativeTarget.ANDROID_X64).forEach { nativeTarget ->
                        target(nativeTarget) {
                            arguments.addAll(
                                "-DCMAKE_BUILD_TYPE=Release",
                                // liblitert-lm.so links its own C++ runtime statically, so a
                                // shared STL would share nothing and need packaging besides.
                                "-DANDROID_STL=c++_static",
                                // The NDK compiles with -g whatever the build type and ELF carries
                                // DWARF inside the .so.
                                "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--strip-debug",
                            )
                        }
                    }
                }
            }
        }
    }
}

// cinterop tracks the .def, not the header it names, so a facade edit would leave the klib
// describing the previous API — "unresolved reference koilm_*" for a function plainly in the header.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.CInteropProcess>().configureEach {
    inputs.files(fileTree("native/facade"))
        .withPropertyName("facadeHeaders")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
