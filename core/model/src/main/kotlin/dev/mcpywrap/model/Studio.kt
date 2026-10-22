package dev.mcpywrap.model

object WelcomeTemplate {
    const val CLIENT_PATH = "behavior_pack/WelcomeDemo/client.py"
    val paths = listOf(CLIENT_PATH, "behavior_pack/WelcomeDemo/modMain.py", "behavior_pack/manifest.json", "behavior_pack/WelcomeDemo/__init__.py")
    val files: Map<String, String> by lazy {
        // An archive preserves names such as __init__.py which APK resource filtering omits.
        val result = linkedMapOf<String, String>()
        val input = requireNotNull(javaClass.getResourceAsStream("/welcome-template.zip"))
        java.util.zip.ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name in paths) result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        require(result.keys.containsAll(paths)) { "Incomplete welcome template" }
        paths.associateWith { result.getValue(it) }
    }
}
