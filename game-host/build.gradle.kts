plugins { id("com.android.library") }
android { namespace = "dev.mcpy.game"; compileSdk = 34; ndkVersion = "25.1.8937393"
 defaultConfig { minSdk = 30; ndk { abiFilters += "arm64-v8a" } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")

}
dependencies { compileOnly(project(":engine-contract")) }

val buildNative by tasks.registering(Exec::class) {
    inputs.file("src/main/cpp/bridge.cpp")
    inputs.file(rootProject.file("tools/build-native.sh"))
    outputs.file("src/main/jniLibs/arm64-v8a/libmcpy_bridge.so")
    commandLine("sh", rootProject.file("tools/build-native.sh"))
}
tasks.named("preBuild") { dependsOn(buildNative) }
