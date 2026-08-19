plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKmpLibrary)
}

// android and macosArm64, and no others: those are the two targets where *both* engines exist.
// :backends:llamacpp reaches further (jvm, linux, iOS) but :backends:litertlm does not, and a
// target that can only run one engine cannot answer the question this harness exists to ask.
//
// Android is the deliverable — the numbers that matter come from Firebase Test Lab. macosArm64
// is where the harness itself is verified, because both engines really run there.
kotlin {
    android {
        namespace = "io.github.lemcoder.koinference.benchmark"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()

        // The benchmark runs here, as a device test, because AGP 9 has no Kotlin-capable
        // application plugin in *this* build — see benchmark/app. AGP builds this into a self-instrumenting
        // test APK, which is what Firebase Test Lab executes.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    macosArm64 {
        // Both backends' native libraries, named again here.
        //
        // A klib does not carry the linker options of the project that produced it, so every
        // module that links a binary against these backends repeats them — the same lesson as
        // the .a that records nothing about what it needs, one level further out. Without this
        // the test executable fails on undefined koi_* and koilm_*.
        val llamaCppLibDir = project(":backends:llamacpp")
            .layout.buildDirectory.dir("prebuilt/${konanTarget.name}").get().asFile.path
        val liteRtLmLibDir = project(":backends:litertlm")
            .layout.projectDirectory.dir("native/build/$name").asFile.path

        binaries.all {
            linkerOpts(
                "-L$llamaCppLibDir", "-lkoinference-facade",
                "-L$liteRtLmLibDir", "-lkoinference-litertlm-facade", "-lCLiteRTLM_mac",
                // The facade archive references the runtime dylib, which CMake stages beside
                // it; without the rpath the binary links and then dies at load time.
                "-rpath", liteRtLmLibDir,
                // ggml's Metal and BLAS backends are inside the llama.cpp archive.
                "-framework", "Metal",
                "-framework", "MetalKit",
                "-framework", "Foundation",
                "-framework", "Accelerate",
            )
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":backends:llamacpp"))
            api(project(":backends:litertlm"))
            implementation(libs.kotlinx.coroutines.core)
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

// The LiteRT-LM facade is produced by CMake rather than by any Kotlin compilation, so nothing
// in this module's task graph would otherwise cause it to exist before the link.
tasks.matching { it.name.startsWith("linkDebugTestMacosArm64") }
    .configureEach { dependsOn(":backends:litertlm:buildFacade") }
