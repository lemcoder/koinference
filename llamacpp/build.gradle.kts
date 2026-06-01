import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// Directory where CMake places the compiled koinference-facade static lib.
// Pass after running CMake: ./gradlew :llamacpp:build -PkoiLibDir=/path/to/cmake/output
val koiLibDir: String = findProperty("koiLibDir")?.toString()
    ?: "${projectDir}/cpp/build/facade"

val koiFacadeHeader: String = "${projectDir}/cpp/facade"

kotlin {
    jvm()
    androidNativeArm64()
    androidNativeX64()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    macosX64()

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations["main"].apply {
            // cinterop: parse the header and generate Kotlin bindings.
            // Does NOT require the .a to exist at this stage — only the header.
            cinterops {
                create("koinference") {
                    defFile(project.file("src/nativeInterop/koinference.def"))
                    compilerOpts("-I$koiFacadeHeader")
                }
            }

            // Link the static facade library when compiling a native binary.
            // The .a must exist at koiLibDir by the time a binary is linked.
            kotlinOptions.freeCompilerArgs += listOf(
                "-linker-options", "-L$koiLibDir -lkoinference-facade"
            )
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":library"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
