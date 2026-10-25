package dev.mcpywrap.bridge

import android.app.KeyguardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.mcpywrap.design.McpyTheme
import kotlinx.coroutines.delay

/** A user-tapped notification opens only this dialog and returns to the caller on completion. */
class PairingConfirmationActivity:ComponentActivity(){
    private val host get()=HostController.get(application)
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val id=intent.getStringExtra("id")?:run{finish();return}
        host.confirmationShowing=true
        setContent{McpyTheme{
            var pair by remember{mutableStateOf(host.store.pendingPairs().firstOrNull{it.getString("id")==id})}
            var error by remember{mutableStateOf<String?>(null)}
            LaunchedEffect(id){while(true){pair=host.store.pendingPairs().firstOrNull{it.getString("id")==id};if(pair==null){finish();break};delay(500)}}
            fun decide(allow:Boolean){
                if(getSystemService(KeyguardManager::class.java).isKeyguardLocked){error="请先解锁手机";return}
                runCatching{host.approvePair(id,allow)}.onSuccess{finish()}.onFailure{error="请求已过期，请重新生成配对码"}
            }
            pair?.let{request->AlertDialog(onDismissRequest={finish()},
                title={Text("允许 ${request.getString("label")} 连接？")},
                text={Text(error?:"允许编辑 mcpy 中的作品、构建模组和调试游戏。仅允许你刚刚发起的连接，以后可在「开发」页断开。")},
                confirmButton={Button(onClick={decide(true)}){Text("允许连接")}},
                dismissButton={TextButton(onClick={decide(false)}){Text("拒绝")}})}
        }}
    }
    override fun onDestroy(){host.confirmationShowing=false;super.onDestroy()}
}
