plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("com.chaquo.python") }
android {
    namespace = "dev.mcpywrap.engineering"; compileSdk = 36
    defaultConfig { minSdk = 30; ndk { abiFilters += "arm64-v8a" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            options("--index-url", "https://pypi.org/simple")
            install("mcpywrap-core==0.6.0")
            install("click==8.1.8"); install("packaging==25.0")
            install("tomli==2.2.1"); install("tomli-w==1.2.0")
        }
    }
}
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0") }
