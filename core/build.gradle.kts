plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    androidNativeArm64()
    androidNativeX64()
    iosArm64()
    iosSimulatorArm64()
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
