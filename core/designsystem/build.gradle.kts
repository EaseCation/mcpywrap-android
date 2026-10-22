plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "dev.mcpywrap.design"; compileSdk = 36
    defaultConfig { minSdk = 30 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    api(platform("androidx.compose:compose-bom:2025.10.01"))
    api("androidx.compose.material3:material3:1.4.0")
    api("androidx.compose.material:material-icons-core:1.7.8")
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
}
