package net.easecation.mcpywrap

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mcpywrap.application.ApplicationOperations
import dev.mcpywrap.studio.StudioViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in real engine test: the driver uses System UI's expand control, not showGame(). */
class PipExpansionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun systemExpansionKeepsGameInForeground() {
        val operations = ApplicationOperations.get(compose.activity.application)
        val repo = operations.repository
        val vm = ViewModelProvider(compose.activity)[StudioViewModel::class.java]
        fun waitFor(label: String, seconds: Int = 60, test: () -> Boolean) {
            val end = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < end) {
                if (test()) return
                Thread.sleep(200)
            }
            error("Timed out: $label; ${repo.sessionStatus()}")
        }
        check(!repo.active()) { "Do not interrupt an existing game" }
        waitFor("workspace") { vm.state.value.workspaceLoaded && vm.state.value.runtimeReady && !vm.state.value.busy }
        val fixture = InstrumentationRegistry.getArguments().getString("project")
        val project = if (fixture != null) {
            require(fixture.startsWith("QA-BackNavigation-") || fixture.startsWith("QA-PipExpansion-"))
            repo.projects().single { it.folder == fixture }
        } else repo.createProject("QA-PipExpansion-${System.currentTimeMillis()}")
        val previous = vm.state.value.smallWindow
        val evidence = File(repo.root, "logs/pip-expansion").apply { mkdirs() }
        val done = File(evidence, "driver-complete").apply { delete() }
        File(evidence, "driver-ready").delete()
        val previousSession = repo.session()?.optString("session")
        try {
            compose.runOnUiThread { vm.setSmallWindow(true); vm.run(project, repo.defaultWorld(project)) }
            waitFor("initial PiP", 90) {
                repo.session()?.optString("session") != previousSession && repo.session()?.optString("projectId") == project.id &&
                    repo.sessionStatus()?.let { it.optBoolean("pip") && it.optString("phase") in listOf("loading", "running") } == true
            }
            File(evidence, "initial.json").writeText(repo.sessionStatus().toString())
            File(evidence, "driver-ready").writeText(repo.session()!!.getString("session"))
            waitFor("system expansion driver", 600) { done.isFile && done.length() > 0 }
            assertEquals("passed", done.readText().trim())
            assertEquals("running", repo.sessionStatus()!!.getString("phase"))
        } finally {
            compose.runOnUiThread { vm.setSmallWindow(previous) }
            if (repo.session()?.optString("projectId") == project.id && repo.active()) {
                runBlocking { operations.mutate { operations.stopCurrent() } }
            }
        }
        assertEquals("saved", repo.sessionStatus()!!.getString("phase"))
    }
}
