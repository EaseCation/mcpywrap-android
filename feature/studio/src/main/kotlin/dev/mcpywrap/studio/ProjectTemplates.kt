package dev.mcpywrap.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import dev.mcpywrap.design.McpySpacing

/** Curated templates share one selection UI; generation stays with core or the ordinary bundled archive. */
data class ProjectTemplate(val id:String,val title:String,val description:String,val defaultName:String,val example:String?=null)
object ProjectTemplates {
    val entries=listOf(
        ProjectTemplate("standard","标准模组","包含客户端、服务端和资源目录，从基础结构开始。","我的模组"),
        ProjectTemplate("welcome","欢迎语示例","一套可运行的普通源码，打开 client.py 修改欢迎语。","我的欢迎语","welcome")
    )
    fun get(id:String)=entries.single{it.id==id}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectTemplateSheet(onDismiss:()->Unit,onSelect:(ProjectTemplate)->Unit){
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Column(Modifier.fillMaxWidth().padding(horizontal=McpySpacing.page),verticalArrangement=Arrangement.spacedBy(McpySpacing.sm)){
            Text("选择模组模板",style=MaterialTheme.typography.titleLarge)
            Text("创建后就是普通文件，可以自由修改。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(McpySpacing.md))
        ProjectTemplates.entries.forEach{template->
            ListItem(headlineContent={Text(template.title)},supportingContent={Text(template.description)},
                leadingContent={Icon(if(template.example==null)Icons.Outlined.Extension else Icons.Outlined.WavingHand,null,tint=MaterialTheme.colorScheme.primary)},
                trailingContent={Icon(Icons.Outlined.ChevronRight,null)},colors=ListItemDefaults.colors(containerColor=Color.Transparent),
                modifier=Modifier.clickable{onSelect(template)}.testTag("template-${template.id}"))
        }
        Spacer(Modifier.height(McpySpacing.section))
    }
}
