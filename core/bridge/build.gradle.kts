plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android { namespace="dev.mcpywrap.bridge";compileSdk=36
 defaultConfig{minSdk=30}
 buildFeatures{compose=true}
 compileOptions{sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
}
kotlin { jvmToolchain(17) }
dependencies {
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation(project(":core:designsystem"))
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
 implementation("androidx.savedstate:savedstate:1.3.2")
 implementation(project(":core:engineering"));implementation(project(":core:application"));implementation(project(":core:workspace"))
 implementation("androidx.core:core-ktx:1.16.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 implementation("io.ktor:ktor-server-cio:3.3.1");implementation("io.ktor:ktor-server-websockets:3.3.1")
 testImplementation("junit:junit:4.13.2");testImplementation("org.json:json:20250517")
}
