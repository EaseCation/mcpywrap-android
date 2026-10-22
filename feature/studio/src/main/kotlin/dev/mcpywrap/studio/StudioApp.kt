package dev.mcpywrap.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mcpywrap.editor.SourceEditor
import dev.mcpywrap.workspace.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioApp(vm: StudioViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar=remember{SnackbarHostState()}
    LaunchedEffect(s.notice){s.notice?.let{message->val result=snackbar.showSnackbar(message,actionLabel=if(s.undoTrash!=null)"撤销" else null,withDismissAction=true);if(result==SnackbarResult.ActionPerformed)vm.undo() else vm.clearNotice()}}
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
    var dialog by remember{mutableStateOf("")};var input by remember{mutableStateOf("")};var url by remember{mutableStateOf("")}
    var target by remember{mutableStateOf<WorkFile?>(null)}
    var world by remember{mutableStateOf<WorkWorld?>(null)}
    var filter by remember(s.selected?.folder,s.directory){mutableStateOf("")}
    var pendingName by remember{mutableStateOf("")}
    var activeMenu by remember{mutableStateOf<String?>(null)}
    val archive=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.zip(uri,pendingName)}
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null)vm.folder(uri,pendingName)}
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)vm.refresh()};lifecycle.lifecycle.addObserver(observer);onDispose{lifecycle.lifecycle.removeObserver(observer)}}
    if(s.newProject!=null){
        NewProjectScreen(s.newProject!!,s.newProjectPreview,s.busy,s.waitModal,s.operationTitle,s.progress,s.error,vm::changeNewProject,
            vm::closeNewProject,vm::editNewProject,vm::previewNewProject,vm::createNewProject,vm::dismiss,vm::cancelPreview)
        return
    }
    fun back(){when{ s.opened!=null -> if(s.dirty)dialog="discard" else vm.closeFile();s.tab!=0->vm.tab(0);s.instancesOpen->vm.instances(false);s.selected!=null && s.directory.isNotEmpty()->vm.directory(s.directory.substringBeforeLast('/',""));s.selected!=null->vm.closeProject();s.tab!=0->vm.tab(0) }}
    BackHandler(s.opened!=null||s.selected!=null||s.tab!=0){back()}
    fun ask(kind:String,value:String=""){input=value;dialog=kind}
    fun run(project:WorkProject,selectedWorld:WorkWorld?=null){world=selectedWorld;if(s.phase in listOf("loading","running","saving")){dialog="apply"}else vm.run(project,selectedWorld)}
    Scaffold(
        snackbarHost={SnackbarHost(snackbar)},
        topBar={TopAppBar(title={Column{Text(if(s.opened!=null)s.opened!!.path.substringAfterLast('/') else if(s.tab==0)s.selected?.let{if(s.instancesOpen)it.name+" · 测试实例" else it.name}?:"作品" else "设置",maxLines=1,overflow=TextOverflow.Ellipsis);if(s.opened!=null)Text(if(s.dirty)"未保存" else "已保存",style=MaterialTheme.typography.labelSmall)}},
            navigationIcon={if(s.selected!=null||s.opened!=null)IconButton(onClick={back()}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")}},
            actions={if(s.opened!=null){TextButton(onClick=vm::save,enabled=s.dirty&&!s.busy){Text("保存")}}else if(s.permission){IconButton(onClick=vm::refresh,enabled=!s.busy){Icon(Icons.Outlined.Refresh,"刷新")}}})},
        bottomBar={if(s.opened==null)NavigationBar{listOf("作品","设置").forEachIndexed{i,label->NavigationBarItem(selected=s.tab==i,onClick={vm.tab(i)},icon={Icon(when(i){0->Icons.Outlined.Folder;else->Icons.Outlined.Settings},null)},label={Text(label)})}}}
    ){padding->Column(Modifier.fillMaxSize().padding(padding)){
        if(s.busy&&!s.waitModal&&s.workspaceLoaded)LoadingFeedback(s.progress,Modifier.fillMaxWidth().padding(12.dp).testTag("operation-progress"))
            if(s.workspaceLoaded&&s.installing&&s.tab!=1){
                Surface(color=MaterialTheme.colorScheme.secondaryContainer){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){Text(s.installText,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);TextButton(onClick={vm.tab(1)}){Text("查看下载")}}
                    s.installPercent?.let{LinearProgressIndicator(progress={it.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())}?:LinearProgressIndicator(Modifier.fillMaxWidth())
                }}
            }
        if(!s.permission){
            Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center){
                Icon(Icons.Outlined.FolderOpen,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp));Text("文件就在手机里",style=MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp));Text("内部存储 → mcpy\n源码、模组和世界都保存在这里。")
                Spacer(Modifier.height(24.dp));Button(onClick={try{context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:${context.packageName}")))}catch(e:Exception){context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))}}){Text("允许文件访问")}
                TextButton(onClick=vm::refresh){Text("已授权，重新检查")}
            }
        }else if(!s.workspaceLoaded){
            WorkspaceInitialContent(s.workspaceLoadState,s.busy,vm::refresh)
        }else if(s.opened!=null){
            Text(s.opened!!.path,Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.labelSmall,maxLines=2)
            key(s.selected!!.folder,s.opened!!.path){SourceEditor(s.opened!!.path,s.draft,vm::draft,Modifier.weight(1f))}
        }else{
            if(s.phase!="idle"&&s.session.isNotBlank()){
                Surface(color=MaterialTheme.colorScheme.secondaryContainer){Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){Text(when(s.phase){"loading"->"游戏启动中";"running"->"游戏正在运行";"saving"->"正在保存世界";"saved"->"世界已保存";"interrupted"->"游戏已中断";else->"启动失败"},style=MaterialTheme.typography.labelLarge);if(s.phase in listOf("loading","saving"))Text(s.runtimeLoading.ifBlank{if(s.phase=="saving")"等待游戏完成存档写入" else "正在准备游戏"},style=MaterialTheme.typography.bodySmall);if(s.runtimeError.isNotBlank())Text("有运行错误 · 查看日志",style=MaterialTheme.typography.labelSmall)}
                    if(s.phase in listOf("running","loading","failed")){if(s.phase=="running")TextButton(onClick=vm::showGame){Text("游戏")};TextButton(onClick=vm::stop,enabled=!s.busy){Text("保存退出")}}
                    TextButton(onClick=vm::logs,enabled=!s.busy){Text("日志")}
                }}
            }
            when(s.tab){
                0 -> if(s.selected==null){
                    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        item{Text("从一个普通模组开始",style=MaterialTheme.typography.headlineSmall)}
                        item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button(onClick=vm::startNewProject,enabled=!s.busy){Icon(Icons.Outlined.Add,null);Text("新建作品")};OutlinedButton(onClick={ask("import","导入的作品")},enabled=!s.busy){Icon(Icons.Outlined.FileDownload,null);Text("导入")}}}
                        item{TextButton(onClick={ask("example","我的欢迎语")},enabled=!s.busy){Text("欢迎语示例")}}
                        if(s.projects.isEmpty()&&s.canShowEmptyContent)item{Card{Column(Modifier.padding(20.dp)){Text("先试一个示例",style=MaterialTheme.typography.titleLarge);Text("打开 client.py 改代码，进入世界看结果。",Modifier.padding(vertical=12.dp));FilledTonalButton(onClick={ask("example","我的欢迎语")}){Text("使用示例")}}}}
                        items(s.projects,key={it.id}){project->ElevatedCard(onClick={vm.select(project)},enabled=!s.busy){ListItem(headlineContent={Text(project.name)},supportingContent={Text("内部存储 / mcpy / projects / ${project.folder}",maxLines=2,overflow=TextOverflow.Ellipsis)},leadingContent={Icon(Icons.Outlined.Folder,null)},trailingContent={Icon(Icons.Outlined.ChevronRight,null)})}}
                        item{Text("/sdcard/mcpy/projects",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                }else if(s.instancesOpen){
                    val p=s.selected!!
                    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            Button(onClick={ask("world","开发测试"+(s.worlds.size+1))},enabled=!s.busy){Icon(Icons.Outlined.Add,null);Text("新建测试实例")}
                            TextButton(onClick={vm.instances(false)}){Text("返回文件")}
                        }}
                        if(s.worlds.isEmpty()&&s.canShowEmptyContent)item{Text("开始测试时会自动创建默认实例，也可以在这里新建。")}
                        items(s.worlds,key={it.id}){w->Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically){Text(w.name,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);if(w.id==s.worlds.firstOrNull()?.id)Text("默认",style=MaterialTheme.typography.labelMedium)}
                            Text(if(w.revision.isBlank())"尚未运行" else "上次运行版本 ${w.revision.take(8)}",style=MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                                TextButton(onClick={run(p,w)},enabled=!s.busy){Text("测试当前代码")}
                                TextButton(onClick={vm.backup(w)},enabled=!s.busy){Text("备份")}
                                TextButton(onClick={world=w;dialog="delete-instance"},enabled=!s.busy,modifier=Modifier.testTag("delete-instance-${w.id}")){Text("删除")}
                            }
                        }}}
                        if(s.legacyWorlds.isNotEmpty())item{OutlinedCard{Column(Modifier.padding(16.dp)){
                            Text("有 ${s.legacyWorlds.size} 个旧版世界尚未归属作品",style=MaterialTheme.typography.bodyMedium)
                            TextButton(onClick={dialog="legacy-worlds"}){Text("从旧版恢复到此作品")}
                        }}}
                        item{Text("/sdcard/mcpy/worlds/${p.folder}/",style=MaterialTheme.typography.labelSmall)}
                    }
                }else{
                    val p=s.selected!!
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(onClick={run(p)},enabled=!s.busy,modifier=Modifier.weight(1f)){Icon(Icons.Outlined.PlayArrow,null);Text(if(s.phase=="running")"应用修改" else "开始测试")}
                        OutlinedButton(onClick={vm.instances(true)},enabled=!s.busy,modifier=Modifier.testTag("project-instances")){Text("测试实例")}
                        Box{IconButton(onClick={activeMenu="project"}){Icon(Icons.Outlined.MoreVert,"作品操作")};DropdownMenu(expanded=activeMenu=="project",onDismissRequest={activeMenu=null}){
                            DropdownMenuItem(text={Text("重命名作品")},onClick={activeMenu=null;ask("rename-project",p.name)})
                            DropdownMenuItem(text={Text("移到回收站")},onClick={activeMenu=null;dialog="delete-project"})
                            DropdownMenuItem(text={Text("导出 ZIP")},onClick={activeMenu=null;vm.export()})
                            DropdownMenuItem(text={Text("在文件管理器中打开")},onClick={activeMenu=null;try{val uri=android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:mcpy/projects/${p.folder}");context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"vnd.android.document/directory").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}catch(e:Exception){vm.error("系统文件管理器无法定位，请打开内部存储 → mcpy → projects")}})
                            DropdownMenuItem(text={Text("复制目录路径")},onClick={activeMenu=null;(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("路径","/sdcard/mcpy/projects/${p.folder}"))})
                        }}
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
                        TextButton(onClick={vm.directory("")}){Text("文件")}
                        var accumulated="";s.directory.split('/').filter{it.isNotEmpty()}.forEach{part->accumulated=listOf(accumulated,part).filter{it.isNotEmpty()}.joinToString("/");val path=accumulated;Text("/");TextButton(onClick={vm.directory(path)}){Text(part)}}
                    }
                    Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
                        OutlinedTextField(filter,{filter=it},Modifier.weight(1f),placeholder={Text("筛选当前文件夹")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true)
                        Box{IconButton(onClick={activeMenu="new"},enabled=!s.busy){Icon(Icons.Outlined.CreateNewFolder,"新建文件或文件夹")};DropdownMenu(expanded=activeMenu=="new",onDismissRequest={activeMenu=null}){DropdownMenuItem(text={Text("新建文件")},onClick={activeMenu=null;ask("file","script.py")});DropdownMenuItem(text={Text("新建文件夹")},onClick={activeMenu=null;ask("directory","新文件夹")})}}
                    }
                    LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(vertical=8.dp)){
                        val visible=s.files.filter{it.name.contains(filter,true)}
                        if(visible.isEmpty()&&s.canShowEmptyContent)item{Text(if(filter.isNotBlank())"没有匹配的文件" else "文件夹为空",Modifier.padding(24.dp))}
                        items(visible,key={it.path}){file->ListItem(modifier=Modifier.clickable(enabled=!s.busy){if(file.directory)vm.directory(file.path)else vm.open(file.path)},
                            headlineContent={Text(file.name,maxLines=1,overflow=TextOverflow.Ellipsis)},supportingContent={Text(if(file.directory)"文件夹" else if(file.bytes<1024)"${file.bytes} B" else "${file.bytes/1024} KB")},
                            leadingContent={Icon(if(file.directory)Icons.Outlined.Folder else if(file.name.endsWith(".py"))Icons.Outlined.Code else Icons.Outlined.Description,null,tint=MaterialTheme.colorScheme.primary)},
                            trailingContent={Box{IconButton(onClick={activeMenu=file.path}){Icon(Icons.Outlined.MoreVert,"${file.name} 的操作")};DropdownMenu(expanded=activeMenu==file.path,onDismissRequest={activeMenu=null}){
                                DropdownMenuItem(text={Text("重命名 / 移动")},onClick={activeMenu=null;target=file;ask("move",file.path)})
                                if(!file.directory)DropdownMenuItem(text={Text("用其他应用打开")},onClick={activeMenu=null;try{val uri=FileProvider.getUriForFile(context,"${context.packageName}.provider",File("/sdcard/mcpy/projects/${p.folder}/${file.path}"));context.startActivity(Intent.createChooser(Intent(if(file.name.endsWith(".png"))Intent.ACTION_VIEW else Intent.ACTION_EDIT).setDataAndType(uri,if(file.name.endsWith(".png"))"image/png" else "text/plain").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),"打开文件"))}catch(e:Exception){vm.error("没有可打开此文件的应用")}})
                                DropdownMenuItem(text={Text("移到回收站")},onClick={activeMenu=null;target=file;dialog="delete"})
                            }}})}
                    }
                    Text("/sdcard/mcpy/projects/${p.folder}",Modifier.padding(horizontal=16.dp,vertical=6.dp),style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                1 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
                    Text("运行环境",style=MaterialTheme.typography.headlineSmall)
                    Card{Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        Text(if(s.runtimeReady)"已安装" else "安装官方开发者内核",style=MaterialTheme.typography.titleLarge)
                        Text("3.10.100.299889 · ARM64 · 离线开发",style=MaterialTheme.typography.labelMedium)
                        Text(s.installText)
                        if(s.installing){s.installPercent?.let{LinearProgressIndicator(progress={it},modifier=Modifier.fillMaxWidth());Text("${(it*100).toInt()}%") }?:LinearProgressIndicator(Modifier.fillMaxWidth());OutlinedButton(onClick=vm::pauseInstall){Text("暂停下载")}}
                        else if(!s.runtimeReady){Text("下载约 2.16 GB，建议保留 7 GB 空间。完成后自动校验；返回作品可继续编辑。",style=MaterialTheme.typography.bodyMedium);Button(onClick={if(android.os.Build.VERSION.SDK_INT>=33)notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS);vm.install()}){Text("下载并安装")}}
                    }}
                    ListItem(headlineContent={Text("默认小窗运行")},supportingContent={Text("启动后回到编辑器，保留真实游戏画面")},trailingContent={Switch(s.smallWindow,vm::setSmallWindow)})
                    Card { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("工程核心",style=MaterialTheme.typography.titleLarge)
                        Text(s.coreVersion?.let{"mcpywrap-core $it"}?:if(s.busy)"正在读取版本" else "尚未读取核心版本")
                        Text("负责依赖解析、组装与导出，可单独更新。",style=MaterialTheme.typography.bodyMedium)
                        if(s.coreUpdate!=null) Button(onClick=vm::updateCore,enabled=!s.busy){Text("更新到 ${s.coreUpdate}")}
                        else OutlinedButton(onClick=vm::checkCoreUpdate,enabled=!s.busy){Text("检查更新")}
                        if(s.coreCanRollback)TextButton(onClick=vm::rollbackCore,enabled=!s.busy){Text("恢复上一版")}
                    } }
                    Text("工作目录",style=MaterialTheme.typography.titleLarge)
                    Text("内部存储 → mcpy\n/sdcard/mcpy")
                    Text("projects · 源码\nmods · 运行版本\nworlds · 按作品分组的测试实例\nexports · 导出与备份\nlogs · 运行日志\ntrash · 删除的文件",style=MaterialTheme.typography.bodyMedium)
                    TextButton(onClick={dialog="trash"}){Text("回收站（${s.trash.size}）")}
                    TextButton(onClick={context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:${context.packageName}")))}){Text("管理文件访问权限")}
                    HorizontalDivider();Text("编辑器：Sora Editor 0.24.4 · LGPL-2.1\nPython 高亮：MagicPython · MIT\nGit：Eclipse JGit · EDL-1.0",style=MaterialTheme.typography.bodySmall)
                    Text("已内置 Python 工程核心。支持普通模组和已准备好的本地组件依赖；Git/pip 依赖获取与构建脚本环境尚未接入。",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }}
    if(s.error!=null)AlertDialog(onDismissRequest=vm::dismiss,title={Text("需要处理")},text={Text(s.error!!,Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick=vm::dismiss){Text("知道了")}})

    if(s.busy&&s.waitModal)ModalWait(s.operationTitle,s.progress)
    if(dialog in listOf("example","import","git","file","directory","move","world","rename-project")){
        AlertDialog(onDismissRequest={dialog=""},title={Text(when(dialog){"example"->"使用欢迎语示例";"import"->"导入作品";"git"->"从 Git 导入";"file"->"新建文件";"directory"->"新建文件夹";"move"->"重命名 / 移动";"rename-project"->"重命名作品";else->"新建测试实例"})},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(input,{input=it},label={Text(if(dialog=="move")"项目内的目标路径" else "名称")},singleLine=true)
            if(dialog=="git"){OutlinedTextField(url,{url=it},label={Text("HTTPS 仓库地址")},placeholder={Text("https://github.com/作者/仓库.git")});Text("克隆公开仓库的默认分支；不会执行安装脚本。",style=MaterialTheme.typography.bodySmall)}
            if(dialog=="import"){Text("导入为独立作品，保留原文件。");FilledTonalButton(onClick={pendingName=input;dialog="";archive.launch(arrayOf("*/*"))},enabled=input.isNotBlank()){Text("ZIP / mcpack / mcaddon")};OutlinedButton(onClick={pendingName=input;dialog="";folder.launch(null)},enabled=input.isNotBlank()){Text("选择文件夹")};TextButton(onClick={dialog="git"},enabled=input.isNotBlank()){Text("粘贴 Git 地址")}}
        }},confirmButton={if(dialog!="import")TextButton(onClick={when(dialog){"example"->vm.create(input,"welcome");"git"->vm.git(url,input);"file"->vm.newEntry(input,false);"directory"->vm.newEntry(input,true);"move"->vm.move(target!!.path,input);"world"->vm.newWorld(input);"rename-project"->vm.renameProject(input)};dialog=""},enabled=input.isNotBlank()&&(dialog!="git"||url.isNotBlank())){Text(if(dialog=="git")"导入" else "确定")}},dismissButton={TextButton(onClick={dialog=""}){Text("取消")}})
    }
    if(dialog=="trash")AlertDialog(onDismissRequest={dialog=""},title={Text("回收站")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())){if(s.trash.isEmpty()&&s.canShowEmptyContent)Text("回收站为空");s.trash.forEach{entry->ListItem(headlineContent={Text(entry.label)},trailingContent={TextButton(onClick={vm.restoreTrash(entry.id)},enabled=!s.busy,modifier=Modifier.testTag("restore-trash-${entry.id}")){Text("恢复")}})}}},confirmButton={TextButton(onClick={dialog=""}){Text("返回")}})
    if(dialog=="delete-project")AlertDialog(onDismissRequest={dialog=""},title={Text("把作品移到回收站？")},text={Text("作品源码和它的所有测试实例一起移到回收站，可一起恢复。已导出的备份保留。")},confirmButton={TextButton(onClick={dialog="";vm.deleteProject()}){Text("移走")}},dismissButton={TextButton(onClick={dialog=""}){Text("取消")}})
    if(dialog=="discard")AlertDialog(onDismissRequest={dialog=""},title={Text("文件还没保存")},text={Text("保存后，修改才会写入项目文件。")},confirmButton={TextButton(onClick={dialog="";vm.save()}){Text("保存")}},dismissButton={Row{TextButton(onClick={dialog=""}){Text("继续编辑")};TextButton(onClick={dialog="";vm.closeFile()}){Text("放弃修改")}}})
    if(dialog=="delete")AlertDialog(onDismissRequest={dialog=""},title={Text("移到回收站？")},text={Text(target?.name?:"")},confirmButton={TextButton(onClick={dialog="";vm.trash(target!!.path)}){Text("移走")}},dismissButton={TextButton(onClick={dialog=""}){Text("取消")}})
    if(dialog=="apply")AlertDialog(onDismissRequest={dialog=""},title={Text("保存当前世界后应用修改？")},text={Text("保存完成后，用当前文件重新启动选定世界。")},confirmButton={TextButton(onClick={dialog="";s.selected?.let{vm.run(it,world,true)}}){Text("保存并应用")}},dismissButton={TextButton(onClick={dialog=""}){Text("取消")}})
    if(dialog=="delete-instance")AlertDialog(onDismissRequest={dialog=""},title={Text("删除测试实例？")},text={Text("“${world?.name}”的存档和部署文件会移到回收站，作品源码不受影响。正在运行的实例需先保存退出。")},confirmButton={TextButton(onClick={dialog="";world?.let(vm::deleteWorld)}){Text("移到回收站")}},dismissButton={TextButton(onClick={dialog=""}){Text("取消")}})
    if(dialog=="legacy-worlds")AlertDialog(onDismissRequest={dialog=""},title={Text("恢复到 ${s.selected?.name}")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())){
        s.legacyWorlds.forEach{legacy->TextButton(onClick={dialog="";vm.adoptLegacy(legacy)},enabled=!s.busy){Text(legacy.name)}}
    }},confirmButton={TextButton(onClick={dialog=""}){Text("返回")}})
}
