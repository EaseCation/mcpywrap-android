package dev.mcpywrap.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import dev.mcpywrap.design.McpySpacing
import dev.mcpywrap.design.ProductPage
import dev.mcpywrap.design.ProductFooter
import dev.mcpywrap.design.ProductCard
import dev.mcpywrap.design.ProductOutlinedCard
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONObject

data class NewProjectDraft(
    val title:String="我的模组", val modName:String="MyMod", val version:String="1.0.0",
    val scriptDir:String="myScript", val serverOverride:String?=null, val clientOverride:String?=null
) {
    val server get()=serverOverride?:"${modName}ServerSystem"
    val client get()=clientOverride?:"${modName}ClientSystem"
    fun json()=JSONObject().put("title",title).put("modName",modName).put("version",version)
        .put("scriptDir",scriptDir).put("serverSystem",server).put("clientSystem",client)
}

private val reserved=setOf("False","None","True","and","as","assert","async","await","break","class","continue","def","del","elif","else","except","finally","for","from","global","if","import","in","is","lambda","nonlocal","not","or","pass","raise","return","try","while","with","yield","print","exec","__init__")
private fun identifier(value:String)=value.length<=96&&Regex("[A-Za-z_][A-Za-z0-9_]*").matches(value)&&value !in reserved

/** 手机端分步表单；预览来自核心生成的真实文件清单，不在 UI 拼装 Python。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectScreen(draft:NewProjectDraft,preview:JSONObject?,busy:Boolean,modal:Boolean,title:String,progress:String,error:String?,
                     change:(NewProjectDraft)->Unit,back:()->Unit,edit:()->Unit,
                     inspect:()->Unit,create:()->Unit,dismissError:()->Unit,cancelPreview:()->Unit) {
    var advanced by rememberSaveable{mutableStateOf(false)}
    val scroll=rememberScrollState()
    LaunchedEffect(preview,error){scroll.scrollTo(0)}
    val titleValid=draft.title.trim().let{it.isNotEmpty()&&it.length<=80&&!it.startsWith('.')&&it.none{c->c.isISOControl()||c in "/\\:*?\"<>|"}}
    val valid=titleValid&&identifier(draft.modName)&&identifier(draft.scriptDir)&&identifier(draft.server)&&identifier(draft.client)&&draft.version.isNotBlank()
    val goBack={if(!busy){if(preview!=null)edit() else back()}else if(!modal)back()}
    BackHandler{goBack()}
    Scaffold(topBar={TopAppBar(title={Text(if(preview==null)"新建模组" else "确认文件结构")},navigationIcon={IconButton(onClick=goBack,enabled=!busy||!modal){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回新建")}})},
        bottomBar={Surface(tonalElevation=3.dp){ProductFooter{
            Button(onClick=if(preview==null)inspect else create,enabled=!busy&&valid,modifier=Modifier.fillMaxWidth().testTag("new-project-next")){
                Text(if(busy)"处理中…" else if(preview==null)"查看结构" else "创建模组")
            }
            if(busy&&!modal)TextButton(onClick=cancelPreview,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("取消预览")}
        }}}
    ){padding->
        ProductPage(Modifier.padding(padding),scrollState=scroll){
            if(busy&&!modal)LoadingFeedback(progress,Modifier.fillMaxWidth().testTag("operation-progress"))
            if(error!=null)Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.padding(12.dp)){
                Text(error,color=MaterialTheme.colorScheme.onErrorContainer);TextButton(onClick=dismissError){Text("知道了")}
            }}
            if(preview==null){
                OutlinedTextField(draft.title,{change(draft.copy(title=it))},Modifier.fillMaxWidth().testTag("new-project-title"),
                    label={Text("作品名称")},supportingText={Text(if(titleValid)"手机中显示的名称，可以用中文" else "名称不能为空，不能包含路径符号")},isError=!titleValid,singleLine=true,enabled=!busy)
                OutlinedTextField(draft.modName,{change(draft.copy(modName=it))},Modifier.fillMaxWidth().testTag("new-mod-name"),
                    label={Text("Mod 标识")},supportingText={Text(if(identifier(draft.modName))"代码中的名称，例如 MyFirstMod" else "使用字母、数字、下划线；不能以数字开头或使用保留字")},isError=!identifier(draft.modName),singleLine=true,enabled=!busy)
                TextButton(onClick={advanced=!advanced},enabled=!busy){Icon(if(advanced)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null);Spacer(Modifier.width(McpySpacing.sm));Text(if(advanced)"收起更多选项" else "更多选项")}
                if(advanced){
                    OutlinedTextField(draft.version,{change(draft.copy(version=it))},Modifier.fillMaxWidth().testTag("new-mod-version"),label={Text("模组版本")},singleLine=true,enabled=!busy)
                    OutlinedTextField(draft.scriptDir,{change(draft.copy(scriptDir=it))},Modifier.fillMaxWidth().testTag("new-script-dir"),label={Text("脚本目录")},isError=!identifier(draft.scriptDir),singleLine=true,enabled=!busy)
                    OutlinedTextField(draft.server,{change(draft.copy(serverOverride=it))},Modifier.fillMaxWidth().testTag("new-server-system"),label={Text("服务端系统名")},isError=!identifier(draft.server),singleLine=true,enabled=!busy)
                    OutlinedTextField(draft.client,{change(draft.copy(clientOverride=it))},Modifier.fillMaxWidth().testTag("new-client-system"),label={Text("客户端系统名")},isError=!identifier(draft.client),singleLine=true,enabled=!busy)
                    if(draft.serverOverride!=null||draft.clientOverride!=null)TextButton(onClick={change(draft.copy(serverOverride=null,clientOverride=null))},enabled=!busy){Text("按 Mod 标识自动命名")}
                    Text("系统类路径会随脚本目录和系统名生成。",style=MaterialTheme.typography.bodySmall)
                }
                Text("生成客户端和服务端的普通源码，之后直接打开文件修改。",style=MaterialTheme.typography.bodyMedium)
            }else{
                Text(draft.title.trim(),style=MaterialTheme.typography.headlineSmall)
                Text("内部存储 / mcpy / projects / ${draft.title.trim()}",style=MaterialTheme.typography.bodySmall)
                Text("${draft.modName} · ${preview.getJSONObject("options").getString("version")}",style=MaterialTheme.typography.labelLarge)
                ProductOutlinedCard(Modifier.fillMaxWidth().testTag("new-project-tree")){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(McpySpacing.xs)){
                    val paths=preview.getJSONArray("files")
                    for(i in 0 until paths.length()){
                        val path=paths.getString(i);val parts=path.trimEnd('/').split('/')
                        Row(Modifier.padding(start=((parts.size-1)*12).dp),verticalAlignment=Alignment.CenterVertically){
                            Icon(if(path.endsWith('/'))Icons.Outlined.Folder else Icons.Outlined.Description,null,Modifier.size(20.dp))
                            Spacer(Modifier.width(McpySpacing.sm));Text(parts.last(),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }}
                Text("从 modMain.py 开始；客户端和服务端逻辑分别在 client、server 目录。",style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick=edit,enabled=!busy){Text("修改配置")}
            }
        }
    }
    if(busy&&modal)ModalWait(title,progress)

}
