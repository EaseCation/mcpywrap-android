plugins { id("com.android.library") }
android { namespace = "dev.mcpy.contract"; compileSdk = 34; defaultConfig { minSdk = 30 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
