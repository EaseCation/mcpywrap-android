package dev.mcpywrap.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 非模态状态只占据任务所在区域，下载之外不伪造百分比。 */
@Composable
fun LoadingFeedback(label:String,modifier:Modifier=Modifier) {
    Surface(modifier.semantics{liveRegion=LiveRegionMode.Polite},color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.medium){
        Row(Modifier.padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)
            Text(label,style=MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ModalWait(title:String,phase:String) {
    AlertDialog(onDismissRequest={},title={Text(title,Modifier.testTag("operation-progress"))},text={
        Column(Modifier.testTag("modal-wait").semantics{liveRegion=LiveRegionMode.Polite},verticalArrangement=Arrangement.spacedBy(16.dp)){
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(phase)
        }
    },confirmButton={})
}

/** 未完成首次读取时不展示“没有作品”；失败也不伪装成空列表。 */
@Composable
fun WorkspaceInitialContent(state:ContentLoadState,busy:Boolean,retry:()->Unit){
    Column(Modifier.fillMaxSize().padding(24.dp).testTag("workspace-not-loaded"),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
        if(state==ContentLoadState.ERROR||(!busy&&state!=ContentLoadState.READY)){
            Text("工作区尚未读取完成",style=MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick=retry,enabled=!busy){Text("重新读取")}
        }else{
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("正在读取作品和测试实例",style=MaterialTheme.typography.bodyMedium)
        }
    }
}
