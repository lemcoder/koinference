import io.github.lemcoder.interop.jvmInterops
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
kotlin {
    jvm {
        compilations["main"].jvmInterops {
            // defFile defaults to src/nativeInterop/cinterop/koinference.def — the same file the
            // native targets bind below.
            create("koinference") {
                packageName.set("io.github.lemcoder.koinference.llamacpp.internal.jni")
                includeDirs.from(file("native/facade"))

                // CMake compiles and links the stub: it owns llama.cpp, so it is the only build that
                // knows the archive's transitive needs (libc++, Accelerate, Metal). The plugin passes
                // KONAN_JNI_STUB_DIR and KONAN_JNI_LIB_NAME; native/CMakeLists.txt reads them.
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
