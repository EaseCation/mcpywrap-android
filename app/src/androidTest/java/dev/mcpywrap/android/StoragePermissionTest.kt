package dev.mcpywrap.android

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The host revokes permission before this test, because Android kills the UID on revocation. */
class StoragePermissionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun deniedWorkspaceHasRecoveryAndNoPrivateFallback(){
        compose.waitUntil(15000){compose.onAllNodesWithText("允许文件访问").fetchSemanticsNodes().isNotEmpty()}
        assertFalse(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"projects").exists())
        val ui=InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("screencap -p /sdcard/mcpy/logs/product-evidence/public-storage-permission.png")).use{it.readBytes()}
        compose.onNodeWithText("允许文件访问").performClick()
        Thread.sleep(1000)
        val foreground=ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("dumpsys activity activities")).bufferedReader().use{it.readText()}
        org.junit.Assert.assertTrue(foreground.contains("com.android.settings"))
    }
}
