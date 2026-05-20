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
            implementation(project(":library"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
