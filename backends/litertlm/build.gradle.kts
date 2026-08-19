import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKmpLibrary)
}

// The facade has to exist before Kotlin/Native links against it. Unlike the llama.cpp
// module there is no Konan plugin task to hang this off, so drive CMake directly. Declared
// before the kotlin block because the native binaries below depend on it by reference.
val cmakeExecutable: String = findProperty("koiCmake")?.toString()
    ?: System.getenv("CMAKE")
    ?: "cmake"

// Same reason as :backends:llamacpp: cinterop does not treat the headers behind its compiler
// options as inputs, so a facade edit would otherwise be invisible to the up-to-date check.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.CInteropProcess>().configureEach {
    inputs.files(fileTree("native/facade"))
        .withPropertyName("facadeHeaders")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

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
    // The presets carry the build type, the deployment target and the output directory, so a
    // preset edit changes the artifacts — without this the task stays UP-TO-DATE and the old
    // ones are linked instead.
    inputs.file("native/CMakePresets.json")
    outputs.dir("native/build/macosArm64")
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

        // commonTest holds the runtime's own logic and needs no runtime to run, so it is worth
        // running on this leg too: it compiles the Android actuals against it, which is where
        // a duplicate JVM class name or a drifted SamplerConfig would show up.
        withHostTest {}

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
        //
        // Both paths are absolute and belong to this checkout, so they are right for building
        // and testing here and wrong the moment this klib is published: a consumer needs the
        // dylib shipped alongside and an rpath of their own. Publishing this target needs that
        // question answered first — -PkoiLiteRtLmLibDir only moves the problem.
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

        // The klib carries the options above for whoever links it, but every binary this
        // project links — the test executable today, a framework tomorrow — needs them
        // separately, or it fails with undefined koilm_*. The dependency goes on the link
        // task rather than on named tasks: naming them missed linkReleaseTest, which then
        // linked against a facade directory that a clean checkout has not built yet.
        target.binaries.all {
            linkerOpts(linkerOptions)
            linkTaskProvider.configure { dependsOn(buildFacade) }
        }
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

// cinterop reads the facade header, and compileKotlin* carries the -linker-options the klib
// hands on; both want the facade built. The binaries' own link tasks are wired above.
tasks.matching {
    it.name == "cinteropKoinferenceLiteRtLmMacosArm64" || it.name == "compileKotlinMacosArm64"
}.configureEach { dependsOn(buildFacade) }

// The header is what cinterop actually binds, but the task only tracks the .def that names
// it: adding a facade function and rebuilding leaves the klib without it, and the error is an
// unresolved reference in Kotlin rather than anything pointing at the interop.
tasks.matching { it.name == "cinteropKoinferenceLiteRtLmMacosArm64" }.configureEach {
    inputs.files(fileTree("native/facade"))
}
