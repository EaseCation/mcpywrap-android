package dev.mcpywrap.android

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.pressBack
import dev.mcpywrap.design.McpyTheme
import dev.mcpywrap.studio.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class LoadingFeedbackTest {
    @get:Rule val compose=createComposeRule()
    @Test fun nonModalProgressKeepsOtherActionsUsable(){
        var clicks=0
        compose.setContent{McpyTheme{Column{
            LoadingFeedback("正在下载运行环境")
            Button(onClick={clicks++}){Text("继续编辑")}
        }}}
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("继续编辑").performClick()
        assertEquals(1,clicks)
        compose.onNodeWithText("正在下载运行环境").assertIsDisplayed()
    }
    @Test fun committingUsesNativeModalDialogAndCannotDismissMidWrite(){
        compose.setContent{McpyTheme{ModalWait("创建模组","正在保存作品到公共目录")}}
        compose.onNode(isDialog()).assertExists()
        pressBack()
        compose.onNodeWithText("正在保存作品到公共目录").assertIsDisplayed()
    }
    @Test fun initialLoadAndFailureHaveExplicitContentInsteadOfEmptyState(){
        compose.setContent{McpyTheme{WorkspaceInitialContent(ContentLoadState.LOADING,true,{})}}
        compose.onNodeWithText("正在读取作品和测试实例").assertIsDisplayed()
        compose.onNodeWithText("先试一个示例").assertDoesNotExist()
        compose.onNodeWithText("文件夹为空").assertDoesNotExist()
    }
}
