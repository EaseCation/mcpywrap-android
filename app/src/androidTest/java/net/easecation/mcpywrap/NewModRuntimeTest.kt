package net.easecation.mcpywrap

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mcpy.runtime.RuntimeRepository
import dev.mcpywrap.engineering.EngineeringClient
import dev.mcpywrap.studio.StudioViewModel
import dev.mcpywrap.workspace.WorkspaceRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 可选真实内核验收：验证客户端执行，并单独记录服务端的覆盖状态。 */
class NewModRuntimeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val repo=WorkspaceRepository()
    private fun waitFor(seconds:Int,label:String,test:()->Boolean){
        val until=System.currentTimeMillis()+seconds*1000L
        while(System.currentTimeMillis()<until){if(test())return;Thread.sleep(500)}
        error("Timed out: $label; ${repo.sessionStatus()}")
    }
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun pipButton(node:AccessibilityNodeInfo?=automation.rootInActiveWindow):AccessibilityNodeInfo? {
        if(node==null)return null
        if(node.contentDescription?.toString()=="缩小为游戏浮窗")return node
        for(i in 0 until node.childCount)pipButton(node.getChild(i))?.let{return it}
        return null
    }
    private fun buttonBounds()=Rect().also{requireNotNull(pipButton()).getBoundsInScreen(it)}
    private fun drag(from:Rect,x:Float,y:Float){
        val start=SystemClock.uptimeMillis()
        for(i in 0..12){
            val action=when(i){0->MotionEvent.ACTION_DOWN;12->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE}
            val progress=i/12f
            val event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,
                from.exactCenterX()+(x-from.exactCenterX())*progress,
                from.exactCenterY()+(y-from.exactCenterY())*progress,0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event,true));event.recycle();Thread.sleep(25)
        }
    }
    @Test fun generatedClientLoadsInRealGameAndSave(){
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        assertNotNull("Run tools/verify-runtime.sh first to prepare the official runtime",RuntimeRepository(app).installed())
        repo.initialize()
        val client=EngineeringClient(app)
        val options=JSONObject().put("title","QA-NativeMod-${System.nanoTime()}").put("modName","NativeProof")
            .put("version","1.2.3").put("scriptDir","NativeScripts").put("serverSystem","ProofServer").put("clientSystem","ProofClient")
        val preview=runBlocking{client.request("preview_project",options=options)}
        val generated=runBlocking{client.request("create_project",options=options.put("previewSignature",preview.getString("signature")))}
        val project=repo.publishImport(File(generated.getString("stage")),options.getString("title"),"")
        val proofs=File("/sdcard/mcpy/logs/product-evidence").apply{mkdirs()}
        val clientProof=File(proofs,"native-client.txt").apply{delete()}
        val serverProof=File(proofs,"native-server.txt").apply{delete()}
        for((side,type,proof) in listOf(Triple("client","ProofClient",clientProof),Triple("server","ProofServer",serverProof))){
            val file=File(repo.projectDir(project.folder),"behavior_pack/NativeScripts/$side/$type.py")
            // 只在测试生成的普通源码里加入回执；产品模板保持与桌面相同。
            file.appendText("\n        open('${proof.path}', 'w').write('$type executed')\n")
        }
        var vm:StudioViewModel?=null
        compose.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[StudioViewModel::class.java]}
        waitFor(30,"workspace ready"){!vm!!.state.value.busy&&vm!!.state.value.runtimeReady}
        compose.runOnUiThread{vm!!.run(project)}
        waitFor(40,"launch requested"){!vm!!.state.value.busy}
        assertNull(vm!!.state.value.error)
        try {
        waitFor(30,"visible loading stage"){repo.sessionStatus()?.optString("loadingStage")?.isNotBlank()==true}
        val loading=repo.sessionStatus()!!
        File(proofs,"game-loading.json").writeText(loading.toString(2))
        waitFor(240,"generated client system executed"){clientProof.isFile}
        waitFor(60,"HUD and PiP"){repo.sessionStatus()?.let{it.optString("phase")=="running"&&it.optString("screen")=="hud_screen"&&it.optBoolean("pip")}==true}
        assertEquals("",repo.sessionStatus()!!.getString("loadingStage"))
        waitFor(30,"editor observes running game"){!vm!!.state.value.busy&&vm!!.state.value.phase=="running"}
        // Let the editor finish resuming after the automatic PiP transition.
        compose.waitForIdle()
        compose.runOnUiThread{vm!!.showGame()}
        waitFor(30,"fullscreen minimize icon"){repo.sessionStatus()?.optBoolean("pip")==false&&pipButton()!=null}
        Thread.sleep(2500) // Let the system PiP transition finish before touch and visual evidence.
        val before=buttonBounds()
        val screen=Rect().also{automation.rootInActiveWindow.getBoundsInScreen(it)}
        // Move to the opposite edge to prove dragging does not trigger the click action.
        val targetX=if(before.centerX()>screen.centerX())screen.left+1f else screen.right-1f
        drag(before,targetX,screen.centerY().toFloat())
        Thread.sleep(2500)
        val moved=buttonBounds()
        assertTrue("drag moves button",kotlin.math.abs(moved.centerX()-before.centerX())>before.width())
        assertTrue("button remains within window",screen.contains(moved))
        assertFalse("drag must not minimize game",repo.sessionStatus()!!.getBoolean("pip"))
        File(proofs,"game-button-bounds.json").writeText(JSONObject().put("before",before.flattenToString()).put("after",moved.flattenToString()).toString(2))
        automation.takeScreenshot()!!.let{image->
            File(proofs,"game-minimize-button.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
            var whitePixels=0
            for(y in moved.top until moved.bottom)for(x in moved.left until moved.right){
                val color=image.getPixel(x,y)
                if(android.graphics.Color.red(color)>245&&android.graphics.Color.green(color)>245&&android.graphics.Color.blue(color)>245)whitePixels++
            }
            assertTrue("minimize glyph must actually be drawn after drag: $whitePixels white pixels",whitePixels>32)
        }
        drag(moved,moved.exactCenterX(),moved.exactCenterY()) // A real tap, with no movement.
        waitFor(30,"icon minimizes game"){repo.sessionStatus()?.optBoolean("pip")==true}
        assertNull("overlay hidden in PiP",pipButton())
        val display=repo.sessionStatus()!!
        assertEquals(display.getInt("renderWidth"),display.getInt("bufferWidth"))
        assertEquals(display.getInt("renderHeight"),display.getInt("bufferHeight"))
        waitFor(30,"editor ready after minimize"){!vm!!.state.value.busy}
        compose.runOnUiThread{vm!!.showGame()}
        waitFor(30,"button restored after PiP"){repo.sessionStatus()?.optBoolean("pip")==false&&pipButton()!=null&&buttonBounds()==moved}
        Thread.sleep(2500)
        automation.takeScreenshot()!!.let{image->File(proofs,"game-minimize-restored.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}
        } finally {
            repo.requestStop();waitFor(90,"saved"){repo.sessionStatus()?.optString("phase")=="saved"}
        }
        File(proofs,"new-mod-runtime.json").writeText(JSONObject().put("client",clientProof.readText())
            .put("serverExecutionVerified",serverProof.isFile).put("server",if(serverProof.isFile)serverProof.readText()else JSONObject.NULL)
            .put("saved",true).put("draggablePipButton",true).put("coreVersion",generated.getString("coreVersion")).put("project",project.folder).toString(2))
    }
}
