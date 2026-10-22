plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.mcpywrap.workspace"; compileSdk = 36
    defaultConfig { minSdk = 30 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
