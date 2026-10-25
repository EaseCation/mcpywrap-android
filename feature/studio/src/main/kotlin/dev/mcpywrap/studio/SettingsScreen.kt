package dev.mcpywrap.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.mcpywrap.design.*
import dev.mcpy.runtime.RuntimeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

fun openWorkspaceFolder(context:Context,folder:String){
    val uri=DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:mcpy"+if(folder.isBlank())""else "/$folder")
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"vnd.android.document/directory").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

@Composable
fun SettingsScreen(s:ProductState,vm:StudioViewModel,openTrash:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();val snackbar=remember{SnackbarHostState()}
    var advanced by rememberSaveable{mutableStateOf(false)}
    var space by remember{mutableStateOf<Pair<Long,Long>?>(null)}
    LaunchedEffect(s.runtimeReady,s.installing){space=withContext(Dispatchers.IO){
        val installed=RuntimeRepository(context).installed()
        val used=installed?.directory?.walkTopDown()?.filter{it.isFile}?.sumOf{it.length()}?:0L
        used to context.filesDir.usableSpace
    }}
    fun folder(name:String){runCatching{openWorkspaceFolder(context,name)}.onFailure{scope.launch{snackbar.showSnackbar("请在文件管理器打开 内部存储 / mcpy${if(name.isBlank())""else "/$name"}")}}}
    fun copyPath(){context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("工作目录","/sdcard/mcpy"));scope.launch{snackbar.showSnackbar("已复制工作目录")}}
    Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),snackbarHost={SnackbarHost(snackbar)}){padding->ProductPage(Modifier.padding(padding).testTag("settings-page")){
        SectionCard("游戏运行环境",Icons.Outlined.SportsEsports,Modifier.testTag("settings-runtime")){
            Text(when{ s.installing->"正在准备游戏";s.runtimeReady->"已安装，可开始测试";else->"第一次测试前需要安装"},style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.primary)
            if(s.installing){Text(s.installText);s.installPercent?.let{LinearProgressIndicator(progress={it},modifier=Modifier.fillMaxWidth());Text("${(it*100).toInt()}%",style=MaterialTheme.typography.labelMedium)}?:LinearProgressIndicator(Modifier.fillMaxWidth());OutlinedButton(vm::pauseInstall){Text("暂停下载")}}
            else if(!s.runtimeReady){Text(s.installText,style=MaterialTheme.typography.bodyMedium);Text("下载约 2.16 GB，需要约 7 GB 可用空间。下载时可以继续编辑作品。",style=MaterialTheme.typography.bodySmall);Button(vm::install){Text(if(s.installText.contains("暂停"))"继续下载"else if(s.installText.contains("失败"))"重试安装"else "下载并安装")}}
            space?.let{(used,free)->Text((if(s.runtimeReady)"已占用 ${gib(used)} · "else "")+"可用空间 ${gib(free)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        ProductCard(Modifier.fillMaxWidth().testTag("settings-pip")){
            Row(Modifier.fillMaxWidth().padding(McpySpacing.xl),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(McpySpacing.lg)){
                Icon(Icons.Outlined.PictureInPictureAlt,null,tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(McpySpacing.xs)){Text("默认小窗运行",style=MaterialTheme.typography.titleMedium);Text("游戏加载时继续编辑和查看日志",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Switch(s.smallWindow,vm::setSmallWindow)
            }
        }
        SectionCard("构建引擎",Icons.Outlined.Build,Modifier.testTag("settings-core")){
            Text(s.coreVersion?.let{"版本 $it"}?:"正在读取版本",style=MaterialTheme.typography.bodyLarge)
            Text("负责把源码和依赖整理成可运行的模组，可独立更新。",style=MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(McpySpacing.sm)){
                if(s.coreUpdate!=null)Button(vm::updateCore,enabled=!s.busy){Text("更新到 ${s.coreUpdate}")}else OutlinedButton(vm::checkCoreUpdate,enabled=!s.busy){Text("检查更新")}
                if(s.coreCanRollback)TextButton(vm::rollbackCore,enabled=!s.busy){Text("恢复上一版")}
            }
        }
        SectionCard("工作目录",Icons.Outlined.FolderOpen,Modifier.testTag("settings-storage")){
            Text("内部存储 / mcpy",style=MaterialTheme.typography.bodyLarge)
            Text("/sdcard/mcpy",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(McpySpacing.sm)){OutlinedButton({folder("")}){Text("打开文件夹")};TextButton({copyPath()}){Text("复制路径")}}
            listOf(Triple("作品源码","projects",Icons.Outlined.Code),Triple("测试实例","worlds",Icons.Outlined.Public),Triple("运行日志和截图","logs",Icons.Outlined.Subject),Triple("导出文件","exports",Icons.Outlined.Archive)).forEach{(title,path,icon)->
                ListItem(headlineContent={Text(title)},leadingContent={Icon(icon,null)},trailingContent={Icon(Icons.Outlined.ChevronRight,null)},colors=ListItemDefaults.colors(containerColor=Color.Transparent),modifier=Modifier.clickable{folder(path)})
            }
            ListItem(headlineContent={Text("回收站")},supportingContent={Text("${s.trash.size} 个项目，可恢复删除的文件和实例")},leadingContent={Icon(Icons.Outlined.DeleteOutline,null)},trailingContent={Icon(Icons.Outlined.ChevronRight,null)},colors=ListItemDefaults.colors(containerColor=Color.Transparent),modifier=Modifier.clickable(onClick=openTrash))
        }
        SectionCard("文件访问",Icons.Outlined.FolderShared,Modifier.testTag("settings-permission")){
            Text(if(s.permission)"已允许访问作品目录"else "需要允许访问作品目录")
            TextButton({runCatching{context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:${context.packageName}")))}.onFailure{scope.launch{snackbar.showSnackbar("请在系统设置中允许 mcpy 访问文件")}}}){Text("管理文件权限")}
        }
        ProductOutlinedCard(){
            ListItem(headlineContent={Text("高级信息与开源许可")},leadingContent={Icon(Icons.Outlined.Info,null)},trailingContent={Icon(if(advanced)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)},colors=ListItemDefaults.colors(containerColor=Color.Transparent),modifier=Modifier.clickable{advanced=!advanced})
            if(advanced)Column(Modifier.padding(McpySpacing.xl),verticalArrangement=Arrangement.spacedBy(McpySpacing.sm)){
                val version=remember{runCatching{context.packageManager.getPackageInfo(context.packageName,0).versionName}.getOrNull().orEmpty()}
                Text("mcpy $version · mcpywrap-core ${s.coreVersion?:"尚未读取"}")
                Text("游戏版本 3.10.100.299889 · ARM64\n工具 Python 与游戏 Python 独立运行。",style=MaterialTheme.typography.bodySmall)
                Text("Git / pip 与项目脚本由外部开发工具准备，工程解析和构建仍由 App 完成。",style=MaterialTheme.typography.bodySmall)
                Text("Sora Editor 0.24.4 · LGPL-2.1\nMagicPython · MIT\nEclipse JGit · EDL-1.0\nJetpack Compose Material 3 1.4.0 · Apache-2.0",style=MaterialTheme.typography.bodySmall)
                TextButton({folder("logs")}){Text("打开诊断日志")}
            }
        }
    }}
}
private fun gib(bytes:Long)=String.format(Locale.getDefault(),"%.1f GB",bytes/(1024.0*1024*1024))
