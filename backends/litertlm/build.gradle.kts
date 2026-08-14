import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKmpLibrary)
}

// macOS arm64 and Android. The other Apple targets have presets ready in
// native/CMakePresets.json but are untried; Linux needs a third runtime source
// (libLiteRt.so) and is not wired up. Adding a native target here without building its
// facade first fails at link with undefined koilm_*.
kotlin {
    // Android takes no part in the native build. The AAR carries its own liblitertlm_jni.so
    // and exposes only JNI entry points, so there is nothing for CMake to link and no
    // externalNativeBuild block — unlike :backends:llamacpp, where Android is a JNI leg over
    // the shared C facade.
    android {
        namespace = "io.github.lemcoder.koinference.litertlm"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()

        // On-device is the only place the AAR's .so is actually loaded and the only place
        // generation can be proven; everything else about Android is structural.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    listOf<KotlinNativeTarget>(
        macosArm64(),
    ).forEach { target ->
        val main = target.compilations["main"]

        main.cinterops.create("koinferenceLiteRtLm") {
            definitionFile.set(file("src/nativeInterop/cinterop/koinference_litertlm.def"))
            compilerOpts("-I${projectDir}/native/facade")
        }

        // Keyed by the CMake preset name, which is also the Kotlin target name here —
        // unlike :backends:llamacpp, whose prebuilt/ is keyed by konanTarget.name because
        // CI lays those archives out that way. This module's libraries come straight from
        // the CMake build tree, so they follow the preset.
        val libDir = findProperty("koiLiteRtLmLibDir")?.toString()
            ?: file("native/build/${target.name}").path

        // Two libraries, not one: the facade archive plus the prebuilt runtime it calls
        // into. A .a records nothing about the dylib it needs, so the dylib has to be named
        // here. CMake stages it alongside the archive so a single -L covers both, and the
        // rpath is what lets the linked binary find the dylib at run time.
        val linkerOptions = listOf(
            "-L$libDir",
            "-lkoinference-litertlm-facade",
            "-lCLiteRTLM_mac",
            "-rpath", libDir,
        )

        main.compileTaskProvider.configure {
            compilerOptions {
                freeCompilerArgs.addAll("-linker-options", linkerOptions.joinToString(" "))
            }
        }

        // The klib carries the options above for whoever links it, but the test executable
        // is linked by this project and gets them separately — without this the test binary
        // fails with undefined koilm_*.
        target.binaries.all { linkerOpts(linkerOptions) }
    }

    sourceSets {
        commonMain.dependencies {
            // api: TextRuntime is a supertype of LiteRtLmTextRuntime and GenerationConstraint
            // appears in its signature, so a consumer cannot call generateResponse without
            // :core on the compile classpath.
            api(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
        }
        // Only the facade's replies need parsing; the Android SDK hands back a typed Message.
        nativeMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        // api, not implementation: LiteRtLmRuntime is created from types this brings in, and
        // a consumer that never sees the AAR gets a NoClassDefFoundError at first generate.
        androidMain.dependencies {
            api(libs.litertlm.android)
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

// The facade has to exist before Kotlin/Native links against it. Unlike the llama.cpp
// module there is no Konan plugin task to hang this off, so drive CMake directly.
val cmakeExecutable: String = findProperty("koiCmake")?.toString()
    ?: System.getenv("CMAKE")
    ?: "cmake"

val buildFacade by tasks.registering(Exec::class) {
    group = "interop"
    description = "Configure and build the LiteRT-LM facade for macOS arm64."
    workingDir = file("native")
    // Configure is idempotent and cheap once the prebuilt is cached, so both steps run
    // together rather than as two tasks that could drift apart.
    commandLine(
        "sh", "-c",
        "$cmakeExecutable --preset macosArm64 && " +
            "$cmakeExecutable --build --preset macosArm64",
    )
    inputs.files(fileTree("native/facade"))
    inputs.file("native/CMakeLists.txt")
    outputs.dir("native/build/macosArm64")
}

tasks.matching { it.name == "cinteropKoinferenceLiteRtLmMacosArm64" }.configureEach {
    dependsOn(buildFacade)
}
tasks.matching { it.name == "compileKotlinMacosArm64" || it.name == "linkDebugTestMacosArm64" }
    .configureEach { dependsOn(buildFacade) }
