package dev.mcpywrap.android

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import dev.mcpywrap.workspace.*
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ProductFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val repo=WorkspaceRepository()
    private fun ready(text:String){compose.waitUntil(30000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
    private fun tap(text:String){ready(text);compose.waitUntil(30000){compose.onAllNodesWithTag("operation-progress").fetchSemanticsNodes().isEmpty()};compose.onAllNodesWithText(text).onLast().performClick()}
    private fun create():String {
        val name="QA-${System.nanoTime()}"
        tap("欢迎语示例");compose.onNodeWithText("我的欢迎语").performTextReplacement(name);tap("确定");ready("behavior_pack");compose.waitUntil(30000){compose.onAllNodesWithTag("operation-progress").fetchSemanticsNodes().isEmpty()};return name
    }
    private fun editor(){tap("behavior_pack");tap("WelcomeDemo");tap("client.py");compose.waitUntil(15000){compose.onAllNodesWithTag("source-editor").fetchSemanticsNodes().isNotEmpty()}}
    private fun screenshot(name:String){val dir=File("/sdcard/mcpy/logs/product-evidence").apply{mkdirs()};InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let{bitmap->File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}}
    @Test fun createNavigateEditAndPersistActualSource(){
        val name=create();editor()
        onView(isAssignableFrom(CodeEditor::class.java)).perform(object:ViewAction{
            override fun getConstraints():Matcher<View> = isAssignableFrom(CodeEditor::class.java)
            override fun getDescription()="Edit the real Sora document"
            override fun perform(ui:UiController,view:View){val editor=view as CodeEditor;assertTrue(editor.editorLanguage.javaClass.name.contains("TextMate"));editor.setText(editor.text.toString().replace("欢迎来到我的世界！","PHONE_SOURCE_QA"));ui.loopMainThreadUntilIdle()}
        })
        tap("保存");ready("已保存到公共目录")
        compose.waitUntil(30000){compose.onAllNodesWithTag("operation-progress").fetchSemanticsNodes().isEmpty()}
        compose.waitUntil(15000){var styled=false;onView(isAssignableFrom(CodeEditor::class.java)).check{v,_->val spans=(v as? CodeEditor)?.styles?.spans?.read()?.getSpansOnLine(1);styled=spans!=null&&spans.size>1};styled}
        onView(isAssignableFrom(CodeEditor::class.java)).check{v,_->
            val e=v as CodeEditor
            val spans=e.styles!!.spans.read().getSpansOnLine(1)
            val colors=spans.map{e.colorScheme.getColor(it.foregroundColorId)}
            File("/sdcard/mcpy/logs/product-evidence/highlighting.txt").writeText(spans.joinToString{ "${it.column}:${it.foregroundColorId}:${e.colorScheme.getColor(it.foregroundColorId)}" })
            assertTrue("Python tokens must have different visible colors: $colors",colors.distinct().size>1)
        }
        screenshot("source-editor")
        assertTrue(repo.open(name,"behavior_pack/WelcomeDemo/client.py").text.contains("PHONE_SOURCE_QA"))
        assertTrue(File(repo.projectDir(name),"behavior_pack/WelcomeDemo/__init__.py").exists())
    }
    @Test fun externalEditProducesConflictAndKeepsBothCopies(){
        val name=create();editor()
        onView(isAssignableFrom(CodeEditor::class.java)).perform(object:ViewAction{
            override fun getConstraints():Matcher<View> = isAssignableFrom(CodeEditor::class.java)
            override fun getDescription()="Edit document"
            override fun perform(ui:UiController,view:View){(view as CodeEditor).setText("# draft");ui.loopMainThreadUntilIdle()}
        })
        val file=File(repo.projectDir(name),"behavior_pack/WelcomeDemo/client.py");file.writeText("# external")
        tap("保存");ready("需要处理");assertEquals("# external",file.readText());screenshot("save-conflict")
    }
    @Test fun fileManagerCreatesRenamesDeletesAndRestores(){
        val name=create();compose.onNodeWithContentDescription("新建文件或文件夹").performClick();tap("新建文件")
        compose.onNodeWithText("script.py").performTextReplacement("notes.py");tap("确定");ready("notes.py")
        compose.onNodeWithContentDescription("notes.py 的操作").performClick();tap("重命名 / 移动");compose.onNode(hasSetTextAction() and hasText("notes.py")).performTextReplacement("renamed.py");tap("确定");ready("renamed.py")
        compose.onNodeWithContentDescription("renamed.py 的操作").performClick();tap("移到回收站");tap("移走");tap("撤销");ready("已恢复")
        assertTrue(File(repo.projectDir(name),"renamed.py").isFile);screenshot("file-manager")
    }
    @Test fun realGitCloneRunsOnDeviceAndDoesNotExecuteBuild(){
        val name="QA-Git-${System.nanoTime()}"
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val project=runBlocking{ProjectImporter(context,repo).git("https://github.com/octocat/Hello-World.git",name){}}
        assertTrue(File(repo.projectDir(project.folder),"README").isFile)
        assertTrue(File(repo.projectDir(project.folder),".git/HEAD").isFile)
        assertTrue(repo.packs(project.folder).isEmpty())
    }
    @Test fun settingsExposeSharedCoreAndExplicitUpdateCheck(){
        tap("设置")
        ready("工程核心")
        compose.onNodeWithText("工程核心").performScrollTo().assertIsDisplayed()
        ready("mcpywrap-core 0.6.0")
        compose.onNodeWithText("mcpywrap-core 0.6.0").assertIsDisplayed()
        compose.onNodeWithText("检查更新").performScrollTo().assertIsDisplayed()
        screenshot("engineering-settings")
    }
    @Test fun exportedTemplateImportsOnDeviceWithoutLosingInit(){
        val source=repo.createProject("QA-Export-${System.nanoTime()}")
        val zip=repo.export(source.folder);val target=zip.inputStream().use{repo.importZip(it,"QA-Import-${System.nanoTime()}")}
        assertEquals(repo.open(source.folder,"behavior_pack/WelcomeDemo/client.py").text,repo.open(target.folder,"behavior_pack/WelcomeDemo/client.py").text)
        assertTrue(File(repo.projectDir(target.folder),"behavior_pack/WelcomeDemo/__init__.py").isFile)
    }
    @Test fun renameAndTrashWholeProjectCanBeUndone(){
        val old=create();val renamed=old+"-renamed"
        compose.onNodeWithContentDescription("作品操作").performClick();tap("重命名作品")
        compose.onNode(hasSetTextAction() and hasText(old)).performTextReplacement(renamed);tap("确定")
        ready(renamed);compose.waitUntil(15000){repo.projects().any{it.folder==renamed}}
        compose.onNodeWithContentDescription("作品操作").performClick();tap("移到回收站");tap("移走");tap("撤销");ready("已恢复")
        assertTrue(repo.projects().any{it.folder==renamed});assertFalse(repo.projects().any{it.folder==old})
    }

    @Test fun instancesAreCreatedWithinProjectDeletedAndRestored(){
        compose.onAllNodesWithText("世界").assertCountEquals(0)
        compose.onAllNodesWithText("新建测试实例").assertCountEquals(0)
        val name=create();val project=repo.projects().first{it.folder==name}
        compose.onNodeWithTag("project-instances").performClick();tap("新建测试实例")
        compose.onNode(hasSetTextAction() and hasText("开发测试1")).performTextReplacement("场景一");tap("确定");ready("场景一")
        compose.waitUntil(30000){compose.onAllNodesWithTag("operation-progress").fetchSemanticsNodes().isEmpty()}
        val world=repo.worlds(project).single();assertEquals(project.id,world.projectId)
        val save=File(repo.worldDir(world.folder),"minecraftWorlds/local-world/level.dat");save.parentFile.mkdirs();save.writeText("device-save-fixture")
        screenshot("project-instances")
        compose.onNodeWithTag("delete-instance-${world.id}").performClick();tap("移到回收站")
        compose.waitUntil(30000){!repo.worldDir(world.folder).exists()}
        assertTrue(File(repo.projectDir(project.folder),"behavior_pack/WelcomeDemo/client.py").exists())
        val trash=repo.trashItems().first{it.label.contains(name)&&it.label.contains("场景一")}
        compose.waitUntil(30000){compose.onAllNodesWithTag("operation-progress").fetchSemanticsNodes().isEmpty()}
        compose.onAllNodesWithContentDescription("Dismiss").fetchSemanticsNodes().takeIf{it.isNotEmpty()}?.let{compose.onAllNodesWithContentDescription("Dismiss").onLast().performClick()}
        tap("设置");compose.onNodeWithText("回收站（",substring=true).performScrollTo().performClick()
        compose.waitUntil(30000){compose.onAllNodesWithTag("restore-trash-${trash.id}").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("restore-trash-${trash.id}").performScrollTo().performClick()
        compose.waitUntil(30000){save.exists()};assertEquals("device-save-fixture",save.readText())
        tap("返回");tap("作品");ready("场景一")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        val other=create();val second=repo.projects().first{it.folder==other}
        compose.onNodeWithTag("project-instances").performClick();ready("新建测试实例")
        compose.onAllNodesWithText("场景一").assertCountEquals(0);assertTrue(repo.worlds(second).isEmpty())
    }

}
