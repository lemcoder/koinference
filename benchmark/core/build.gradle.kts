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
        // Links :backends:llamacpp, whose AAR declares 31; a lower value fails at manifest merge.
        minSdk = libs.versions.androidMinSdkLlamaCpp.get().toInt()

        // The benchmark runs here, as a device test, because AGP 9 has no Kotlin-capable
        // application plugin in *this* build — see benchmark/app. AGP builds this into a self-instrumenting
        // test APK, which is what Firebase Test Lab executes.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    macosArm64 {
        // Both facade archives travel inside their backends' cinterop klibs, and so do the
        // frameworks llama.cpp needs. What does not is LiteRT-LM's runtime, a dylib: an archive
        // records nothing about it, so every module that links a binary names it again.
        val liteRtLmLibDir = project(":backends:litertlm")
            .layout.buildDirectory.dir("cmake/library-macosArm64").get().asFile.path

        binaries.all {
            // Without the rpath the binary links and then dies at load time.
            linkerOpts("-L$liteRtLmLibDir", "-llitert-lm", "-rpath", liteRtLmLibDir)
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
        // Android only: Cera reaches its Rust engine through UniFFI over JNA, so it has no
        // Kotlin/Native leg and cannot sit in commonMain beside the other two.
        androidMain.dependencies {
            api(project(":backends:cera"))
            // ExecuTorch publishes an Android AAR only, same as Cera.
            api(project(":backends:executorch"))
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
