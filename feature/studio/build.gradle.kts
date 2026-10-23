plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "dev.mcpywrap.studio"; compileSdk = 36
    defaultConfig { minSdk = 30 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:application"))
    implementation(project(":core:bridge"))
    testImplementation("junit:junit:4.13.2")
    implementation(project(":core:engineering"))
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation(project(":core:workspace")); implementation(project(":feature:editor")); implementation(project(":runtime-core")); implementation(project(":core:model")); implementation(project(":core:designsystem"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
}
