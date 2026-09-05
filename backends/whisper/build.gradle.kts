@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import io.github.lemcoder.interop.jvmInterops
import io.github.lemcoder.interop.jvmInteropsContainer
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

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
    jvm {
        compilations["main"].jvmInterops {
            create("koinferenceWhisper") {
                packageName.set("io.github.lemcoder.koinference.whisper.jni")
                includeDirs.from(file("native/facade"))

                externalNativeBuild {
                    cmake {
                        path.set(file("native/CMakeLists.txt"))
                        preset.set(hostPreset)
                        targets.add("koinference-whisper-jni")
                        arguments.add("-DKOI_BUILD_JNI=ON")
                    }
                }
            }
        }
    }

    // ART takes the JNI leg, exactly as it does for llama.cpp.
    android {
        namespace = "io.github.lemcoder.koinference.whisper"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compilations["main"].jvmInterops {
            create("koinferenceWhisperAndroid") {
                defFile(project.file("src/nativeInterop/cinterop/koinferenceWhisper.def"))
                packageName.set("io.github.lemcoder.koinference.whisper.jni")
                includeDirs.from(file("native/facade"))

                externalNativeBuild {
                    cmake {
                        path.set(file("native/CMakeLists.txt"))
                        targets.add("koinference-whisper-jni")
                        arguments.add("-DKOI_BUILD_JNI=ON")
                        // The NDK compiles with -g whatever the build type, and DWARF rides inside
                        // the .so.
                        arguments.add("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--strip-debug")

                        abi("arm64-v8a") { preset.set("androidNativeArm64") }
                        abi("x86_64") { preset.set("androidNativeX64") }
                    }
                }
            }
        }
    }

    listOf<KotlinNativeTarget>(
        iosArm64(),
        iosSimulatorArm64(),
        linuxX64(),
        macosArm64(),
        macosX64(),
    ).forEach { target ->
        val main = target.compilations["main"]

        main.cinterops.create("koinferenceWhisper") {
            compilerOpts("-I${projectDir}/native/facade")
        }

        val libDir = findProperty("koiLibDir")?.toString()
            ?: layout.buildDirectory.dir("prebuilt/${target.konanTarget.name}").get().asFile.path

        val isApple = target.konanTarget.family.isAppleFamily

        // A .a records nothing about what it needs: ggml's Metal and Accelerate backends are inside
        // the archive on Apple targets, and the frameworks have to be named here. Same lesson as
        // :backends:llamacpp, one module over.
        val frameworks = if (isApple) {
            listOf(
                "-framework", "Metal",
                "-framework", "MetalKit",
                "-framework", "Foundation",
                "-framework", "Accelerate",
            )
        } else {
            emptyList()
        }

        val linkerOptions = listOf("-L$libDir", "-lkoinference-whisper-facade") + frameworks

        main.compileTaskProvider.configure {
            compilerOptions.freeCompilerArgs.addAll(linkerOptions.flatMap { listOf("-linker-options", it) })
        }

        // The klib's linker options travel to whoever links it; they do not apply to the binaries
        // this project links, so the test executable needs them named again.
        target.binaries.all {
            linkerOpts(linkerOptions)
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

        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
        }
    }
}

// The bridges resolve the stub from java.library.path; the interop reports where its build put it,
// so nothing here hardcodes a path the plugin owns. -PkoiStubDir points at a prebuilt one, which is
// how CI reuses the natives job's output.
val prebuiltStubDir: String? = findProperty("koiStubDir")?.toString()
val interopLibraryDir = kotlin.jvm().compilations["main"].jvmInteropsContainer()
    .getByName("koinferenceWhisper").resolvedLibraryDirectory

// The generator runs cinterop out of a Kotlin/Native distribution the Kotlin plugin downloads in its
// own task, so jvmTest works on a machine that has never built a native target here.
tasks.matching { it.name.startsWith("generateJvmInterop") }.configureEach {
    dependsOn(tasks.matching { it.name == "downloadKotlinNativeDistribution" })
}

tasks.named<Test>("jvmTest") {
    if (prebuiltStubDir == null) dependsOn("cmakeBuildKoinferenceWhisper")
    systemProperty(
        "java.library.path",
        prebuiltStubDir?.let { file(it).absolutePath } ?: interopLibraryDir.get().asFile.absolutePath,
    )
}

// cinterop tracks the .def, not the header it names, so a facade edit would leave the klib
// describing the previous API — "unresolved reference koiw_*" for a function plainly in the header.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.CInteropProcess>().configureEach {
    inputs.files(fileTree("native/facade"))
        .withPropertyName("facadeHeaders")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
