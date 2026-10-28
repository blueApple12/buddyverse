plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.mp) apply false
}

// Repositories are settings-only (FAIL_ON_PROJECT_REPOS): Node/Yarn/Binaryen for the wasm PWA come from settings.gradle.kts.
allprojects {
    plugins.whenPluginAdded {
        extensions.extensionsSchema.elements.forEach { e ->
            (extensions.findByName(e.name) as? org.jetbrains.kotlin.gradle.targets.js.EnvSpec<*>)?.downloadBaseUrl?.set(null as String?)
        }
    }
}
