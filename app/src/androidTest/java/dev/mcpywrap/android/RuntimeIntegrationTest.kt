package dev.mcpywrap.android

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.mcpy.runtime.*
import dev.mcpywrap.model.WelcomeTemplate
import dev.mcpywrap.studio.StudioViewModel
import dev.mcpywrap.workspace.WorkspaceRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Explicit opt-in integration test: downloads 2.16 GiB from the official CDN. */
class RuntimeIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val repo=WorkspaceRepository()
    private val evidence=File("/sdcard/mcpy/logs/product-evidence").apply{mkdirs()}
    private fun waitFor(seconds:Int,label:String,check:()->Boolean){val end=System.currentTimeMillis()+seconds*1000L;while(System.currentTimeMillis()<end){if(check())return;Thread.sleep(1000)};error("Timed out: $label; status=${repo.sessionStatus()}")}
    private fun screenshot(name:String){
        var image:Bitmap?=null
        waitFor(10,"screenshot after window transition") {image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();image!=null}
        File(evidence,"$name.png").outputStream().use{requireNotNull(image).compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun installResumeRunSourceAndSaveSameWorld(){
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val runtime=RuntimeRepository(app)
        if(runtime.installed()==null){
            RuntimeInstallWorker.start(app)
            waitFor(180,"download started"){File(app.noBackupFilesDir,"runtimes").walk().filter{it.isFile}.sumOf{it.length()}>4*1024*1024}
            RuntimeInstallWorker.pause(app)
            waitFor(30,"paused"){WorkManager.getInstance(app).getWorkInfosForUniqueWork(RuntimeInstallWorker.NAME).get().none{!it.state.isFinished}}
            val kept=File(app.noBackupFilesDir,"runtimes").walk().filter{it.isFile}.sumOf{it.length()};assertTrue(kept>0)
            File(evidence,"resume.json").writeText(JSONObject().put("retainedBytes",kept).put("downloadedOnDevice",true).toString())
            RuntimeInstallWorker.start(app)
            waitFor(2400,"runtime installation"){
                val jobs=WorkManager.getInstance(app).getWorkInfosForUniqueWork(RuntimeInstallWorker.NAME).get()
                jobs.firstOrNull{it.state==androidx.work.WorkInfo.State.FAILED}?.let{error("Installation failed: ${it.outputData}")}
                runtime.installed()!=null
            }
        }
        File(evidence,"runtime-receipt.json").writeText(File(runtime.installed()!!.directory,"ready.json").readText())
        val project=repo.createProject("手机运行验收-${System.currentTimeMillis()}")
        val dependency=repo.createProject("运行依赖验收-${System.nanoTime()}","empty")
        File(repo.projectDir(dependency.folder),"behavior_pack/CoreShared/__init__.py").apply{parentFile.mkdirs();writeText("VALUE = 'CORE_DEPENDENCY'\n")}
        File(repo.projectDir(project.folder),"pyproject.toml").writeText("""
            [project]
            name = "phone-runtime"
            version = "1.0.0"
            [tool.mcpywrap]
            target_dir = "build"
            local_dependencies = ["../${dependency.folder}"]
        """.trimIndent())
        val world=repo.defaultWorld(project)
        val proof=File(evidence,"source-execution.json")
        val base=repo.open(project.folder,WelcomeTemplate.CLIENT_PATH)
        fun source(marker:String)=base.text.replace("欢迎来到我的世界！",marker).replace("        game.SetTipMessage", "        import CoreShared\n        assert CoreShared.VALUE == 'CORE_DEPENDENCY'\n        open('/sdcard/mcpy/logs/product-evidence/source-execution.json', 'w').write('{\"marker\":\"$marker\",\"dependency\":\"CORE_DEPENDENCY\",\"event\":\"OnLocalPlayerStopLoading\"}')\n        game.SetTipMessage")
        var original=base
        for(marker in listOf("PHONE_SOURCE_A","PHONE_SOURCE_B")){
            proof.delete();original=repo.save(project.folder,original,source(marker))
            val vm=compose.activityRule.scenario.let{var value:StudioViewModel?=null;it.onActivity{a->value=ViewModelProvider(a)[StudioViewModel::class.java]};value!!}
            compose.runOnUiThread{vm.refresh()}
            waitFor(30,"view model ready"){!vm.state.value.busy && vm.state.value.runtimeReady}
            waitFor(30,"previous session released"){!repo.active()}
            compose.runOnUiThread{vm.run(project,world)}
            waitFor(30,"launch request accepted"){!vm.state.value.busy}
            check(vm.state.value.error==null){"Launch failed: ${vm.state.value.error}"}
            waitFor(240,"source event $marker"){proof.isFile&&runCatching{JSONObject(proof.readText()).getString("marker")==marker}.getOrDefault(false)}
            waitFor(30,"running"){repo.sessionStatus()?.optString("phase")=="running"}
            assertThrows(IllegalArgumentException::class.java){repo.trashWorld(project,world)}
            assertTrue(repo.worldDir(world.folder).exists())
            waitFor(30,"picture in picture with game HUD"){repo.sessionStatus()?.let{it.optBoolean("pip")&&it.optString("screen")=="hud_screen"}==true}
            waitFor(30,"editor observes running session"){!vm.state.value.busy && vm.state.value.phase=="running"}
            compose.waitForIdle();Thread.sleep(3000)
            val display=repo.sessionStatus()!!
            assertEquals(display.getInt("renderWidth"),display.getInt("bufferWidth"))
            assertEquals(display.getInt("renderHeight"),display.getInt("bufferHeight"))
            assertTrue(display.getInt("bufferWidth")>display.getInt("viewWidth"))
            screenshot(marker.lowercase())
            File(evidence,"${marker.lowercase()}-session.json").writeText(repo.sessionStatus().toString())
            File(evidence,"${marker.lowercase()}.json").writeText(proof.readText())
            val session=File(repo.session()!!.getString("session"));File(evidence,"${marker.lowercase()}-engine.json").writeText(File(session,"engine-proof.json").readText())
            if(marker=="PHONE_SOURCE_A"){
                compose.runOnUiThread{vm.showGame()}
                waitFor(30,"restore full framebuffer"){
                    repo.sessionStatus()?.let{!it.optBoolean("pip")&&it.optInt("viewWidth")>display.getInt("viewWidth")&&it.optInt("bufferWidth")==it.optInt("viewWidth")}==true
                }
                Thread.sleep(1500);screenshot("phone_source_a-fullscreen")
            }
            repo.requestStop();waitFor(90,"save exit"){repo.sessionStatus()?.optString("phase")=="saved"};Thread.sleep(1500)
            assertTrue(File(repo.worldDir(world.folder),"minecraftWorlds/local-world/level.dat").isFile)
        }
        File(evidence,"integration.json").writeText(JSONObject().put("project",project.folder).put("world",world.folder).put("sameWorld",true).put("sourceA",true).put("sourceB",true).put("coreDependencyExecuted",true).put("saved",true).toString(2))
    }
}
