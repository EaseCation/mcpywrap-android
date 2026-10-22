package dev.mcpywrap.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import dev.mcpywrap.engineering.EngineeringClient
import dev.mcpywrap.studio.StudioViewModel
import dev.mcpywrap.workspace.WorkspaceRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NewProjectFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val repo=WorkspaceRepository()
    private fun waitText(text:String){compose.waitUntil(30_000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
    private fun ready(){compose.waitUntil(30_000){compose.onAllNodes(hasTestTag("new-project-next") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}}
    private fun start(){
        waitText("新建作品")
        compose.waitUntil(30_000){compose.onAllNodesWithTag("operation-progress").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithText("新建作品").performClick();waitText("Mod 标识")
    }
    private fun field(tag:String,value:String){compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value);closeSoftKeyboard()}
    private fun snapshot(name:String){
        val image=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File("/sdcard/mcpy/logs/product-evidence/$name.png").apply{parentFile.mkdirs()}.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun customFieldsPreviewAndGenerationUseSharedCore(){
        start();val title="QA-NewMod-${System.nanoTime()}"
        field("new-project-title",title);field("new-mod-name","PhoneMod")
        compose.onNodeWithText("更多选项").performClick()
        field("new-server-system","WorldServer")
        field("new-mod-name","CrystalMod")
        compose.onNodeWithTag("new-server-system").performScrollTo().assertTextContains("WorldServer")
        compose.onNodeWithTag("new-client-system").performScrollTo().assertTextContains("CrystalModClientSystem")
        field("new-script-dir","CrystalScripts");field("new-mod-version","2.1.0")
        compose.onNodeWithTag("new-project-title").performScrollTo();snapshot("new-mod-form")
        compose.onNodeWithTag("new-project-next").performClick();waitText("确认文件结构");ready()
        compose.onNodeWithText("modMain.py").performScrollTo().assertIsDisplayed()
        assertFalse(repo.projectDir(title).exists())
        compose.onNodeWithText("WorldServer.py").assertExists()
        compose.onNodeWithText("CrystalModClientSystem.py").assertExists()
        compose.onNodeWithText("CrystalScripts").performScrollTo();snapshot("new-mod-preview")
        compose.onNodeWithTag("new-project-next").performClick()
        compose.waitUntil(30_000){repo.projectDir(title).isDirectory&&compose.onAllNodesWithTag("new-project-next").fetchSemanticsNodes().isEmpty()}
        waitText("behavior_pack")
        val root=repo.projectDir(title);val config=File(root,"behavior_pack/CrystalScripts/config.py").readText()
        assertTrue(config.contains("ModName = \"CrystalMod\""));assertTrue(config.contains("ModVersion = \"2.1.0\""))
        assertTrue(config.contains("CrystalScripts.server.WorldServer.WorldServer"))
        assertTrue(config.contains("CrystalScripts.client.CrystalModClientSystem.CrystalModClientSystem"))
        assertTrue(File(root,"pyproject.toml").isFile)
        assertTrue(File(root,"resource_pack/manifest.json").isFile)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val result=runBlocking{EngineeringClient(context).request("build",title)}
        assertTrue(File(result.getString("output"),"behavior_pack/CrystalScripts/server/WorldServer.py").isFile)
        val evidence=File("/sdcard/mcpy/logs/product-evidence/new-mod.json")
        evidence.writeText(JSONObject().put("project",title).put("coreVersion",result.getString("coreVersion")).put("previewBeforePublication",true).put("customSystemsPreserved",true).put("buildSucceeded",true).toString(2))
    }

    @Test fun invalidNameAndVersionKeepFormWithoutPublishing(){
        start();val title="QA-InvalidMod-${System.nanoTime()}";field("new-project-title",title)
        field("new-mod-name","class");compose.onNodeWithTag("new-project-next").assertIsNotEnabled()
        field("new-mod-name","GoodMod");compose.onNodeWithText("更多选项").performClick()
        field("new-mod-version","not a version");compose.onNodeWithTag("new-project-next").performClick()
        compose.waitUntil(30_000){compose.onAllNodesWithText("知道了").fetchSemanticsNodes().isNotEmpty()}
        ready()
        assertFalse(repo.projectDir(title).exists())
        compose.onNodeWithTag("new-mod-version").performScrollTo().assertTextContains("not a version")
        compose.onNodeWithContentDescription("返回新建").performClick();waitText("新建作品")
    }

    @Test fun editingPreviewRequiresNewPreviewAndCancelCreatesNothing(){
        start();val title="QA-CancelMod-${System.nanoTime()}";field("new-project-title",title)
        compose.onNodeWithTag("new-project-next").performClick();waitText("确认文件结构");ready()
        compose.onNodeWithContentDescription("返回新建").performClick();waitText("Mod 标识")
        field("new-mod-name","ChangedMod")
        compose.onNodeWithTag("new-project-next").assertTextContains("查看结构")
        compose.activityRule.scenario.recreate();waitText("Mod 标识");ready()
        compose.onNodeWithTag("new-mod-name").assertTextContains("ChangedMod")
        compose.onNodeWithContentDescription("返回新建").performClick();waitText("新建作品")
        assertFalse(repo.projectDir(title).exists())
    }
}
