package dev.mcpywrap.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.mcpywrap.design.*

@Composable
fun CollaborationDiagram(modifier:Modifier=Modifier) {
    ProductCard(modifier){
        Column(Modifier.padding(McpySpacing.xl),verticalArrangement=Arrangement.spacedBy(McpySpacing.lg)){
            Text("多个工具，同一份源码",style=MaterialTheme.typography.titleMedium)
            Column{
                CollaborationNode(Icons.Outlined.Code,"在习惯的工具中编写","Minis、Termux、编辑器或 AI",true)
                CollaborationNode(Icons.Outlined.FolderShared,"共享作品目录 /sdcard/mcpy","两个应用编辑相同的文件",true)
                CollaborationNode(Icons.Outlined.Build,"mcpy 构建并启动","工程解析、模组组装与运行日志",true)
                CollaborationNode(Icons.Outlined.PictureInPictureAlt,"游戏小窗展示结果","留在当前应用继续开发",false)
            }
        }
    }
}

@Composable
private fun CollaborationNode(icon:ImageVector,title:String,description:String,connect:Boolean){
    Row(horizontalArrangement=Arrangement.spacedBy(McpySpacing.md)){
        Column(horizontalAlignment=Alignment.CenterHorizontally){
            Surface(Modifier.size(40.dp),shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.primaryContainer){Box(contentAlignment=Alignment.Center){Icon(icon,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)}}
            if(connect)VerticalDivider(Modifier.height(McpySpacing.lg))
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(McpySpacing.xs)){
            Text(title,style=MaterialTheme.typography.labelLarge)
            Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
