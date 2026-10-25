package net.easecation.mcpywrap

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.mcpywrap.studio.StudioViewModel
import dev.mcpywrap.workspace.WorkspaceRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Run on the dedicated upgraded emulator containing a saved 0.2 runtime test world. */
class LegacyInstanceRuntimeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val repo=WorkspaceRepository()
    private fun await(label:String,seconds:Int=180,check:()->Boolean){
        val end=System.currentTimeMillis()+seconds*1000
        while(System.currentTimeMillis()<end){if(check())return;Thread.sleep(1000)}
        error("Timed out: $label; ${repo.sessionStatus()}")
    }
    @Test fun migratedSavedInstanceRunsAndSaves(){
        val candidates=repo.projects().filter{it.name.startsWith("手机运行验收-")}.flatMap{p->repo.worlds(p).map{p to it}}
        val target=candidates.firstOrNull{(_,w)->
            val dir=repo.worldDir(w.folder)
            JSONObject(File(dir,"world.json").readText()).has("migratedFrom") && File(dir,"minecraftWorlds/local-world/level.dat").isFile
        }
        assumeTrue("Needs a saved legacy fixture",target!=null)
        val (project,world)=target!!
        val source=File(repo.projectDir(project.folder),"behavior_pack/WelcomeDemo/client.py")
        val before=WorkspaceRepository.digest(source)
        var model:StudioViewModel?=null
        compose.activityRule.scenario.onActivity{model=ViewModelProvider(it)[StudioViewModel::class.java]}
        val vm=model!!
        await("ready",30){!vm.state.value.busy&&vm.state.value.runtimeReady&&!repo.active()}
        compose.runOnUiThread{vm.run(project,world)}
        await("launch",30){!vm.state.value.busy};assertNull(vm.state.value.error)
        await("world HUD"){repo.sessionStatus()?.let{it.optString("phase")=="running"&&it.optString("screen")=="hud_screen"}==true}
        assertEquals(world.id,repo.session()!!.getString("instanceId"))
        assertEquals(project.id,repo.session()!!.getString("projectId"))
        repo.requestStop();await("saved",90){repo.sessionStatus()?.optString("phase")=="saved"}
        assertEquals(before,WorkspaceRepository.digest(source))
        File("/sdcard/mcpy/logs/product-evidence/legacy-instance-runtime.json").writeText(JSONObject().put("instanceId",world.id).put("projectId",project.id).put("folder",world.folder).put("worldReady",true).put("saved",true).put("sourceUnchanged",true).toString(2))
    }
}
