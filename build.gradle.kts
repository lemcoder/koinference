import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.mavenPublish) apply false
}

// Publishing is configured here rather than in each module: the coordinates, POM and signing are
// identical everywhere except for the artifact id, which each module sets through its own
// gradle.properties (POM_ARTIFACT_ID) — the plugin reads those itself.
subprojects {
    // Keyed off the Kotlin plugin so that `:backends`, the container project include() creates and
    // which has no build file of its own, does not publish an empty artifact called "backends".
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        apply(plugin = "com.vanniktech.maven.publish")

        extensions.configure<MavenPublishBaseExtension> {
            // Central Portal; since 0.34 it is the plugin's only target and takes no argument,
            // and the credentials the publish workflow passes are portal tokens.
            publishToMavenCentral()

            // Central rejects unsigned artifacts. The workflow supplies the key in memory
            // (ORG_GRADLE_PROJECT_signingInMemoryKey); a local publish without one is left
            // unsigned rather than failing at configuration time.
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }
        }
    }
}
