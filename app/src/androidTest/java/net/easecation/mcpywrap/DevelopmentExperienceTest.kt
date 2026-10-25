package net.easecation.mcpywrap

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import dev.mcpywrap.bridge.HostController
import dev.mcpywrap.bridge.HostService
import dev.mcpywrap.design.McpyTheme
import dev.mcpywrap.studio.SessionLogScreen
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class DevelopmentExperienceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application
    private fun waitFor(check:()->Boolean)=compose.waitUntil(30000,check)
    @Test fun defaultServiceStartsOnResumeAndExplicitPauseSurvivesResume(){
        val prefs=app.getSharedPreferences("host",0)
        val existed=prefs.contains("development-enabled");val previous=prefs.getBoolean("development-enabled",true)
        val host=HostController.get(app)
        try {
            compose.runOnUiThread{HostService.stop(app)}
            waitFor{!host.online}
            prefs.edit().remove("development-enabled").commit()
            compose.activityRule.scenario.recreate()
            waitFor{host.online}
            assertEquals("http://127.0.0.1:${host.port}",host.endpoint)
            val connection=URL(host.endpoint+"/v2/projects").openConnection() as HttpURLConnection
            try{assertEquals(401,connection.responseCode)}finally{connection.disconnect()}
            compose.runOnUiThread{HostService.stop(app)}
            waitFor{!host.online}
            compose.activityRule.scenario.recreate();compose.waitForIdle()
            assertFalse(HostService.canResume(app));assertFalse(host.online)
        }finally{
            prefs.edit().apply{if(existed)putBoolean("development-enabled",previous)else remove("development-enabled")}.commit()
            if(previous)compose.runOnUiThread{HostService.start(app)}
        }
    }
    @Test fun logsHaveChannelsHighlightFiltersPauseAndResume(){
        val dir=File("/sdcard/mcpy/logs/session-log-ui-${System.nanoTime()}").apply{mkdirs()}
        fun record(text:String,side:String,time:Long)=JSONObject().put("text",text+"\n").put("side",side).put("time",time).put("stream","stdout").toString()+"\n"
        val file=File(dir,"game.jsonl")
        file.writeText(record("[Welcome] 客户端启动","client",1000)+record("[WARN] 测试警告","client",2000)+record("[ERROR] 服务端测试错误","server",3000))
        File(dir,"runtime.log").writeText("4000 GameActivity initialized\n")
        try {
            compose.runOnUiThread{compose.activity.setContent{McpyTheme{SessionLogScreen(dir.path,{})}}}
            waitFor{compose.onAllNodesWithText("GameActivity initialized",substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("客户端").performClick()
            compose.onNodeWithText("[Welcome] 客户端启动").assertExists()
            compose.onNodeWithText("[ERROR] 服务端测试错误").assertDoesNotExist()
            compose.onNodeWithTag("logs-search").performTextInput("测试")
            compose.onNodeWithTag("logs-search").performImeAction()
            compose.onNodeWithText("仅匹配").performClick()
            compose.onNodeWithText("[Welcome] 客户端启动").assertDoesNotExist()
            val annotated=compose.onNodeWithText("[WARN] 测试警告").fetchSemanticsNode().config[SemanticsProperties.Text].first()
            assertTrue("Visible search highlight must have background color",annotated.spanStyles.any{it.start==7&&it.end==9&&it.item.background!=androidx.compose.ui.graphics.Color.Unspecified})
            val evidence=File("/sdcard/mcpy/logs/product-evidence").apply{mkdirs()}
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let{bitmap->File(evidence,"log-search.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
            compose.onNodeWithTag("logs-pause").performClick()
            compose.waitForIdle();Thread.sleep(1400)
            file.appendText(record("测试新增","client",5000));Thread.sleep(1500)
            compose.onNodeWithText("测试新增").assertDoesNotExist()
            compose.onNodeWithTag("logs-pause").performClick()
            waitFor{compose.onAllNodesWithText("测试新增").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("清除搜索").performClick()
            compose.onNodeWithText("引擎").performClick()
            compose.onNodeWithText("GameActivity initialized").assertExists()
        }finally{dir.deleteRecursively()}
    }
    @Test fun bottomButtonReturnsToLatestWithoutClearingSearchOrResumingPausedReader(){
        val dir=File("/sdcard/mcpy/logs/session-log-scroll-${System.nanoTime()}").apply{mkdirs()}
        File(dir,"runtime.log").writeText((1..120).joinToString(""){"${it*1000} scroll-proof-$it\n"})
        try {
            compose.runOnUiThread{compose.activity.setContent{McpyTheme{SessionLogScreen(dir.path,{})}}}
            waitFor{compose.onAllNodesWithText("scroll-proof-120",substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("logs-pause").performClick()
            compose.onNodeWithTag("logs-list").performScrollToIndex(0)
            compose.onNodeWithTag("logs-bottom").performClick()
            compose.onNodeWithText("[引擎] scroll-proof-120").assertIsDisplayed()
            compose.onNodeWithContentDescription("继续更新").assertExists()
        }finally{dir.deleteRecursively()}
    }

}
