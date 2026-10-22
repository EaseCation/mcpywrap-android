plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace = "dev.mcpy.runtime"; compileSdk = 36; defaultConfig { minSdk = 30 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }; kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.core:core-ktx:1.16.0"); implementation("androidx.work:work-runtime-ktx:2.10.5"); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0"); testImplementation("junit:junit:4.13.2"); testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0") }
