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
            install(providers.gradleProperty("mcpyCoreWheel").orNull ?: "mcpywrap-core==${providers.gradleProperty("mcpyCoreVersion").get()}")
            install("click==8.1.8"); install("packaging==25.0")
            install("tomli==2.2.1"); install("tomli-w==1.2.0")
            install(layout.buildDirectory.file("wheels/rpds_py-0.27.1-1-cp313-cp313-android_30_arm64_v8a.whl").get().asFile.path)
            install("jsonschema==4.25.1")
        }
    }
}
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0") }

val buildRpds by tasks.registering(Exec::class) {
    inputs.file(rootProject.file("tools/build-rpds.py"))
    outputs.file(layout.buildDirectory.file("wheels/rpds_py-0.27.1-1-cp313-cp313-android_30_arm64_v8a.whl"))
    commandLine("python3.13",rootProject.file("tools/build-rpds.py"))
}
tasks.configureEach {
    if((name.startsWith("generate") || name.startsWith("install")) && name.endsWith("PythonRequirements")) {
        dependsOn(buildRpds)
        providers.gradleProperty("mcpyCoreWheel").orNull?.let { inputs.file(it) }
    }
}
