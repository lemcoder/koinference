plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
}

kotlin {
    jvm()

    androidLibrary {
        namespace = "io.github.lemcoder.koinference"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    macosArm64()
    macosX64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
