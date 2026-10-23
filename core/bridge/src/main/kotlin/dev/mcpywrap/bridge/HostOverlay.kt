package dev.mcpywrap.bridge

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.*
import dev.mcpywrap.design.McpyTheme

/** A visible, temporary confirmation/progress card; never an idle overlay or hidden BAL token. */
internal class HostOverlay(context:Context, content:@Composable ()->Unit) : AutoCloseable,
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry=LifecycleRegistry(this)
    private val savedState=SavedStateRegistryController.create(this)
    override val lifecycle:Lifecycle get()=registry
    override val savedStateRegistry:SavedStateRegistry get()=savedState.savedStateRegistry
    override val viewModelStore=ViewModelStore()
    private val windows=context.getSystemService(WindowManager::class.java)
    private val view=ComposeView(context)
    private var drawn=false
    init {
        savedState.performAttach();savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        view.setViewTreeLifecycleOwner(this);view.setViewTreeSavedStateRegistryOwner(this);view.setViewTreeViewModelStoreOwner(this)
        view.viewTreeObserver.addOnDrawListener{drawn=true}
        view.filterTouchesWhenObscured=true
        view.setContent{McpyTheme{content()}}
        val density=context.resources.displayMetrics.density
        val width=minOf((360*density).toInt(),context.resources.displayMetrics.widthPixels-(32*density).toInt())
        val layout=WindowManager.LayoutParams(width,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply{gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL;y=(40*density).toInt()}
        try{windows.addView(view,layout);registry.handleLifecycleEvent(Lifecycle.Event.ON_START);registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)}
        catch(error:Exception){view.disposeComposition();registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);viewModelStore.clear();throw error}
    }
    val visible get()=drawn&&view.isAttachedToWindow
    override fun close(){
        runCatching{windows.removeViewImmediate(view)}
        view.disposeComposition();registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);viewModelStore.clear()
    }
}

@Composable internal fun ConnectionCard(label:String,access:String,onAllow:()->Unit,onDeny:()->Unit,onLater:()->Unit){
    ElevatedCard(Modifier.fillMaxWidth()){
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("允许 $label 连接？",style=MaterialTheme.typography.titleLarge)
            Text(if(access=="shared")"允许编辑 mcpy 文件夹中的作品、构建模组和调试游戏。" else "允许通过接口编辑作品、构建模组和调试游戏。")
            Text("仅在你刚刚发起连接时允许。以后可在 开发工具中断开。",style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                TextButton(onClick=onLater){Text("稍后")}
                TextButton(onClick=onDeny){Text("拒绝")}
                Button(onClick=onAllow){Text("允许连接")}
            }
        }
    }
}

@Composable internal fun LaunchCard(progress:String){
    ElevatedCard(Modifier.fillMaxWidth()){
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("正在启动游戏小窗",style=MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(progress,style=MaterialTheme.typography.bodyMedium)
            Text("可以继续操作，游戏小窗出现后这里会自动收起。",style=MaterialTheme.typography.bodySmall)
        }
    }
}
