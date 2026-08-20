@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import io.github.lemcoder.hostKonanTarget
import io.github.lemcoder.interop.jvmInterops
import io.github.lemcoder.hostKonanTarget
import io.github.lemcoder.interop.jvmInteropsContainer
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

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
    jvm {
        compilations["main"].jvmInterops {
            create("koinference") {
                packageName.set("io.github.lemcoder.koinference.llamacpp.jni")
                includeDirs.from(file("native/facade"))

                externalNativeBuild {
                    cmake {
                        path.set(file("native/CMakeLists.txt"))
                        preset.set(hostPreset)
                        targets.add("koinference-jni")
                        arguments.add("-DKOI_BUILD_JNI=ON")
                    }
                }
            }
        }
    }

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

        compilations["main"].jvmInterops {
            create("koinferenceAndroid") {
                defFile(project.file("src/nativeInterop/cinterop/koinference.def"))
                packageName.set("io.github.lemcoder.koinference.llamacpp.jni")
                includeDirs.from(file("native/facade"))

                // One CMake build per ABI, each landing in jniLibs/<abi>/ for AGP to package. The
                // NDK builds the stub and the facade together, so no toolchains are mixed.
                externalNativeBuild {
                    cmake {
                        path.set(file("native/CMakeLists.txt"))
                        targets.add("koinference-jni")
                        arguments.add("-DKOI_BUILD_JNI=ON")

                        // The NDK toolchain compiles with -g whatever the build type, and ELF
                        // carries DWARF in the .so — 44 MB of the 48 MB before stripping.
                        arguments.add("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--strip-debug")

                        // Android ships no libomp.so and ggml links OpenMP by default, so the
                        // library fails to dlopen on device: UnsatisfiedLinkError, every call.
                        arguments.add("-DGGML_OPENMP=OFF")

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

        main.cinterops.create("koinference") {
            val koiFacadeHeader = "${projectDir}/native/facade" // Header location
            compilerOpts("-I$koiFacadeHeader")
        }

        // koiLibDir overrides the prebuilt dir — useful when pointing at a local CMake output.
        // Keyed by the Kotlin/Native target name (macos_arm64), not the Kotlin one (macosArm64),
        // because that is how CMake and CI lay the archives out.
        val libDir = findProperty("koiLibDir")?.toString()
            ?: layout.buildDirectory.dir("prebuilt/${target.konanTarget.name}").get().asFile.path

        val isApple = target.konanTarget.family.isAppleFamily

        // A .a records nothing about what it needs. ggml's Metal and BLAS backends are compiled
        // into the archive on Apple targets, and the frameworks behind them have to be named
        // here or the link fails on _MTLCreateSystemDefaultDevice and _cblas_sgemm.
        val linkerOptions = listOf("-L$libDir", "-lkoinference-facade") +
            if (isApple) {
                listOf(
                    "-framework", "Metal",
                    "-framework", "MetalKit",
                    "-framework", "Foundation",
                    "-framework", "Accelerate",
                )
            } else {
                emptyList()
            }

        main.compileTaskProvider.configure {
            compilerOptions {
                freeCompilerArgs.addAll("-linker-options", linkerOptions.joinToString(" "))
            }
        }

        // The klib carries the options above for whoever links it, but a binary this project
        // links — the test executable — gets them separately; the same seam :backends:litertlm
        // hits. Every target, not just the ones with native tests: commonMain calls the bridge
        // now, so any test binary references koi_* whether or not its own source set does.
        target.binaries.all {
            linkerOpts(linkerOptions)
        }
    }


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

// cinterop tracks the .def file and its compiler options, not the headers those options point
// at, so editing the facade leaves the task UP-TO-DATE and the klib describing the previous API.
// It surfaces as "Unresolved reference koi_*" for a function that is plainly in the header.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.CInteropProcess>().configureEach {
    inputs.files(fileTree("native/facade"))
        .withPropertyName("facadeHeaders")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// The bridges resolve the stub library from java.library.path. The interop reports where its build
// put the library; CI builds it once in the natives job and passes the directory with -PkoiStubDir=.
val prebuiltStubDir: String? = findProperty("koiStubDir")?.toString()
val interopLibraryDir = kotlin.jvm().compilations["main"].jvmInteropsContainer()
    .getByName("koinference").resolvedLibraryDirectory

// The bindings generator runs cinterop out of a Kotlin/Native distribution, which the Kotlin plugin
// fetches in its own task. Depending on that directly is cheaper than compiling a native target just
// to trigger the download, and it makes `jvmTest` work on a machine that has never built this
// project — nothing else in that task graph touches a native target.
tasks.matching { it.name.startsWith("generateJvmInterop") }.configureEach {
    dependsOn(tasks.matching { it.name == "downloadKotlinNativeDistribution" })
}

// CI collects host artifacts from build/prebuilt; the interop reports where its build left the
// library, so nothing outside the plugin has to know that layout.
val collectHostJniStub by tasks.registering(Copy::class) {
    group = "interop"
    description = "Copy the host JNI library into build/prebuilt/jni/<target>/."
    dependsOn("cmakeBuildKoinference")
    from(interopLibraryDir) { include("*.dylib", "*.so", "*.dll") }
    into(layout.buildDirectory.dir("prebuilt/jni/${hostKonanTarget().name}"))
}

tasks.named<Test>("jvmTest") {
    if (prebuiltStubDir == null) dependsOn("cmakeBuildKoinference")
    systemProperty(
        // Resolved against the project so a caller can pass a relative path; CI does, because
        // ${'$'}{{ github.workspace }} is not available where the matrix is declared and expands to "".
        "java.library.path",
        prebuiltStubDir?.let { file(it).absolutePath } ?: interopLibraryDir.get().asFile.absolutePath,
    )
}

// Rebuild the host facade archive and merge it into build/prebuilt/, which is where the native
// targets link from.
//
// CI builds these in its own job and downloads them, so this is for working locally: edit the
// facade, run this, and the Kotlin/Native targets see the change. Without it the archive silently
// stays whatever it was, and the failure arrives as "Undefined symbols: _koi_*" for a function
// that is plainly in the header — which cost three debugging rounds before this task existed.
val collectHostFacade by tasks.registering(Exec::class) {
    group = "interop"
    description = "Build the facade for this host and merge it into build/prebuilt/."

    val nativeDir = layout.projectDirectory.dir("native").asFile
    val konanName = if (System.getProperty("os.arch").lowercase().contains("aarch64")) {
        "macos_arm64"
    } else {
        "macos_x64"
    }
    val outputDir = layout.buildDirectory.dir("prebuilt/$konanName").get().asFile

    workingDir = nativeDir
    commandLine(
        "sh", "-c",
        // KOI_BUILD_JNI=OFF explicitly: this task wants the facade archive, not the stub, and
        // the build directory it shares with the interop has the option cached ON. Leaving it
        // would send configure looking for jni.h, which the Gradle daemon's JBR does not ship.
        "cmake --preset $hostPreset -DKOI_BUILD_JNI=OFF -DBUILD_TESTS=OFF && " +
            "cmake --build --preset $hostPreset --target koinference-facade -j$(sysctl -n hw.ncpu) && " +
            "mkdir -p ${outputDir.absolutePath} && " +
            "find build/$hostPreset -name '*.a' | xargs libtool -static -o " +
            "${outputDir.absolutePath}/libkoinference-facade.a",
    )

    inputs.files(fileTree("native/facade"))
    inputs.file("native/CMakeLists.txt")
    outputs.file(File(outputDir, "libkoinference-facade.a"))

    onlyIf { org.gradle.internal.os.OperatingSystem.current().isMacOsX }
}
