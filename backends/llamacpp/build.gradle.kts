@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import io.github.lemcoder.interop.jvmInterops
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
    jvm()

    // Android runs on ART, so it takes the JNI leg like the JVM does — not androidNative*, which is
    // Kotlin/Native and would need konan to link the CMake/NDK archive itself.
    android {
        namespace = "io.github.lemcoder.koinference.llamacpp"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    listOf<KotlinNativeTarget>(
        iosArm64(),
        iosSimulatorArm64(),
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

        main.compileTaskProvider.configure {
            compilerOptions {
                freeCompilerArgs.addAll("-linker-options", "-L$libDir -lkoinference-facade")
            }
        }
    }

    jvm().compilations["main"].jvmInterops {
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

    // Same bindings for Android, generated into androidMain. The library itself is built per ABI,
    // which the external build cannot express yet.
    targets.getByName("android").compilations.getByName("main").jvmInterops {
        create("koinferenceAndroid") {
            defFile(project.file("src/nativeInterop/cinterop/koinference.def"))
            packageName.set("io.github.lemcoder.koinference.llamacpp.jni")
            includeDirs.from(file("native/facade"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The bridges resolve the stub library from java.library.path. The interop reports where its build
// put the library; CI builds it once in the natives job and passes the directory with -PkoiStubDir=.
val prebuiltStubDir: String? = findProperty("koiStubDir")?.toString()
val interopLibraryDir = kotlin.jvm().compilations["main"].jvmInterops
    .getByName("koinference").resolvedLibraryDirectory

tasks.named<Test>("jvmTest") {
    if (prebuiltStubDir == null) dependsOn("cmakeBuildKoinference")
    systemProperty(
        "java.library.path",
        prebuiltStubDir ?: interopLibraryDir.get().asFile.absolutePath,
    )
}
