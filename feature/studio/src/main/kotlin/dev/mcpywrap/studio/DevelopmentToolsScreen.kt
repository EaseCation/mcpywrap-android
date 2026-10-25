package dev.mcpywrap.studio

import android.Manifest
import android.app.Application
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.mcpywrap.bridge.HostController
import dev.mcpywrap.bridge.HostService
import dev.mcpywrap.design.*
import kotlinx.coroutines.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevelopmentToolsScreen(vm:StudioViewModel){
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current
    val host=remember{HostController.get(context.applicationContext as Application)}
    val revision by host.changes.collectAsStateWithLifecycle()
    val tasks by host.tasks.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val clients=remember(revision){host.store.publicClients()}
    val prefs=remember{context.getSharedPreferences("development-onboarding",0)}
    var kind by rememberSaveable{mutableStateOf(prefs.getString("tool","Minis")?:"Minis")}
    var mount by rememberSaveable{mutableStateOf(prefs.getString("mount","/var/minis/mounts/mcpy")?:"/var/minis/mounts/mcpy")}
    var environment by rememberSaveable{mutableStateOf(prefs.getString("environment","Alpine / Minis")?:"Alpine / Minis")}
    var adding by rememberSaveable{mutableStateOf(false)}
    var toolPicker by remember{mutableStateOf(false)}
    var commands by rememberSaveable{mutableStateOf(false)}
    var details by rememberSaveable{mutableStateOf(false)}
    var connectionCommand by rememberSaveable{mutableStateOf(false)}
    var manage by remember{mutableStateOf(false)}
    var directoryStatus by remember{mutableStateOf<String?>(null)}
    var checkingDirectory by remember{mutableStateOf(false)}
    var port by rememberSaveable{mutableStateOf(host.port.toString())}
    var pending by remember{mutableStateOf(host.store.pendingPairs())}
    var pairing by remember{mutableStateOf(host.store.pairingProgress())}
    var waiting by remember{mutableStateOf(host.application.pendingLaunch())}
    var code by remember{mutableStateOf(host.store.invitationCode())}
    var seconds by remember{mutableLongStateOf(host.store.invitationSeconds())}
    var overlay by remember{mutableStateOf(Settings.canDrawOverlays(context))}
    var notifications by remember{mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())}
    val setup=clients.isEmpty()||adding
    val snackbar=remember{SnackbarHostState()};val scope=rememberCoroutineScope()
    val notifyPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){notifications=it}
    val previous=remember{mutableIntStateOf(clients.size)}
    LaunchedEffect(clients.size){if(clients.size>previous.intValue){adding=false;snackbar.showSnackbar("连接已授权，回到你的工具继续开发")};previous.intValue=clients.size}
    LaunchedEffect(owner){owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){while(isActive){
        pending=host.store.pendingPairs();pairing=host.store.pairingProgress();waiting=host.application.pendingLaunch()
        code=host.store.invitationCode();seconds=host.store.invitationSeconds()
        overlay=Settings.canDrawOverlays(context);notifications=NotificationManagerCompat.from(context).areNotificationsEnabled();delay(750)
    }}}
    LaunchedEffect(host.online,setup){if(host.online&&setup&&code.isBlank()){code=host.store.newCode();seconds=host.store.invitationSeconds()}}
    fun feedback(message:String){scope.launch{snackbar.showSnackbar(message)}}
    fun copy(value:String){context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("mcpy",value));feedback("已复制，可粘贴到你的开发工具")}
    fun launch(intent:Intent){runCatching{context.startActivity(intent)}.onFailure{feedback("无法打开此入口，请在对应应用或系统设置中操作")}}
    fun start(){runCatching{HostService.start(context.applicationContext as Application)}.onFailure{feedback(it.message?:"连接启动失败")}}
    fun choose(tool:String){
        kind=tool;mount=if(tool=="Minis")"/var/minis/mounts/mcpy"else "/sdcard/mcpy"
        environment=if(tool=="Minis")"Alpine / Minis"else if(tool=="Termux")"Termux 原生"else "Debian / Ubuntu"
        prefs.edit().putString("tool",tool).putString("mount",mount).putString("environment",environment).apply();toolPicker=false
    }
    pending.firstOrNull()?.let{request->AlertDialog(onDismissRequest={},icon={Icon(Icons.Outlined.Link,null)},title={Text("允许 ${request.getString("label")} 连接？")},
        text={Text("这个工具可以编辑 mcpy 中的作品、构建模组和调试游戏。以后可以随时断开。")},
        confirmButton={Button({runCatching{host.approvePair(request.getString("id"),true)}.onFailure{feedback("请求已过期，请让工具重新连接")}}){Text("允许连接")}},
        dismissButton={TextButton({runCatching{host.approvePair(request.getString("id"),false)}}){Text("拒绝")}})}
    Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),snackbarHost={SnackbarHost(snackbar)}){padding->ProductPage(Modifier.padding(padding).testTag("development-page")){
        CollaborationDiagram()
        ProductCard(Modifier.fillMaxWidth().testTag("development-connection")){
            Row(Modifier.fillMaxWidth().padding(McpySpacing.xl),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(McpySpacing.lg)){
                Icon(Icons.Outlined.Link,null,tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(McpySpacing.xs)){
                    Text("开发连接",style=MaterialTheme.typography.titleMedium)
                    Text(if(host.starting)"开启中…"else if(host.online)"仅限本机"else "已暂停",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked=host.online||host.starting,onCheckedChange={if(it)start()else HostService.stop(context.applicationContext as Application)},enabled=!host.starting,modifier=Modifier.testTag("development-connection-switch"))
            }
            host.error?.let{Text(it,Modifier.padding(start=McpySpacing.xl,end=McpySpacing.xl,bottom=McpySpacing.lg),style=MaterialTheme.typography.bodySmall)}
        }
        waiting?.takeIf{it.optString("state") in listOf("waiting_user","presentation_waiting")}?.let{request->
            SectionCard("需要你处理",Icons.Outlined.Info){
                Text(if(request.optString("state")=="presentation_waiting")"游戏仍在运行，等待恢复画面。"else "这次游戏还没有显示，作品和测试实例已保留。")
                FlowRow(horizontalArrangement=Arrangement.spacedBy(McpySpacing.sm)){
                    Button(vm::resumeLaunch,enabled=!state.busy){Text("继续测试")}
                    OutlinedButton(vm::restartCurrentSession,enabled=state.canRestart&&!state.busy){Text("重新测试")}
                }
                TextButton({launch(when(request.optString("reason")){
                    "overlay_permission_required"->Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${context.packageName}"))
                    "pip_permission_missing"->Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS",Uri.parse("package:${context.packageName}"))
                    else->host.application.diagnostics.background.settingsIntent()
                })}){Text("打开系统设置")}
                TextButton(vm::stop,enabled=!state.busy){Text("取消本次测试")}
            }
        }
        if(setup){
            SectionCard("1 · 选择工具",Icons.Outlined.Code){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(McpySpacing.xs)){Text(kind,style=MaterialTheme.typography.titleLarge);Text(if(kind=="Minis")"推荐 · AI 对话、终端和文件编辑"else if(kind=="Termux")"使用终端编写和运行命令"else "其他 AI、编辑器或 HTTP 客户端",style=MaterialTheme.typography.bodySmall)};TextButton({toolPicker=true}){Text("更换")}}
                if(kind!="其他工具")OutlinedButton({launch(Intent(Intent.ACTION_VIEW,Uri.parse(if(kind=="Minis")"https://github.com/OpenMinis/OpenMinis/releases"else "https://github.com/termux/termux-app/releases")))}){Text("获取 $kind")}
                Text("在工具里准备 mcpy 命令。可以交给 AI，也可以自己执行安装命令。",style=MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(McpySpacing.sm)){
                    FilledTonalButton({copy(host.setupPrompt(kind))}){Text("复制安装说明给 AI")}
                    TextButton({commands=!commands}){Text(if(commands)"收起安装命令"else "查看安装命令")}
                }
                if(commands){
                    var choices by remember{mutableStateOf(false)}
                    Box{TextButton({choices=true}){Text(environment)};DropdownMenu(choices,{choices=false}){listOf("Alpine / Minis","Debian / Ubuntu","Termux 原生").forEach{value->DropdownMenuItem(text={Text(value)},onClick={environment=value;prefs.edit().putString("environment",value).apply();choices=false})}}}
                    val packages=when(environment){"Alpine / Minis"->"apk add python3 py3-pip git";"Termux 原生"->"pkg install python git";else->"apt update && apt install -y python3 python3-venv python3-pip git"}
                    val install=packages+"\npython3 -m venv \"\$HOME/.venvs/mcpy\"\n. \"\$HOME/.venvs/mcpy/bin/activate\"\npython -m pip install -U mcpywrap"
                    CommandBlock(install){copy(install)}
                }
            }
            SectionCard("2 · 共享作品目录",Icons.Outlined.FolderShared){
                Text("内部存储 / mcpy",style=MaterialTheme.typography.titleMedium)
                Text(if(kind=="Minis")"在 Minis 的 Mount External Folders 中选择这个文件夹，让两个应用编辑同一份源码。"else "让你的工具读写这个文件夹，源码、测试实例和日志都会保留在这里。")
                Text("/sdcard/mcpy",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                if(pairing?.optString("state")=="verifying"){LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在由工具验证共享目录")}
                else Text("外部工具的读写权限会在连接时自动验证。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                directoryStatus?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                TextButton({checkingDirectory=true;scope.launch{
                    directoryStatus=withContext(Dispatchers.IO){runCatching{check(Environment.isExternalStorageManager());val probe=File.createTempFile("directory-check-",".tmp",File("/sdcard/mcpy"));try{probe.writeText("mcpy");check(probe.readText()=="mcpy")}finally{probe.delete()};"mcpy 可以读写此目录；请在外部工具中完成共享。"}.getOrElse{"mcpy 无法读写目录，请在设置中检查文件权限。"}}
                    checkingDirectory=false
                }},enabled=!checkingDirectory){Text(if(checkingDirectory)"正在检查"else "检查本机目录")}
            }
            SectionCard("3 · 连接工具",Icons.Outlined.Phonelink){
                Text("将验证码交给工具，允许它把构建和运行请求交给 mcpy。")
                if(host.online){
                    Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(McpySpacing.xs)){Text("连接验证码",style=MaterialTheme.typography.labelMedium);SelectionContainer{Text(code.ifBlank{"已过期"},style=MaterialTheme.typography.headlineMedium,fontFamily=FontFamily.Monospace)}};IconButton({copy(code)},enabled=code.isNotBlank()){Icon(Icons.Outlined.ContentCopy,"复制验证码")}}
                    Text("连接地址",style=MaterialTheme.typography.labelMedium);SelectionContainer{Text(host.endpoint,fontFamily=FontFamily.Monospace)}
                    Text(if(seconds>0)"约 ${(seconds+59)/60} 分钟内有效 · 只需确认一次"else "生成新验证码后再连接",style=MaterialTheme.typography.bodySmall)
                    if(code.isBlank())Button({code=host.store.newCode()}){Text("生成验证码")}
                    else {
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(McpySpacing.sm)){
                            Button({copy(host.bootstrapPrompt(kind))}){Text("复制连接说明给 AI")}
                            OutlinedButton({connectionCommand=!connectionCommand}){Text("使用命令连接")}
                        }
                        if(connectionCommand){
                            OutlinedTextField(mount,{mount=it;prefs.edit().putString("mount",it).apply()},Modifier.fillMaxWidth(),label={Text("工具中的 mcpy 文件夹路径")},supportingText={Text("Minis 常用 /var/minis/mounts/mcpy")},isError=!mount.startsWith('/'),singleLine=true)
                            if(mount.startsWith('/'))CommandBlock(host.pairingCommand(kind,mount,code)){copy(host.pairingCommand(kind,mount,code))}
                        }
                    }
                    Text(when(pairing?.optString("state")){"verifying"->"目录验证中，请留在当前工具等待。";"pending"->"收到请求，请点击「允许连接」。";else->if(overlay)"请求到达后，可在临时确认卡中允许连接。"else if(notifications)"请求到达后，可在通知中允许连接。"else "请求到达后，回到本页允许连接。"},style=MaterialTheme.typography.bodySmall)
                }else Button({start()},enabled=!host.starting){Text("恢复连接")}
            }
            if(adding)TextButton({adding=false}){Text("返回已授权工具")}
        }else{
            SectionCard("已授权的工具",Icons.Outlined.Devices){
                clients.forEach{client->ListItem(headlineContent={Text(client.getString("label"))},supportingContent={Text(if(client.optString("access")=="files")"通过文件接口访问作品"else "共享目录已验证，可编辑、构建和调试")},leadingContent={Icon(Icons.Outlined.CheckCircle,null,tint=MaterialTheme.colorScheme.primary)},colors=ListItemDefaults.colors(containerColor=Color.Transparent))}
                FlowRow(horizontalArrangement=Arrangement.spacedBy(McpySpacing.sm)){Button({copy(host.developmentPrompt(kind))}){Text("复制开发说明")};TextButton({manage=true}){Text("管理授权")}}
            }
            val active=tasks.filter{it.optString("state") in listOf("queued","running","waiting_preparation","waiting_user")}
            active.take(3).forEach{task->SectionCard("正在进行",Icons.Outlined.PendingActions){
                Text(task.optString("project").substringAfterLast('/'),style=MaterialTheme.typography.titleMedium)
                Text(when(task.optString("state")){"queued"->"等待前一个任务完成";"waiting_user"->"等待你继续测试";"waiting_preparation"->"工具正在准备依赖或执行脚本";else->task.optString("progress","正在处理")})
                if(task.optString("state")!="waiting_user")LinearProgressIndicator(Modifier.fillMaxWidth())
            }}
            if(state.session.isNotBlank()&&state.phase in listOf("loading","running","saving","waiting_user"))SectionCard("当前测试",Icons.Outlined.SportsEsports){
                Text(state.sessionProject,style=MaterialTheme.typography.titleMedium)
                TextButton({vm.closeProject();vm.tab(0)}){Text("查看运行记录")}
            }
            OutlinedButton({adding=true},Modifier.fillMaxWidth()){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(McpySpacing.sm));Text("连接其他工具")}
        }
        ProductOutlinedCard(){
            ListItem(headlineContent={Text("连接详情与帮助")},leadingContent={Icon(Icons.Outlined.Info,null)},trailingContent={Icon(if(details)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)},colors=ListItemDefaults.colors(containerColor=Color.Transparent),modifier=Modifier.clickable{details=!details})
            if(details)Column(Modifier.padding(McpySpacing.xl),verticalArrangement=Arrangement.spacedBy(McpySpacing.md)){
                SelectionContainer{Text("${host.endpoint}\nHTTP / WebSocket v2 · 核心 ${host.coreVersion.ifBlank{"正在读取"}}",style=MaterialTheme.typography.bodySmall)}
                Text("正常测试直接在工具里运行命令。只有连接失败时，才手动回到 mcpy 恢复。",style=MaterialTheme.typography.bodyMedium)
                TextButton({copy("mcpy://service/start")}){Text("复制恢复链接")}
                TextButton({copy("mcpy 本机连接：${if(host.online)"可用"else "暂停"}，${host.endpoint}，核心 ${host.coreVersion}。临时提示：$overlay；通知：$notifications。")}){Text("复制排错信息")}
                Text("在其他应用中完成确认",style=MaterialTheme.typography.titleSmall)
                if(!overlay)TextButton({launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${context.packageName}")))}){Text("允许临时提示")}
                if(!notifications)TextButton({if(Build.VERSION.SDK_INT>=33)notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)else launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}){Text("允许连接通知")}
                Text("切换应用后经常断连？可以检查后台运行设置。小米选择「无限制」。",style=MaterialTheme.typography.bodySmall)
                TextButton({launch(host.application.diagnostics.execution.settingsIntent())}){Text("打开后台运行设置")}
                OutlinedTextField(port,{port=it},label={Text("连接端口")},singleLine=true,enabled=!host.online&&!host.starting,modifier=Modifier.fillMaxWidth())
                TextButton({port.toIntOrNull()?.takeIf{it in 1024..65535}?.let{host.port=it}?:feedback("端口需为 1024–65535")},enabled=!host.online&&!host.starting){Text("保存端口")}
            }
        }
    }}
    if(toolPicker)ModalBottomSheet(onDismissRequest={toolPicker=false}){
        Text("选择工具",Modifier.padding(horizontal=McpySpacing.page),style=MaterialTheme.typography.titleLarge)
        listOf("Minis" to "推荐 · AI 对话、终端与文件编辑","Termux" to "命令行开发","其他工具" to "其他 AI、编辑器或 HTTP 客户端").forEach{(name,description)->ListItem(headlineContent={Text(name)},supportingContent={Text(description)},leadingContent={Icon(if(name=="Minis")Icons.Outlined.AutoAwesome else if(name=="Termux")Icons.Outlined.Terminal else Icons.Outlined.Code,null)},trailingContent={RadioButton(kind==name,{choose(name)})},modifier=Modifier.clickable{choose(name)})}
        Spacer(Modifier.height(McpySpacing.section))
    }
    if(manage)ModalBottomSheet(onDismissRequest={manage=false}){
        Text("管理授权",Modifier.padding(horizontal=McpySpacing.page),style=MaterialTheme.typography.titleLarge)
        clients.forEach{client->ListItem(headlineContent={Text(client.getString("label"))},trailingContent={TextButton({host.revoke(client.getString("id"));if(clients.size==1)manage=false}){Text("断开")}})}
        Spacer(Modifier.height(McpySpacing.section))
    }
}

@Composable private fun CommandBlock(command:String,onCopy:()->Unit){
    Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=McpyShapes.compactCard){Column(Modifier.fillMaxWidth().padding(McpySpacing.md)){
        SelectionContainer{Text(command,Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}
        TextButton(onCopy,Modifier.align(Alignment.End)){Icon(Icons.Outlined.ContentCopy,null);Spacer(Modifier.width(McpySpacing.sm));Text("复制命令")}
    }}
}
