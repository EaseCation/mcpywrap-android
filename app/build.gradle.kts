plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "dev.mcpywrap.android"; compileSdk = 36
    defaultConfig {
        applicationId = "dev.mcpywrap.android"
        minSdk = 30; targetSdk = 36; versionCode = 6; versionName = "0.5.1"; ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        getByName("debug") {
            providers.environmentVariable("MCPY_CI_DEBUG_KEYSTORE").orNull?.let { configured ->
                storeFile = file(configured)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/pluginAssets"))
    packaging { jniLibs { useLegacyPackaging = true }; resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:bridge"))
    implementation(project(":core:engineering"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(project(":runtime-core")); implementation(project(":game-host")); implementation(project(":core:workspace")); implementation(project(":feature:editor")); implementation(project(":feature:studio")); implementation(project(":core:designsystem")); implementation(project(":core:model"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    androidTestImplementation(project(":core:application"))
    androidTestImplementation("androidx.work:work-runtime-ktx:2.10.5")
    androidTestImplementation("io.github.rosemoe:editor:0.24.4")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val prepareGamePlugin by tasks.registering(Copy::class) {
    dependsOn(":game-plugin:assembleDebug")
    from(project(":game-plugin").layout.buildDirectory.file("outputs/apk/debug/game-plugin-debug.apk")) { rename { "game-adapter.apk" } }
    into(layout.buildDirectory.dir("generated/pluginAssets"))
}
tasks.named("preBuild") { dependsOn(prepareGamePlugin) }
