package net.easecation.mcpywrap

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import dev.mcpywrap.design.McpyTheme
import dev.mcpywrap.studio.ProductState
import dev.mcpywrap.studio.RunningSessionCard
import dev.mcpywrap.studio.StudioViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DesignSystemTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun waitReady(){
        val vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]
        compose.waitUntil(60000){vm.state.value.workspaceLoaded&&!vm.state.value.busy}
        assertNull("Workspace must load before navigation assertions",vm.state.value.error)
    }
    @Test fun primaryTabsHaveOneHeadingAndSettingsShareFullWidth(){
        waitReady();compose.onAllNodesWithText("设置").onLast().performClick()
        compose.onAllNodesWithText("设置").assertCountEquals(2) // App bar plus navigation label, no duplicate page heading.
        val runtime=compose.onNodeWithTag("settings-runtime").getUnclippedBoundsInRoot()
        val pip=compose.onNodeWithTag("settings-pip").getUnclippedBoundsInRoot()
        assertEquals(runtime.left,pip.left);assertEquals(runtime.right,pip.right)
        compose.onNodeWithTag("settings-core").performScrollTo()
        val core=compose.onNodeWithTag("settings-core").getUnclippedBoundsInRoot()
        assertEquals(runtime.left,core.left);assertEquals(runtime.right,core.right)
        compose.onAllNodesWithText("开发").onLast().performClick()
        compose.onAllNodesWithText("开发").assertCountEquals(2)
        compose.onNodeWithText("多个工具，同一份源码").assertExists()
        compose.onNodeWithText("共享作品目录 /sdcard/mcpy").assertExists()
    }
    @Test fun welcomeIsOnlyOfferedInsideNewProjectTemplates(){
        waitReady()
        compose.onNodeWithText("欢迎语示例").assertDoesNotExist()
        compose.onNodeWithText("新建作品").performClick()
        compose.onNodeWithTag("template-standard").assertExists()
        compose.onNodeWithTag("template-welcome").performClick()
        compose.onNodeWithText("我的欢迎语").assertExists()
        compose.onNodeWithText("取消").performClick()
    }
    @Test fun projectDetailsNeverShowTheGlobalSessionCard(){
        waitReady()
        val vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]
        val project=vm.repository.createProject("QA-ProjectScope-${System.currentTimeMillis()}")
        compose.runOnUiThread{vm.select(project)}
        compose.waitUntil(30000){vm.state.value.selected?.id==project.id&&!vm.state.value.busy}
        compose.onNodeWithTag("active-session-card").assertDoesNotExist()
        compose.runOnUiThread{vm.instances(true)}
        compose.onNodeWithTag("active-session-card").assertDoesNotExist()
    }
    @Test fun endedSessionHasNoDiagnosticBannerOrEmptyActionRow(){
        val vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]
        compose.runOnUiThread{compose.activity.setContent{McpyTheme{RunningSessionCard(ProductState(
            phase="interrupted",session="session-test",sessionProject="测试作品",sessionWorldName="开发测试",canRestart=true,
            runtimeError="游戏进程已退出，尚未确认正常保存"
        ),vm,Modifier.fillMaxWidth())}}}
        compose.onNodeWithText("游戏进程已退出，尚未确认正常保存").assertDoesNotExist()
        compose.onNodeWithText("测试已结束").assertExists()
        val card=compose.onNodeWithTag("active-session-card").getUnclippedBoundsInRoot()
        val button=compose.onNodeWithTag("restart-session").getUnclippedBoundsInRoot()
        assertTrue("Bottom padding is one card gutter",card.bottom-button.bottom in 16.dp..24.dp)
    }
}
