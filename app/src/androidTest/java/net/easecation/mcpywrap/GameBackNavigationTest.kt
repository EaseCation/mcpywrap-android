package net.easecation.mcpywrap

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mcpywrap.application.ApplicationOperations
import dev.mcpywrap.studio.StudioViewModel
import dev.mcpy.runtime.RuntimeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import org.json.JSONObject

/** Opt-in engine regression. Never run against an existing user session. */
class GameBackNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun backOpensPauseAndResumesWithoutEndingSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val operations = ApplicationOperations.get(compose.activity.application)
        val repo = operations.repository
        val vm = ViewModelProvider(compose.activity)[StudioViewModel::class.java]
        fun waitFor(label: String, seconds: Int = 60, predicate: () -> Boolean) {
            val until = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < until) {
                if (predicate()) return
                Thread.sleep(250)
            }
            error("Timed out: $label; ${repo.sessionStatus()}; UI=${vm.state.value}")
        }
        check(!repo.active()) { "Do not interrupt an existing game" }
        if (InstrumentationRegistry.getArguments().getString("prepareRuntime") == "true") {
            runBlocking { RuntimeRepository(compose.activity.application).prepare { } }
            compose.runOnUiThread { vm.refresh() }
        }
        waitFor("workspace ready") { vm.state.value.workspaceLoaded && vm.state.value.runtimeReady && !vm.state.value.busy }
        val before = vm.state.value.smallWindow
        val reuse = InstrumentationRegistry.getArguments().getString("project")
        val project = if (reuse != null) {
            require(reuse.startsWith("QA-BackNavigation-")) { "Only reuse this test's fixture" }
            repo.projects().single { it.folder == reuse }
        } else repo.createProject("QA-BackNavigation-${System.currentTimeMillis()}")
        val world = repo.defaultWorld(project)
        val evidence = File(repo.root, "logs/product-evidence").apply { mkdirs() }
        try {
            compose.runOnUiThread { vm.setSmallWindow(false); vm.run(project, world) }
            waitFor("game running", 300) { repo.session()?.optString("projectId") == project.id && repo.sessionStatus()?.optString("phase") == "running" }
            val session = repo.session()!!.getString("session")
            fun pressBack() = instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            pressBack()
            waitFor("native pause menu") { repo.sessionStatus()?.optString("screen")?.contains("pause") == true }
            assertEquals("running", repo.sessionStatus()!!.getString("phase"))
            assertFalse(File(session, "save-exit.complete").exists())
            instrumentation.uiAutomation.takeScreenshot()!!.let { image ->
                File(evidence, "back-native-pause.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            pressBack()
            waitFor("return to game") { repo.sessionStatus()?.optString("screen") == "hud_screen" }
            assertEquals(session, repo.session()!!.getString("session"))
            assertEquals(world.id, repo.session()!!.getString("instanceId"))
            assertFalse(File(session, "save-exit.complete").exists())
            File(evidence, "back-resumed.json").writeText(repo.sessionStatus().toString())
            if (InstrumentationRegistry.getArguments().getString("inspectMenuExit") == "true") {
                File(evidence, "back-menu-exit-inspected").delete()
                pressBack()
                waitFor("pause for manual menu exit") { repo.sessionStatus()?.optString("screen")?.contains("pause") == true }
                waitFor("menu exit observation complete", 300) { File(evidence, "back-menu-exit-inspected").exists() }
            }
        } finally {
            compose.runOnUiThread { vm.setSmallWindow(before) }
            if (repo.session()?.optString("projectId") == project.id && repo.active()) {
                runBlocking { operations.mutate { operations.stopCurrent() } }
            }
        }
        assertEquals("saved", repo.sessionStatus()!!.getString("phase"))
        waitFor("game process exited") { repo.sessionStatus()?.opt("process_alive") == false }
        waitFor("editor regains focus") { compose.activity.hasWindowFocus() }
        val receipt = JSONObject(File(repo.session()!!.getString("session"), "save-exit.complete").readText())
        assertTrue(receipt.getBoolean("confirmed"))
        assertTrue(receipt.getBoolean("world_unloaded"))
        assertEquals("closed", receipt.getString("world_files"))
        File(evidence, "back-save-confirmed.json").writeText(receipt.toString(2))
    }
}
