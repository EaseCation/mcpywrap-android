plugins { id("com.android.application") }
android { namespace="dev.mcpy.plugin"; compileSdk=34
 defaultConfig { applicationId="dev.mcpy.plugin"; minSdk=30;targetSdk=34;versionCode=1;versionName="0.1"; ndk {abiFilters += "arm64-v8a"} }
 compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
}
dependencies { implementation(project(":game-host")) }
