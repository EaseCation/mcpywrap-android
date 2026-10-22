pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "mcpywrap-android"
include(":app", ":core:model", ":core:designsystem", ":feature:studio")

include(":core:workspace", ":feature:editor", ":runtime-core", ":game-host", ":game-plugin", ":engine-contract")
include(":core:engineering")
