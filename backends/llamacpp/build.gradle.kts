import io.github.lemcoder.KonanTarget
import java.io.File
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.konan)
}

// Header location — used for cinterop.
val koiFacadeHeader: String = "${projectDir}/native/facade"

// The Konan plugin is used for generation only: it turns the facade header into the JVM bridges and
// the JNI .c stub, and CMake compiles/links that stub (see native/CMakeLists.txt, KOI_BUILD_JNI).
// Linking there rather than here keeps one toolchain end to end — konan's linker joining CMake/NDK
// artifacts is what produced the libc++, compiler-rt and framework mismatches this replaced.
konanConfig {
    headerDir.set("native/facade")
    libName.set("koinference-facade")
    // CMake and CI drop the per-target archives here; they are build output, not sources.
    outputDir.set("build/prebuilt")

    jvmInterop {
        packageName.set("io.github.lemcoder.koinference.llamacpp.internal.jni")
        // No targets: registers generateJvmInterop only, no link tasks.
        targets.set(emptyList())
    }
}

// CMake preset for the machine running the build — the only one the JVM target can load.
val hostPreset: String = System.getProperty("os.name").lowercase().let { os ->
    val arm = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }
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

// A JDK that ships include/jni.h. Not necessarily the one running Gradle: IDE-bundled JBRs (Android
// Studio's, which is the daemon JVM here) strip the headers. Override with -PkoiJniHome=/path/to/jdk.
val jniHome: String = findProperty("koiJniHome")?.toString() ?: run {
    val candidates = buildList {
        System.getenv("JAVA_HOME")?.let { add(File(it)) }
        add(File(System.getProperty("java.home")))
        File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines").listFiles()?.let { addAll(it) }
        File("/Library/Java/JavaVirtualMachines").listFiles()?.let { addAll(it) }
        File("/usr/lib/jvm").listFiles()?.let { addAll(it) }
    }
    candidates.flatMap { listOf(it, it.resolve("Contents/Home")) }
        .firstOrNull { it.resolve("include/jni.h").isFile }?.absolutePath
        ?: throw GradleException(
            "No JDK with include/jni.h found — the JNI stub cannot be compiled. Pass -PkoiJniHome=/path/to/jdk."
        )
}

val cmakeConfigureJni by tasks.registering(Exec::class) {
    group = "interop"
    description = "Configure the CMake build with the generated JNI stub enabled."
    dependsOn("generateJvmInterop")
    workingDir = nativeDir.asFile
    // CMake's FindJNI wants a full JDK; the stub only needs jni.h, which this one supplies.
    environment("JAVA_HOME", jniHome)
    commandLine(cmakeExecutable, "--preset", hostPreset, "-DKOI_BUILD_JNI=ON")
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

kotlin {
    jvm()
    androidNativeArm64()
    androidNativeX64()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    macosX64()

    targets.withType<KotlinNativeTarget>().configureEach {
        // koiLibDir overrides the prebuilt dir — useful when pointing at a local CMake output.
        // Directories are named after the Kotlin/Native target (macos_arm64, …), which is also the
        // layout konanConfig.jvmInterop expects for the static library it links the JNI stub against.
        val libDir = findProperty("koiLibDir")?.toString()
            ?: layout.buildDirectory.dir("prebuilt/${konanTarget.name}").get().asFile.path
        compilations["main"].apply {
            cinterops {
                create("koinference") {
                    defFile(project.file("src/nativeInterop/koinference.def"))
                    compilerOpts("-I$koiFacadeHeader")
                }
            }

            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.addAll("-linker-options", "-L$libDir -lkoinference-facade")
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
        }
        // Generated JNI bridges — the JVM counterpart of the native targets' cinterop bindings.
        jvmMain.configure {
            kotlin.srcDir(layout.buildDirectory.dir("generated/jvmInterop/kotlin"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The bridges resolve the stub library from java.library.path. Locally it is built on demand; CI
// builds it once in the natives job and passes the directory with -PkoiStubDir=.
val prebuiltStubDir: String? = findProperty("koiStubDir")?.toString()

tasks.named<Test>("jvmTest") {
    if (prebuiltStubDir == null) dependsOn(buildJniStub)
    systemProperty("java.library.path", prebuiltStubDir ?: hostStubDir.asFile.absolutePath)
}
