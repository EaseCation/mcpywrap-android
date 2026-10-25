package net.easecation.mcpywrap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import dev.mcpywrap.design.McpyTheme
import dev.mcpywrap.studio.StudioApp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private fun handleConnectionIntent(intent:android.content.Intent){
        val uri=intent.data
        val recovery=intent.action==android.content.Intent.ACTION_VIEW&&uri?.scheme=="mcpy"&&uri.host=="service"&&uri.path=="/start"
        if(recovery||intent.action==dev.mcpywrap.bridge.HostService.OPEN){
            androidx.lifecycle.ViewModelProvider(this)[dev.mcpywrap.studio.StudioViewModel::class.java].tab(1)
        }
    }
    override fun onNewIntent(intent:android.content.Intent){super.onNewIntent(intent);setIntent(intent);handleConnectionIntent(intent)}
    override fun onPostResume(){
        super.onPostResume()
        val host=dev.mcpywrap.bridge.HostController.get(application)
        if(android.os.Environment.isExternalStorageManager() && dev.mcpywrap.bridge.HostService.canResume(application) && !host.online && !host.starting)
            dev.mcpywrap.bridge.HostService.start(application)
        lifecycleScope.launch{dev.mcpywrap.bridge.HostController.get(application).resumeFromForeground()}
    }
    override fun onStart(){super.onStart();dev.mcpywrap.bridge.HostController.get(application).foreground=true}
    override fun onStop(){dev.mcpywrap.bridge.HostController.get(application).foreground=false;super.onStop()}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleConnectionIntent(intent)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            McpyTheme { StudioApp() }
        }
    }
}
