package net.easecation.mcpywrap

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
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
        val priorSmallWindow=app.getSharedPreferences("settings",0).getBoolean("pip",true)
        try {
        for(marker in listOf("PHONE_SOURCE_A","PHONE_SOURCE_B")){
            proof.delete();original=repo.save(project.folder,original,source(marker))
            val vm=compose.activityRule.scenario.let{var value:StudioViewModel?=null;it.onActivity{a->value=ViewModelProvider(a)[StudioViewModel::class.java]};value!!}
            compose.runOnUiThread{vm.refresh()}
            waitFor(30,"view model ready"){!vm.state.value.busy && vm.state.value.runtimeReady}
            waitFor(30,"previous session released"){!repo.active()}
            compose.runOnUiThread{vm.setSmallWindow(true);vm.run(project,world)}
            waitFor(60,"loading is presented directly in PiP"){
                repo.session()?.optString("projectId")==project.id&&repo.sessionStatus()?.let{it.optString("phase")=="loading"&&it.optBoolean("pip")&&it.optString("launchMode")=="direct_pip"&&it.optInt("viewWidth")>0&&it.optInt("bufferWidth")>0&&it.optInt("viewHeight")<it.optInt("bufferHeight")}==true
            }
            val loading=repo.sessionStatus()!!
            assertTrue(loading.getString("loadingStage").isNotBlank())
            assertFalse(repo.session()!!.getBoolean("launch_external"))
            assertTrue(loading.getInt("viewWidth")<loading.getInt("bufferWidth"))
            File(evidence,"${marker.lowercase()}-initial-pip.json").writeText(loading.toString(2))
            screenshot("${marker.lowercase()}-initial-pip")
            waitFor(60,"launch request accepted"){!vm.state.value.busy}
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
                val originalPid=repo.sessionStatus()!!.getInt("pid")
                val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
                fun find(node:android.view.accessibility.AccessibilityNodeInfo?,label:String,partial:Boolean=false):android.view.accessibility.AccessibilityNodeInfo? {
                    if(node==null)return null
                    if(node.contentDescription?.toString()==label||node.text?.toString()==label||(partial&&node.text?.toString()?.contains(label)==true))return node
                    for(i in 0 until node.childCount){find(node.getChild(i),label,partial)?.let{return it}}
                    return null
                }
                waitFor(15,"single fullscreen minimize button"){find(automation.rootInActiveWindow,"缩小为游戏浮窗")!=null}
                fun dragButtonTo(y:Float,targetX:Float?=null){
                    val bounds=android.graphics.Rect()
                    find(automation.rootInActiveWindow,"缩小为游戏浮窗")!!.getBoundsInScreen(bounds)
                    val start=android.os.SystemClock.uptimeMillis();val fromX=bounds.exactCenterX();val toX=targetX?:fromX;val from=bounds.exactCenterY()
                    fun event(action:Int,at:Float,x:Float){val motion=android.view.MotionEvent.obtain(start,android.os.SystemClock.uptimeMillis(),action,x,at,0);motion.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;assertTrue(automation.injectInputEvent(motion,true));motion.recycle()}
                    event(android.view.MotionEvent.ACTION_DOWN,from,fromX)
                    for(i in 1..20){event(android.view.MotionEvent.ACTION_MOVE,from+(y-from)*i/20,fromX+(toX-fromX)*i/20);Thread.sleep(20)}
                    event(android.view.MotionEvent.ACTION_UP,y,toX)
                }
                dragButtonTo(repo.sessionStatus()!!.getInt("viewHeight")*0.6f)
                Thread.sleep(300)
                screenshot("fullscreen-button-middle")
                val centerX=repo.sessionStatus()!!.getInt("viewWidth")/2f
                dragButtonTo(0f,centerX)
                Thread.sleep(300);screenshot("fullscreen-button-top")
                waitFor(5,"minimize button reaches fullscreen top"){
                    val bounds=android.graphics.Rect();find(automation.rootInActiveWindow,"缩小为游戏浮窗")?.getBoundsInScreen(bounds)
                    !bounds.isEmpty&&bounds.top<=24*compose.activity.resources.displayMetrics.density&&kotlin.math.abs(bounds.exactCenterX()-centerX)<bounds.width()/2
                }
                assertTrue(find(automation.rootInActiveWindow,"缩小为游戏浮窗")!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                waitFor(20,"game minimized before log page"){repo.sessionStatus()?.optBoolean("pip")==true}
                waitFor(15,"independent log page"){find(automation.rootInActiveWindow,"运行日志")!=null}
                try {
                    compose.waitUntil(30000){compose.onAllNodesWithTag("logs-list").fetchSemanticsNodes().isNotEmpty()}
                }catch(e:Throwable){screenshot("log-loading-failure");throw e}
                screenshot("fullscreen-game-logs")
                compose.onNodeWithContentDescription("返回").performClick()
                assertTrue(repo.sessionStatus()!!.getBoolean("pip"))
                compose.waitUntil(15000){compose.onAllNodesWithTag("active-session-card").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("查看日志").performClick()
                compose.onNodeWithText("运行日志").assertExists()
                compose.runOnUiThread{vm.showGame()}
                waitFor(15,"return to same full screen game"){find(automation.rootInActiveWindow,"缩小为游戏浮窗")!=null}
                assertEquals(originalPid,repo.sessionStatus()!!.getInt("pid"))
                assertEquals("running",repo.sessionStatus()!!.getString("phase"))
                screenshot("fullscreen-after-logs")
                repeat(2){cycle->
                    assertTrue(find(automation.rootInActiveWindow,"缩小为游戏浮窗")!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                    waitFor(20,"PiP after fullscreen/logs cycle"){repo.sessionStatus()?.optBoolean("pip")==true}
                    assertEquals(display.getInt("renderWidth"),repo.sessionStatus()!!.getInt("bufferWidth"))
                    assertEquals(display.getInt("renderHeight"),repo.sessionStatus()!!.getInt("bufferHeight"))
                    compose.runOnUiThread{vm.showGame()}
                    waitFor(20,"fullscreen after portrait caller"){repo.sessionStatus()?.let{!it.optBoolean("pip")&&it.optInt("viewWidth")==it.optInt("bufferWidth")}==true}
                    waitFor(10,"overlay restored"){find(automation.rootInActiveWindow,"缩小为游戏浮窗")!=null}
                    assertEquals(originalPid,repo.sessionStatus()!!.getInt("pid"))
                    Thread.sleep(1000);screenshot("rotation-cycle-$cycle")
                }
                val operations=dev.mcpywrap.application.ApplicationOperations.get(app)
                val frame=kotlinx.coroutines.runBlocking{operations.mutate{operations.game(project,session.name,"screenshot")}}
                File(frame.getString("path")).copyTo(File(evidence,"rotation-game-surface.png"),overwrite=true)
            }
            repo.requestStop();waitFor(90,"save exit"){repo.sessionStatus()?.optString("phase")=="saved"};Thread.sleep(1500)
            assertTrue(File(repo.worldDir(world.folder),"minecraftWorlds/local-world/level.dat").isFile)
        }
        } finally {
            app.getSharedPreferences("settings",0).edit().putBoolean("pip",priorSmallWindow).apply()
            if(repo.session()?.optString("projectId")==project.id&&repo.active()){
                repo.requestStop();waitFor(90,"save owned test session"){!repo.active()}
            }
        }
        File(evidence,"integration.json").writeText(JSONObject().put("project",project.folder).put("world",world.folder).put("sameWorld",true).put("sourceA",true).put("sourceB",true).put("coreDependencyExecuted",true).put("saved",true).toString(2))
    }
}
