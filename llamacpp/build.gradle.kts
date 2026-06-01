import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// Header location — used for cinterop.
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
        // koiLibDir overrides the prebuilt dir — useful when pointing at a local CMake output.
        val libDir = findProperty("koiLibDir")?.toString() ?: "${projectDir}/prebuilt/$name"
        compilations["main"].apply {
            cinterops {
                create("koinference") {
                    defFile(project.file("src/nativeInterop/koinference.def"))
                    compilerOpts("-I$koiFacadeHeader")
                }
            }
            compilerOptions.configure {
                freeCompilerArgs.addAll("-linker-options", "-L$libDir -lkoinference-facade")
            }
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
