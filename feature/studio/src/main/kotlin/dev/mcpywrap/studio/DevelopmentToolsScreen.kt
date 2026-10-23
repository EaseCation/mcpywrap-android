package dev.mcpywrap.studio

import android.Manifest
import android.app.Application
import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import dev.mcpywrap.bridge.HostController
import dev.mcpywrap.bridge.HostService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevelopmentToolsScreen(){
    val context=LocalContext.current
    val host=remember{HostController.get(context.applicationContext as Application)}
    val revision by host.changes.collectAsState()
    val tasks by host.tasks.collectAsState()
    val clients=remember(revision){host.store.publicClients()}
    val prefs=remember{context.getSharedPreferences("development-onboarding",0)}
    var step by rememberSaveable{mutableIntStateOf(prefs.getInt("step",0))}
    var kind by rememberSaveable{mutableStateOf(prefs.getString("tool","Minis")?:"Minis")}
    var environment by rememberSaveable{mutableStateOf(prefs.getString("environment","Alpine / Minis")?:"Alpine / Minis")}
    var environments by remember{mutableStateOf(false)}
    var ai by rememberSaveable{mutableStateOf(prefs.getBoolean("ai",true))}
    var mount by rememberSaveable{mutableStateOf(prefs.getString("mount","/var/minis/mounts/mcpy")?:"/var/minis/mounts/mcpy")}
    var adding by rememberSaveable{mutableStateOf(false)}
    var tools by remember{mutableStateOf(false)}
    var connections by remember{mutableStateOf(false)}
    var details by rememberSaveable{mutableStateOf(false)}
    var manualConnect by rememberSaveable{mutableStateOf(false)}
    var copied by rememberSaveable{mutableStateOf(false)}
    var starting by remember{mutableStateOf(false)}
    var port by rememberSaveable{mutableStateOf(host.port.toString())}
    var notifications by remember{mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())}
    var overlay by remember{mutableStateOf(Settings.canDrawOverlays(context))}
    var pending by remember{mutableStateOf(host.store.pendingPairs())}
    var waitingLaunch by remember{mutableStateOf(host.application.pendingLaunch())}
    var pairing by remember{mutableStateOf(host.store.pairingProgress())}
    var code by remember{mutableStateOf(host.store.invitationCode())}
    var seconds by remember{mutableLongStateOf(host.store.invitationSeconds())}
    val snackbar=remember{SnackbarHostState()}
    val scope=rememberCoroutineScope()
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){notifications=it}
    val setup=clients.isEmpty()||adding
    val previousCount=remember{mutableIntStateOf(clients.size)}
    LaunchedEffect(clients.size){
        if(clients.size>previousCount.intValue){adding=false;copied=false;snackbar.showSnackbar("连接成功，可以回到开发工具继续操作")}
        previousCount.intValue=clients.size
    }
    LaunchedEffect(Unit){while(true){
        notifications=NotificationManagerCompat.from(context).areNotificationsEnabled();overlay=Settings.canDrawOverlays(context)
        pending=host.store.pendingPairs();pairing=host.store.pairingProgress();waitingLaunch=host.application.pendingLaunch()
        code=host.store.invitationCode();seconds=host.store.invitationSeconds();delay(500)
    }}
    LaunchedEffect(host.online,setup,step){if(host.online&&setup&&step==1&&host.store.invitationCode().isEmpty()){code=host.store.newCode();seconds=host.store.invitationSeconds()}}
    LaunchedEffect(revision){if(host.online||host.error!=null)starting=false}
    fun advance(value:Int){step=value;copied=false;prefs.edit().putInt("step",value).apply()}
    fun copy(text:String,message:String="已复制"){
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("mcpy",text))
        copied=true;scope.launch{snackbar.showSnackbar(message)}
    }
    fun open(url:String){runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}.onFailure{scope.launch{snackbar.showSnackbar("无法打开，请先安装对应工具")}}}
    fun allowOverlay(){context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${context.packageName}")))}
    fun start(){starting=true;HostService.start(context.applicationContext as Application)}
    val pair=pending.firstOrNull()
    if(pair!=null)AlertDialog(onDismissRequest={},icon={Icon(Icons.Outlined.Link,null)},title={Text("允许 ${pair.getString("label")} 连接？")},
        text={Text("允许编辑 mcpy 文件夹中的作品、构建模组和调试游戏。仅允许你刚刚发起的连接，以后可随时断开。")},
        confirmButton={Button(onClick={runCatching{host.approvePair(pair.getString("id"),true)}.onFailure{scope.launch{snackbar.showSnackbar("请求已过期，请重新生成配对码")}}}){Text("允许连接")}},
        dismissButton={TextButton(onClick={runCatching{host.approvePair(pair.getString("id"),false)}}){Text("拒绝")}})
    Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),snackbarHost={SnackbarHost(snackbar)}){padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text(if(setup)"用外部工具开发"else "外部工具连接",style=MaterialTheme.typography.headlineSmall)
            waitingLaunch?.takeIf{it.optString("state") in listOf("waiting_user","presentation_waiting")}?.let{waiting->
                ElevatedCard{Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    val restoring=waiting.optString("state")=="presentation_waiting"
                    Text(if(restoring)"恢复游戏画面"else "作品已准备好",style=MaterialTheme.typography.titleLarge)
                    Text(if(restoring)"游戏进程仍在运行，返回游戏恢复画面。"else "继续同一次启动，不会重新构建或创建世界。允许后台弹出界面后，以后可以直接在开发工具中启动。")
                    Button(onClick={scope.launch{host.resumeFromForeground()}},modifier=Modifier.fillMaxWidth()){Text(if(restoring)"恢复画面"else "继续启动游戏")}
                    OutlinedButton(onClick={
                        val reason=waiting.optString("reason")
                        val settings=if(reason=="pip_permission_missing")Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS",Uri.parse("package:${context.packageName}"))else host.application.diagnostics.background.settingsIntent()
                        runCatching{context.startActivity(settings)}.onFailure{scope.launch{snackbar.showSnackbar("请在系统应用设置中检查 mcpy 的权限")}}
                    },modifier=Modifier.fillMaxWidth()){Text("设置后台启动权限")}
                    TextButton(onClick={scope.launch{host.application.mutate{host.application.stopCurrent()};host.changed()}}){Text(if(restoring)"保存退出"else "取消本次启动")}
                }}
            }
            if(setup){
                Text("在其他应用里编辑模组，由 mcpy 构建并运行游戏。",color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp)){
                    listOf("准备工具","允许工具连接").forEachIndexed{i,label->Text("${i+1} · $label",color=if(i==step)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.titleSmall)}
                }
                if(step==0){
                    ElevatedCard{Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){Text(kind,style=MaterialTheme.typography.titleLarge);Text(if(kind=="Minis")"推荐 · AI 对话与文件编辑"else "命令行、编辑器或自动化工具",style=MaterialTheme.typography.bodySmall)}
                            TextButton(onClick={tools=true}){Text("更换")}
                        }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){
                            listOf("复制给 AI","使用命令").forEachIndexed{i,label->SegmentedButton(selected=ai==(i==0),onClick={ai=i==0;prefs.edit().putBoolean("ai",ai).apply()},shape=SegmentedButtonDefaults.itemShape(i,2)){Text(label)}}
                        }
                        Text(if(kind=="Minis")"在 Minis 设置的 Mount External Folders 中，选择手机内部存储的 mcpy 文件夹，让它能打开和修改作品。"else "让工具访问手机内部存储的 mcpy 文件夹，并保留读写权限。")
                        if(ai){
                            Text("如果工具支持 AI，将下面的安装说明粘贴到对话中。账号登录和文件授权需由你完成。",style=MaterialTheme.typography.bodyMedium)
                            Button(onClick={copy(host.setupPrompt(kind),"已复制，可粘贴或自行调整提示词")},modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.ContentCopy,null);Spacer(Modifier.width(8.dp));Text("复制安装说明给 AI")}
                        }else{
                            Box {
                                TextButton(onClick={environments=true}){Text("环境：$environment")}
                                DropdownMenu(expanded=environments,onDismissRequest={environments=false}){
                                    listOf("Alpine / Minis","Debian / Ubuntu","Termux 原生").forEach{item->DropdownMenuItem(text={Text(item)},onClick={environment=item;prefs.edit().putString("environment",item).apply();environments=false})}
                                }
                            }
                            Text("在该环境的终端中依次运行：",style=MaterialTheme.typography.bodyMedium)
                            val packages=when(environment){"Alpine / Minis"->"apk add python3 py3-pip git";"Termux 原生"->"pkg install python git";else->"apt update && apt install -y python3 python3-venv python3-pip git"}
                            val install=packages+"\npython3 -m venv \"\$HOME/.venvs/mcpy\"\n. \"\$HOME/.venvs/mcpy/bin/activate\"\npython -m pip install -U mcpywrap"
                            CommandBlock(install){copy(install,"已复制安装命令")}
                            Text("解释器与工具装在此环境的私有目录中。作品仍保留在共享的 mcpy 文件夹内；普通终端也能完成后续操作。",style=MaterialTheme.typography.bodySmall)
                        }
                        if(kind!="其他工具")TextButton(onClick={open(if(kind=="Minis")"https://github.com/OpenMinis/OpenMinis/releases"else "https://github.com/termux/termux-app/releases")}){Text("安装 $kind")}
                        FilledTonalButton(onClick={advance(1)},modifier=Modifier.fillMaxWidth()){Text("下一步：允许工具连接")}
                    }}
                    TextButton(onClick={advance(1)}){Text("已有 mcpy 命令，直接连接")}
                }else{
                    if(!overlay||!notifications)OutlinedCard{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text("在其他应用中完成确认",style=MaterialTheme.typography.titleMedium)
                        if(!overlay){Text("允许临时提示后，连接确认和游戏启动会直接出现；平时不显示悬浮图标。",style=MaterialTheme.typography.bodyMedium);OutlinedButton(onClick={allowOverlay()}){Text("允许临时提示")}}
                        if(!notifications)TextButton(onClick={if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}){Text("允许连接通知")}
                    }}
                    ElevatedCard{Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        Text(when(pairing?.optString("state")){"verifying"->"正在验证共享目录";"pending"->"等待允许连接";else->"连接信息"},style=MaterialTheme.typography.titleLarge)
                        host.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                        if(!host.online){
                            Text("允许后，配对的工具才能读写作品、构建模组和启动游戏。",style=MaterialTheme.typography.bodyMedium)
                            Button(onClick={start()},enabled=!starting&&!host.starting,modifier=Modifier.fillMaxWidth()){
                                if(starting||host.starting){CircularProgressIndicator(Modifier.size(18.dp));Spacer(Modifier.width(8.dp))};Text(if(starting||host.starting)"正在开启"else "允许工具连接")
                            }
                        }else{
                            Text("本机地址",style=MaterialTheme.typography.labelMedium)
                            SelectionContainer{Text(host.endpoint,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodyLarge)}
                            Row(verticalAlignment=Alignment.CenterVertically){
                                Column(Modifier.weight(1f)){Text("配对码",style=MaterialTheme.typography.labelMedium);SelectionContainer{Text(code.ifBlank{"已过期"},fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.headlineMedium)}}
                                IconButton(onClick={copy(code,"配对码已复制")},enabled=code.isNotBlank()){Icon(Icons.Outlined.ContentCopy,"复制配对码")}
                            }
                            Text(if(pairing!=null)"请求已发出，确认后即可继续；中断时重试原命令。"else if(seconds>0)"约 ${((seconds+59)/60)} 分钟内有效 · 确认一次后无需反复配对"else "生成新的配对码后再连接。",style=MaterialTheme.typography.bodySmall)
                            if(pairing!=null)LinearProgressIndicator(Modifier.fillMaxWidth())
                            if(code.isBlank())Button(onClick={code=host.store.newCode()}){Text("生成配对码")}
                            else{
                                Button(onClick={manualConnect=!manualConnect},modifier=Modifier.fillMaxWidth()){Text(if(manualConnect)"收起连接命令"else "使用命令连接")}
                                if(manualConnect||!ai){
                                    OutlinedTextField(mount,{mount=it;prefs.edit().putString("mount",it).apply()},modifier=Modifier.fillMaxWidth(),label={Text("开发环境中的 mcpy 目录")},supportingText={Text("填写挂载后的路径；不需要选择具体作品。")},singleLine=true,isError=!mount.startsWith('/'))
                                    if(mount.startsWith('/')){val command=host.pairingCommand(kind,mount,code);CommandBlock(command){copy(command,"已复制，在开发环境中运行即可")}}
                                }
                                OutlinedButton(onClick={copy(host.bootstrapPrompt(kind),"已复制，包含上方地址与配对码，可自行调整")},modifier=Modifier.fillMaxWidth()){Text("复制给 AI 的连接说明")}
                            }
                            Text(if(overlay)"请求到达时，在弹出的确认卡中点击「允许连接」。"else if(notifications)"请求到达时，从通知中点击「允许连接」。"else "请求到达后，回到此页确认连接。",style=MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick={advance(0)}){Text("返回准备工具")}
                    }}
                }
                if(adding)TextButton(onClick={adding=false}){Text("返回已连接工具")}
            }else{
                ElevatedCard{Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){Text(if(host.online)"工具可以连接"else "连接已暂停",style=MaterialTheme.typography.titleLarge);Text(if(host.online)"已配对的工具可读写作品、构建和调试游戏"else "恢复后，外部工具才能继续操作",style=MaterialTheme.typography.bodyMedium)}
                        if(starting||host.starting)CircularProgressIndicator(Modifier.size(24.dp))else TextButton(onClick={if(host.online)HostService.stop(context.applicationContext as Application)else start()}){Text(if(host.online)"暂停连接"else "恢复连接")}
                    }
                    host.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                    Text("回到你的工具选择作品，开始编辑和运行。游戏会通过小窗显示。",style=MaterialTheme.typography.bodyMedium)
                    if(!overlay)OutlinedButton(onClick={allowOverlay()}){Text("允许临时提示")}
                }}
                val active=tasks.filter{it.optString("state") in listOf("queued","running","waiting_preparation","waiting_user")}
                if(active.isNotEmpty()){
                    Text("正在进行",style=MaterialTheme.typography.titleMedium)
                    active.take(3).forEach{task->OutlinedCard{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text(task.optString("project").substringAfterLast('/'),style=MaterialTheme.typography.titleSmall)
                        if(task.optString("state")!="waiting_user")LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(when(task.optString("state")){"queued"->"等待前一个任务完成";"waiting_user"->"等待用户继续启动，准备结果已保留";"waiting_preparation"->"开发端正在准备工具或运行脚本";else->task.optString("progress","正在执行")})
                    }}}
                }
                ListItem(headlineContent={Text("已授权 ${clients.size} 个工具")},supportingContent={Text(clients.joinToString("、"){it.getString("label")})},trailingContent={TextButton(onClick={connections=true}){Text("管理")}})
                FilledTonalButton(onClick={adding=true;advance(0)},modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text("连接其他工具")}
            }
            if(!setup||step==1){
                OutlinedCard{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("保持后台连接",style=MaterialTheme.typography.titleMedium)
                    Text(if(host.application.diagnostics.background.xiaomi)
                        "切换到开发工具前，在系统省电策略中选择「无限制」，避免后台连接被暂停。"
                        else "允许 mcpy 在后台运行，避免切换到开发工具后连接被暂停。",style=MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick={
                        runCatching{context.startActivity(host.application.diagnostics.execution.settingsIntent())}.recoverCatching{
                            context.startActivity(host.application.diagnostics.execution.appDetailsIntent())
                        }.onFailure{scope.launch{snackbar.showSnackbar("请在系统设置 → 应用 → mcpy 中检查后台耗电设置")}}
                    }){Text(if(host.application.diagnostics.background.xiaomi)"打开省电设置"else "打开后台运行设置")}
                    Text("在系统页面完成设置后返回即可。",style=MaterialTheme.typography.bodySmall)
                }}
            }
            HorizontalDivider()
            TextButton(onClick={details=!details}){Text(if(details)"收起使用帮助"else "使用帮助与排错")}
            if(details){
                Text("恢复开发连接",style=MaterialTheme.typography.titleMedium)
                SelectionContainer{Text("mcpy://service/start",fontFamily=FontFamily.Monospace)}
                TextButton(onClick={copy("mcpy://service/start","恢复链接已复制")}){Text("复制恢复链接")}
                Text("链接会打开连接页，并恢复此前已开启的开发模式；主动暂停后需要在此页重新开启。",style=MaterialTheme.typography.bodySmall)
                Text("标准命令",style=MaterialTheme.typography.titleMedium)
                val workflow="mcpy --project <作品目录> build --json\nmcpy --project <作品目录> run --no-gui --detach --json"
                CommandBlock(workflow){copy(workflow)}
                Text("将 <作品目录> 换成开发环境中的实际路径。运行后，使用返回的 session 查询日志、热更或保存退出。",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={copy("请使用 mcpywrap 标准 skill 在当前开发环境中选择或新建作品。先检查 mcpy host status 的宿主能力和 launch_preconditions；出现 needs_user_action 或 host_unavailable 时立即停止自动尝试，转述 user_message 并等待我明确确认，再查询原任务；不要自行反复唤起。切换应用后断连时，请我在开发连接页打开省电设置，小米选择「无限制」；工程由 App 内核心执行，游戏通过画中画显示。用真实双端日志和截图验证源码修改；pip 字段表示画中画，不表示 Python 环境就绪。","开发说明已复制")}){Text("复制给 AI 的开发说明")}
                Text("${host.endpoint} · 核心 ${host.coreVersion.ifEmpty{"尚未读取"}}",style=MaterialTheme.typography.bodySmall)
                Text("目录权限修复或网络恢复后，重试同一条配对命令即可继续。只有请求过期才需要新配对码。",style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick={copy("mcpy 本机连接：服务 ${if(host.online)"已开启"else"已暂停"}，地址 ${host.endpoint}，核心 ${host.coreVersion}，临时提示 $overlay，通知 $notifications。检查目录挂载、mcpy host status 与协议能力。重试可恢复的原配对请求；不要输出私有令牌，不回退本地构建。","已复制排错信息，不含连接凭据")}){Text("复制排错信息")}
                val validPort=port.toIntOrNull()?.takeIf{it in 1024..65535}
                OutlinedTextField(port,{port=it},label={Text("端口")},enabled=!host.online&&!host.starting,singleLine=true,isError=validPort==null)
                TextButton(onClick={validPort?.let{host.port=it}},enabled=!host.online&&!host.starting&&validPort!=null){Text("保存端口")}
            }
        }
    }
    if(tools)ModalBottomSheet(onDismissRequest={tools=false}){
        Text("选择开发工具",Modifier.padding(horizontal=24.dp),style=MaterialTheme.typography.titleLarge)
        listOf("Minis" to "AI 对话与文件编辑", "Termux" to "使用命令行开发", "其他工具" to "编辑器、脚本或 HTTP 客户端").forEach{(name,description)->
            fun select(){kind=name;ai=name=="Minis";environment=if(name=="Minis")"Alpine / Minis"else if(name=="Termux")"Termux 原生"else "Debian / Ubuntu";mount=if(name=="Minis")"/var/minis/mounts/mcpy"else "/sdcard/mcpy";prefs.edit().putString("tool",name).putBoolean("ai",ai).putString("environment",environment).putString("mount",mount).apply();tools=false;copied=false}
            ListItem(modifier=Modifier.clickable{select()},headlineContent={Text(name)},supportingContent={Text(description)},trailingContent={RadioButton(selected=kind==name,onClick={select()})})
        };Spacer(Modifier.height(24.dp))
    }
    if(connections)ModalBottomSheet(onDismissRequest={connections=false}){
        Text("已连接的工具",Modifier.padding(horizontal=24.dp),style=MaterialTheme.typography.titleLarge)
        clients.forEach{client->ListItem(headlineContent={Text(client.getString("label"))},supportingContent={Text("可编辑作品、构建和调试游戏")},trailingContent={TextButton(onClick={host.revoke(client.getString("id"));if(clients.size==1)connections=false}){Text("断开")}})}
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun CommandBlock(command:String,onCopy:()->Unit){
    Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.medium){
        Column(Modifier.fillMaxWidth().padding(12.dp)){
            SelectionContainer{Text(command,Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}
            TextButton(onClick=onCopy,modifier=Modifier.align(Alignment.End)){Icon(Icons.Outlined.ContentCopy,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("复制命令")}
        }
    }
}
