package dev.mcpywrap.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mcpywrap.design.McpyShapes
import dev.mcpywrap.design.McpySpacing
import dev.mcpywrap.design.ProductCard

@Composable
fun RunningSessionCard(state:ProductState,vm:StudioViewModel,modifier:Modifier=Modifier){
    val phase=state.phase
    ProductCard(modifier.testTag("active-session-card")){
        Column(Modifier.padding(McpySpacing.xl),verticalArrangement=Arrangement.spacedBy(McpySpacing.md)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Icon(Icons.Outlined.SportsEsports,null)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(McpySpacing.xs)){
                    Text(state.projects.find{it.folder==state.sessionProject}?.name?:state.sessionProject.ifBlank{"当前游戏"},style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(state.sessionWorldName.isNotBlank())Text("测试实例 · ${state.sessionWorldName}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(when(phase){
                        "loading"->state.runtimeLoading.ifBlank{"游戏启动中"};"running"->"游戏正在运行";"saving"->"正在保存世界";"saved"->"测试已结束"
                        "waiting_user"->"作品已准备，等待继续启动";"unknown"->"启动尚未确认";"unresponsive"->"状态暂未更新";"cancelled"->"启动已取消";"failed"->"测试未能启动";else->"测试已结束"
                    },style=MaterialTheme.typography.bodySmall)
                }
                if(phase in listOf("loading","saving")||state.restartBusy)CircularProgressIndicator(Modifier.size(24.dp))
            }
            if(state.restartBusy)Text(state.progress.ifBlank{"正在重新测试"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
            if(state.restartReason.isNotBlank()){
                Text(state.restartReason,style=MaterialTheme.typography.bodySmall)
                if(phase!="saving")TextButton(vm::returnToSessionProject){Text("返回作品")}
            }
            if(phase=="running")Text(if(state.gameInPip)"画面正在小窗显示"else "画面正在游戏界面显示",style=MaterialTheme.typography.labelSmall)
            Column(verticalArrangement=Arrangement.spacedBy(0.dp)){
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(McpySpacing.sm)){
                FilledTonalButton(onClick=vm::logs){Text("查看日志")}
                Button(onClick=vm::restartCurrentSession,enabled=!state.busy&&!state.restartBusy&&state.canRestart,modifier=Modifier.testTag("restart-session")){Text(if(state.restartBusy)"重新测试中"else if(phase=="saving")"保存中"else "重新测试")}
            }
            if(phase in listOf("running","loading","unknown","unresponsive","waiting_user"))FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(McpySpacing.sm)){
                if(phase=="running")TextButton(onClick=vm::showGame,enabled=!state.busy){Text("返回游戏")}
                if(phase=="waiting_user")TextButton(onClick=vm::resumeLaunch,enabled=!state.busy){Text("继续测试")}
                if(phase in listOf("running","loading","unknown","unresponsive","waiting_user"))TextButton(onClick=vm::stop,enabled=!state.busy&&!state.restartBusy){Text(if(phase=="waiting_user")"取消"else "保存退出")}
            }
            }
        }
    }
}
