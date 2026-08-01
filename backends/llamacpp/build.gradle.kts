import io.github.lemcoder.KonanTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.konan)
}

// Header location — used for cinterop.
val koiFacadeHeader: String = "${projectDir}/native/facade"

// NDK prebuilt toolchains are named after the build host, and are x86_64 even on Apple silicon.
val isMacHost: Boolean = System.getProperty("os.name").lowercase().contains("mac")

// The facade itself is built by CMake (it links llama.cpp), so the Konan plugin is used for the JNI
// leg only: generate the bridges from the facade header, link the stub against the prebuilt .a.
konanConfig {
    headerDir.set("native/facade")
    libName.set("koinference-facade")
    // CMake and CI drop the per-target archives here; they are build output, not sources.
    outputDir.set("build/prebuilt")

    jvmInterop {
        packageName.set("io.github.lemcoder.koinference.llamacpp.internal.jni")
        // Set on the nested property, not via targets(...) — that helper belongs to konanConfig and
        // would switch on runKonanClang, which cannot build the C++ facade.
        // Host for the JVM target, Android ABIs for the androidNative ones. iOS has no JVM, so no
        // stub is built for it — those targets go through cinterop instead.
        targets.set(listOf(KonanTarget.host(), KonanTarget.ANDROID_ARM64))
        // The facade .a is C++ (llama.cpp + ggml), so every stub needs a C++ runtime — and on macOS
        // the frameworks ggml's Metal/BLAS backends call into. These are per-target: the Android
        // linker rejects -framework, and its libc++ is the NDK's static one.
        linkerArgsFor(
            KonanTarget.host(),
            "-lc++",
            "-framework", "Accelerate",
            "-framework", "Metal",
            "-framework", "MetalKit",
            "-framework", "Foundation",
        )
        // The Android .a is built by CMake against $ANDROID_NDK_HOME, so its C++ runtime has to come
        // from that same NDK — konan bundles a much older one, which is missing std::filesystem and
        // the iostream vtables llama.cpp pulls in.
        val ndkToolchain = providers.environmentVariable("ANDROID_NDK_HOME")
            .map { file("$it/toolchains/llvm/prebuilt/${if (isMacHost) "darwin" else "linux"}-x86_64") }
            .orNull
        if (ndkToolchain != null) {
            // libunwind ships with clang's runtime rather than the sysroot, under a versioned dir.
            val unwindDir = ndkToolchain.resolve("lib/clang").listFiles()
                ?.map { it.resolve("lib/linux/aarch64") }?.firstOrNull { it.isDirectory }
            // Named as archives rather than -L/-l: a search path would also pull this NDK's libc and
            // libdl in ahead of konan's, and lld cannot read their compressed debug sections.
            val sysrootLib = ndkToolchain.resolve("sysroot/usr/lib/aarch64-linux-android")
            linkerArgsFor(
                KonanTarget.ANDROID_ARM64,
                "${sysrootLib.resolve("libc++_static.a")}",
                "${sysrootLib.resolve("libc++abi.a")}",
                *listOfNotNull(unwindDir?.resolve("libunwind.a")?.toString()).toTypedArray(),
            )
        } else {
            logger.warn("ANDROID_NDK_HOME is not set — linkJvmInteropAndroid_arm64 will fail to find libc++.")
        }
    }
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

// The bridges resolve the stub library from java.library.path. Only the host stub is needed, so this
// deliberately skips the umbrella task — a JVM-only machine has no Android NDK.
tasks.named<Test>("jvmTest") {
    dependsOn("linkJvmInterop${KonanTarget.host().taskSuffix}")
    systemProperty(
        "java.library.path",
        layout.buildDirectory.dir("jvmInterop/jniLibs/${KonanTarget.host().abiDir}").get().asFile.absolutePath,
    )
}
