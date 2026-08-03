import io.github.lemcoder.interop.jvmInterops
import io.github.lemcoder.jniHome
import io.github.lemcoder.jvm.GenerateJvmInteropTask
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.konan)
}

// Header location — used for cinterop.
val koiFacadeHeader: String = "${projectDir}/native/facade"

// konanConfig would compile C/C++ to a .a, which is CMake's job here — llama.cpp is far past what a
// flat source list can express — so the block stays empty and only the interop leg is used.

// CMake preset for the machine running the build — the only one the JVM target can load.
val hostPreset: String = System.getProperty("os.name").lowercase().let { os ->
    val arm = System.getProperty("os.arch").lowercase()
        .let { it.contains("aarch64") || it.contains("arm64") }
    when {
        os.contains("mac") -> if (arm) "macosArm64" else "macosX64"
        else -> "linuxX64"
    }
}
val nativeDir = layout.projectDirectory.dir("native")
val hostStubDir = nativeDir.dir("build/$hostPreset")

// The Gradle daemon does not inherit a login shell's PATH, so a Homebrew cmake is invisible to it.
// Override with -PkoiCmake=/path/to/cmake or $CMAKE.
val cmakeExecutable: String = findProperty("koiCmake")?.toString()
    ?: System.getenv("CMAKE")
    ?: sequenceOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake", "/usr/bin/cmake")
        .firstOrNull { File(it).canExecute() }
    ?: "cmake"

kotlin {
    jvm {
        compilations["main"].jvmInterops {
            // defFile defaults to src/nativeInterop/cinterop/koinference.def — the same file the
            // native targets bind below.
            create("koinference") {
                packageName.set("io.github.lemcoder.koinference.llamacpp.internal.jni")
                includeDirs.from(file("native/facade"))
            }
        }
    }

    listOf<KotlinNativeTarget>(
        androidNativeArm64(),
        androidNativeX64(),
        iosArm64(),
        iosSimulatorArm64(),
        macosArm64(),
        macosX64(),
    ).forEach { target ->
        val main = target.compilations["main"]

        main.cinterops.create("koinference") {
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

// Where the plugin writes the generated stub. The task reports it rather than this build assuming a
// path: the layout is the plugin's to change, and a stale stub left at a previous location would be
// globbed and compiled without complaint.
val generateJni = tasks.named<GenerateJvmInteropTask>("generateJvmInteropKoinference")
val generatedStubDir: String = generateJni.get().stubSourceDirectory.get().asFile.absolutePath

// The generated bindings System.loadLibrary this exact name, so CMake has to emit it.
val stubLibraryName: String = generateJni.get().stubLibraryBaseName.get()

val cmakeConfigureJni by tasks.registering(Exec::class) {
    group = "interop"
    description = "Configure the CMake build with the generated JNI stub enabled."
    dependsOn(generateJni)
    workingDir = nativeDir.asFile
    // CMake's FindJNI wants a full JDK; the stub only needs jni.h, and the plugin already knows
    // which installed JDK has it.
    environment("JAVA_HOME", jniHome())
    commandLine(
        cmakeExecutable, "--preset", hostPreset,
        "-DKOI_BUILD_JNI=ON",
        "-DKOI_JNI_STUB_DIR=$generatedStubDir",
        "-DKOI_JNI_LIB_NAME=$stubLibraryName",
    )
}

val buildJniStub by tasks.registering(Exec::class) {
    group = "interop"
    description = "Compile and link the generated JNI stub against the facade."
    dependsOn(cmakeConfigureJni)
    workingDir = nativeDir.asFile
    commandLine(
        cmakeExecutable, "--build", "build/$hostPreset",
        "--target", "koinference-jni",
        "-j", Runtime.getRuntime().availableProcessors().toString(),
    )
}

// The bridges resolve the stub library from java.library.path. Locally it is built on demand; CI
// builds it once in the natives job and passes the directory with -PkoiStubDir=.
val prebuiltStubDir: String? = findProperty("koiStubDir")?.toString()

tasks.named<Test>("jvmTest") {
    if (prebuiltStubDir == null) dependsOn(buildJniStub)
    systemProperty("java.library.path", prebuiltStubDir ?: hostStubDir.asFile.absolutePath)
}
