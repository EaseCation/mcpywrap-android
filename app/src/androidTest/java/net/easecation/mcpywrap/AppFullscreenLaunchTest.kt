package net.easecation.mcpywrap

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.mcpywrap.application.ApplicationOperations
import dev.mcpywrap.studio.StudioViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppFullscreenLaunchTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun waitFor(label:String,check:()->Boolean){val end=System.currentTimeMillis()+120_000;while(System.currentTimeMillis()<end){if(check())return;Thread.sleep(250)};error("Timed out: $label")}
    @Test fun disabledDefaultSmallWindowStaysFullscreen(){
        val vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]
        val operations=ApplicationOperations.get(compose.activity.application);val repo=operations.repository
        check(!repo.active()){ "Do not interrupt an existing user game" }
        waitFor("workspace ready"){vm.state.value.workspaceLoaded&&vm.state.value.runtimeReady&&!vm.state.value.busy}
        val before=vm.state.value.smallWindow
        val project=repo.createProject("QA-Fullscreen-${System.currentTimeMillis()}")
        val world=repo.defaultWorld(project)
        try {
            compose.runOnUiThread{vm.setSmallWindow(false);vm.run(project,world)}
            waitFor("game ready"){repo.session()?.optString("projectId")==project.id&&repo.sessionStatus()?.optString("phase")=="running"}
            assertNull(vm.state.value.error)
            val status=repo.sessionStatus()!!
            assertFalse(status.getBoolean("pip"));assertEquals("activity",status.getString("launchMode"))
            assertEquals(status.getInt("bufferWidth"),status.getInt("viewWidth"))
            assertFalse(repo.session()!!.getBoolean("small_window"))
        }finally{
            compose.runOnUiThread{vm.setSmallWindow(before)}
            if(repo.session()?.optString("projectId")==project.id&&repo.active())runBlocking{operations.mutate{operations.stopCurrent()}}
        }
    }
}
