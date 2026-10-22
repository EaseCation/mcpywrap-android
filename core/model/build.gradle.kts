plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
val welcomeArchive by tasks.registering(Zip::class) {
    from(rootProject.file("demo-addon/welcome"))
    archiveFileName.set("welcome-template.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/templates"))
}
tasks.processResources { from(welcomeArchive) }
dependencies { testImplementation("junit:junit:4.13.2") }
