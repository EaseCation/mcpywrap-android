plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "dev.mcpywrap.editor"; compileSdk = 36
    defaultConfig { minSdk = 30 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(project(":core:designsystem"))
    implementation("io.github.rosemoe:editor:0.24.4")
    implementation("io.github.rosemoe:language-textmate:0.24.4")
    implementation("androidx.activity:activity-compose:1.10.1")
}
