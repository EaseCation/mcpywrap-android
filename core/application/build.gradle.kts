plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace="dev.mcpywrap.application";compileSdk=36
 defaultConfig{minSdk=30}
 compileOptions{sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
}
kotlin { jvmToolchain(17) }
dependencies {
 implementation(project(":core:workspace"));implementation(project(":core:engineering"));implementation(project(":runtime-core"))
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
