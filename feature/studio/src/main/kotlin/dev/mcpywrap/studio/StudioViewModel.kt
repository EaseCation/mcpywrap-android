package dev.mcpywrap.studio

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dev.mcpy.runtime.RuntimeInstallWorker
import dev.mcpy.runtime.RuntimeRepository
import dev.mcpywrap.workspace.*
import dev.mcpywrap.engineering.EngineeringClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

enum class ContentLoadState { NOT_LOADED, LOADING, READY, ERROR }

data class ProductState(
    val permission: Boolean = false, val projects: List<WorkProject> = emptyList(), val worlds: List<WorkWorld> = emptyList(),
    val selected: WorkProject? = null, val directory: String = "", val files: List<WorkFile> = emptyList(),
    val opened: OpenFile? = null, val draft: String = "", val busy: Boolean = false, val progress: String = "",
    val waitModal:Boolean=false,val operationTitle:String="",val workspaceLoaded:Boolean=false,
    val workspaceLoadState:ContentLoadState=ContentLoadState.NOT_LOADED,
    val error: String? = null, val notice: String? = null, val undoTrash: String? = null,
    val runtimeReady: Boolean = false, val installing: Boolean = false, val installText: String = "正在检查运行环境", val installPercent: Float? = null,
    val phase: String = "idle", val runtimeError: String = "", val session: String = "", val smallWindow: Boolean = true,val runtimeLoading:String="",
    val tab: Int = 0, val instancesOpen: Boolean = false, val legacyWorlds: List<WorkWorld> = emptyList(), val trash: List<TrashItem> = emptyList(),
    val coreVersion: String? = null, val coreUpdate: String? = null, val coreChecked: Boolean = false, val coreCanRollback: Boolean = false,
    val newProject:NewProjectDraft?=null, val newProjectPreview:org.json.JSONObject?=null
) {
    val dirty: Boolean get() = opened!=null && opened.text!=draft
    val canShowEmptyContent:Boolean get() = workspaceLoaded&&workspaceLoadState==ContentLoadState.READY&&!busy
}

class StudioViewModel(app: Application): AndroidViewModel(app) {
    val repository=WorkspaceRepository()
    private var draftJob: Job? = null
    private var operationJob:Job?=null
    private val runtime=RuntimeRepository(app)
    private val importer=ProjectImporter(app,repository)
    private val engineering=EngineeringClient(app)
    private val application=dev.mcpywrap.application.ApplicationOperations.get(app)
    private var coreInspected=false
    private val mutable=MutableStateFlow(ProductState(smallWindow=app.getSharedPreferences("settings",0).getBoolean("pip",true)))
    val state=mutable.asStateFlow()
    init {
        refresh()
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(RuntimeInstallWorker.NAME).collect { list ->
                val activeId=app.getSharedPreferences("installer",0).getString("request",null)
                val info=list.firstOrNull{!it.state.isFinished} ?: list.firstOrNull{it.id.toString()==activeId} ?: return@collect
                val ready=withContext(Dispatchers.IO){runtime.installed()!=null}
                val progress=info.progress;val total=progress.getLong("total",0);val bytes=progress.getLong("bytes",0)
                mutable.update{it.copy(runtimeReady=ready,installing=!info.state.isFinished,installText=when{
                    ready->"运行环境已就绪"
                    info.state==androidx.work.WorkInfo.State.FAILED->info.outputData.getString("error")?:"安装失败，可以重试"
                    info.state==androidx.work.WorkInfo.State.CANCELLED->"已暂停，继续时会保留下载进度"
                    else->progress.getString("phase")?:"等待网络并准备下载"
                },installPercent=if(total>0)bytes.toFloat()/total else null)}
            }
        }
        viewModelScope.launch { while(isActive){delay(2000);if(state.value.permission){runCatching{withContext(Dispatchers.IO){repository.sessionStatus() to repository.session()}}.onSuccess{(status,config)->mutable.update{it.copy(phase=status?.optString("phase")?:"idle",runtimeError=status?.optString("error")?:"",runtimeLoading=status?.optString("loadingStage")?:"",session=config?.optString("session")?:"")}}} } }
    }
    private fun action(label: String, modal:Boolean=false, block: suspend ()->Unit) {
        if(state.value.busy)return
        mutable.update{it.copy(busy=true,progress=label,operationTitle=label,waitModal=modal,error=null)}
        operationJob=viewModelScope.launch {
            try { application.mutate{require(Environment.isExternalStorageManager()){ "需要允许所有文件访问，文件仍保留在 /sdcard/mcpy" };repository.initialize();block();reload()} }
            catch(e:CancellationException){
                mutable.update{if(it.workspaceLoadState==ContentLoadState.LOADING)it.copy(workspaceLoadState=if(it.workspaceLoaded)ContentLoadState.READY else ContentLoadState.NOT_LOADED)else it}
                throw e
            }
            catch(e:Exception){mutable.update{it.copy(error=e.message?:"操作失败，请重试")}}
            finally{mutable.update{it.copy(busy=false,progress="",waitModal=false,operationTitle="")}}
        }
    }
    private fun reload() {
        try {
        val projects=repository.projects();repository.migrateLegacyWorlds()
        val selected=state.value.selected?.let{old->projects.find{it.folder==old.folder}}
        val files=selected?.let{repository.list(it.folder,state.value.directory)}?:emptyList()
        val worlds=selected?.let{repository.worlds(it)}?:emptyList()
        val legacy=repository.legacyWorlds();val trash=repository.trashItems();val ready=runtime.installed()!=null
        mutable.update{it.copy(projects=projects,worlds=worlds,legacyWorlds=legacy,selected=selected,files=files,trash=trash,runtimeReady=ready,workspaceLoaded=true,workspaceLoadState=ContentLoadState.READY,
            installText=if(ready)"运行环境已就绪" else if(it.installText=="正在检查运行环境")"尚未安装" else it.installText)}
        }catch(error:Exception){mutable.update{it.copy(workspaceLoadState=ContentLoadState.ERROR)};throw error}
    }
    private suspend fun coreRequest(operation:String,folder:String?=null,version:String?=null,options:org.json.JSONObject?=null)=
        engineering.request(operation,folder,version,options,onProgress={message->mutable.update{if(it.busy)it.copy(progress=message,waitModal=it.waitModal||(operation=="update"&&message.contains("校验并安装")))else it}})
    fun refresh() {
        val allowed=Environment.isExternalStorageManager();mutable.update{it.copy(permission=allowed)}
        if(allowed&&!state.value.busy)action("读取工作区"){
            mutable.update{it.copy(workspaceLoadState=ContentLoadState.LOADING)}
            reload()
            if(!coreInspected){applyCoreStatus(coreRequest("status"));coreInspected=true}
        }
    }
    // Workspace/core status is shared across tabs; navigation must not cancel its first load.
    private fun cancelReading(){if(state.value.busy&&!state.value.waitModal&&state.value.operationTitle in setOf("打开作品","打开文件夹","读取文件","生成结构预览"))operationJob?.cancel()}
    fun tab(value:Int){cancelReading();mutable.update{it.copy(tab=value)}}
    fun error(message:String){mutable.update{it.copy(error=message)}}
    fun dismiss(){mutable.update{it.copy(error=null)}}
    fun clearNotice(){mutable.update{it.copy(notice=null,undoTrash=null)}}
    fun create(title:String,template:String)=action("创建作品",modal=true) { val project=repository.createProject(title,template);selectInternal(project) }
    fun startNewProject(){if(!state.value.busy)mutable.update{it.copy(newProject=NewProjectDraft(),newProjectPreview=null,error=null)}}
    fun changeNewProject(draft:NewProjectDraft){if(!state.value.busy)mutable.update{it.copy(newProject=draft,newProjectPreview=null,error=null)}}
    fun closeNewProject(){if(!state.value.busy||(!state.value.waitModal&&state.value.operationTitle=="生成结构预览")){cancelReading();mutable.update{it.copy(newProject=null,newProjectPreview=null,error=null)}}}
    fun cancelPreview(){if(state.value.operationTitle=="生成结构预览"&&!state.value.waitModal){operationJob?.cancel();mutable.update{it.copy(newProjectPreview=null)}}}
    fun editNewProject(){if(!state.value.busy)mutable.update{it.copy(newProjectPreview=null,error=null)}}
    fun previewNewProject()=action("生成结构预览"){
        val draft=requireNotNull(state.value.newProject)
        val preview=coreRequest("preview_project",options=draft.json())
        mutable.update{it.copy(newProjectPreview=preview)}
    }
    fun createNewProject()=action("创建模组",modal=true){
        val draft=requireNotNull(state.value.newProject);val preview=requireNotNull(state.value.newProjectPreview)
        val result=coreRequest("create_project",options=draft.json().put("previewSignature",preview.getString("signature")))
        val stage=File(result.getString("stage")).canonicalFile
        require(stage.parentFile==File(repository.root,"build").canonicalFile&&stage.name.startsWith("new-mod-")){"无效的生成目录"}
        try {
            val project=repository.publishImport(stage,draft.title.trim(),"")
            mutable.update{it.copy(progress="正在保存作品到公共目录")}
            selectInternal(project)
            mutable.update{it.copy(newProject=null,newProjectPreview=null,notice="模组已创建，打开 ${draft.scriptDir}/modMain.py 开始编辑")}
        } finally {stage.deleteRecursively()}
    }
    fun select(project:WorkProject)=action("打开作品"){selectInternal(project)}
    private suspend fun selectInternal(project:WorkProject){
        val files=repository.list(project.folder);val worlds=repository.worlds(project)
        currentCoroutineContext().ensureActive()
        mutable.update{it.copy(selected=project,directory="",files=files,worlds=worlds,opened=null,draft="",tab=0,instancesOpen=false)}
    }
    fun instances(value:Boolean){mutable.update{it.copy(instancesOpen=value)}}
    fun closeProject(){cancelReading();mutable.update{it.copy(selected=null,opened=null,directory="",draft="",worlds=emptyList(),instancesOpen=false)}}
    fun directory(path:String)=action("打开文件夹"){val p=state.value.selected?:return@action;val files=repository.list(p.folder,path);currentCoroutineContext().ensureActive();mutable.update{it.copy(directory=path,files=files)}}
    fun open(path:String)=action("读取文件"){val p=state.value.selected?:return@action;val f=repository.open(p.folder,path);val recovery=repository.readDraft(p.folder,path);currentCoroutineContext().ensureActive();mutable.update{it.copy(opened=recovery?.first?:f,draft=recovery?.second?:f.text,notice=if(recovery!=null)"已恢复上次未保存的草稿" else null)}}
    fun draft(text:String){
        mutable.update{it.copy(draft=text)}
        draftJob?.cancel();val s=state.value;val f=s.opened?:return;val p=s.selected?:return
        draftJob=viewModelScope.launch {delay(500);withContext(Dispatchers.IO){if(Environment.isExternalStorageManager())repository.storeDraft(p.folder,f,text)}}
    }
    fun closeFile()=action("关闭文件") {draftJob?.cancelAndJoin();val s=state.value;if(s.selected!=null&&s.opened!=null)repository.discardDraft(s.selected.folder,s.opened.path);mutable.update{it.copy(opened=null,draft="")}}
    fun save() {
        val s=state.value;val p=s.selected?:return;val f=s.opened?:return
        action("保存文件",modal=true){draftJob?.cancelAndJoin();val saved=repository.save(p.folder,f,s.draft);repository.discardDraft(p.folder,f.path);mutable.update{it.copy(opened=saved,notice="已保存到公共目录")}}
    }
    fun newEntry(name:String,folder:Boolean)=action("创建文件",modal=true){val s=state.value;repository.createEntry(s.selected!!.folder,s.directory,name,folder)}
    fun move(path:String,destination:String)=action("移动文件",modal=true){repository.move(state.value.selected!!.folder,path,destination)}
    fun trash(path:String)=action("移到回收站",modal=true){val id=repository.trash(state.value.selected!!.folder,path);mutable.update{it.copy(undoTrash=id,notice="已移到 /sdcard/mcpy/trash，可撤销")}}
    fun undo()=action("恢复文件",modal=true){state.value.undoTrash?.let{repository.restore(it)};mutable.update{it.copy(undoTrash=null,notice="已恢复")}}
    fun zip(uri:Uri,title:String)=action("导入组件",modal=true){val input=requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri));val p=input.use{repository.importZip(it,title)};selectInternal(p)}
    fun folder(uri:Uri,title:String)=action("导入文件夹",modal=true){selectInternal(importer.folder(uri,title))}
    fun git(url:String,title:String)=action("连接 Git 仓库"){selectInternal(importer.git(url,title){message->mutable.update{it.copy(progress=message)}})}
    fun renameProject(name:String)=action("重命名作品",modal=true){val p=repository.renameProject(state.value.selected!!,name);selectInternal(p)}
    fun deleteProject()=action("移到回收站",modal=true){val id=repository.trashProject(state.value.selected!!);mutable.update{it.copy(selected=null,directory="",files=emptyList(),worlds=emptyList(),instancesOpen=false,undoTrash=id,notice="作品和测试实例已移到回收站")}}
    fun restoreTrash(id:String)=action("恢复文件",modal=true){repository.restore(id);mutable.update{it.copy(notice="已恢复")}}
    fun export()=action("组装并导出组件"){
        require(!state.value.dirty){"请先保存正在编辑的文件"}
        val result=coreRequest("package",state.value.selected!!.folder)
        mutable.update{it.copy(notice="已导出：${result.getString("artifact")}",coreVersion=result.getString("coreVersion"))}
    }
    private fun applyCoreStatus(result:org.json.JSONObject){mutable.update{it.copy(coreVersion=result.getString("version"),coreCanRollback=result.optJSONObject("selection")?.optString("current").let{current->!current.isNullOrBlank()&&current!="null"})}}
    fun checkCoreUpdate()=action("检查工程核心更新"){
        val result=coreRequest("check_update")
        mutable.update{it.copy(coreChecked=true,coreUpdate=if(result.getBoolean("available"))result.getString("version") else null,notice=if(result.getBoolean("available"))"发现工程核心 ${result.getString("version")}" else "工程核心已是最新版本")}
    }
    fun updateCore()=action("下载并验证工程核心"){
        val version=requireNotNull(state.value.coreUpdate){"请先检查更新"}
        applyCoreStatus(coreRequest("update",version=version))
        mutable.update{it.copy(coreUpdate=null,notice="工程核心已更新，下次测试使用新版本")}
    }
    fun rollbackCore()=action("恢复上一版工程核心",modal=true){
        applyCoreStatus(coreRequest("rollback"));mutable.update{it.copy(notice="已恢复工程核心")}
    }
    fun newWorld(title:String)=action("创建测试实例",modal=true){
        val owner=requireNotNull(state.value.selected){"请先打开作品"}
        repository.createWorld(title,owner)
    }
    fun deleteWorld(world:WorkWorld)=action("删除测试实例",modal=true){
        val owner=requireNotNull(state.value.selected){"请先打开作品"}
        val id=repository.trashWorld(owner,world)
        mutable.update{it.copy(undoTrash=id,notice="测试实例已移到回收站，作品源码保留")}
    }
    fun adoptLegacy(world:WorkWorld)=action("恢复旧版世界",modal=true){
        val owner=requireNotNull(state.value.selected){"请先打开作品"}
        repository.adoptLegacyWorld(owner,world)
        mutable.update{it.copy(notice="已归入当前作品的测试实例")}
    }
    fun backup(world:WorkWorld)=action("备份世界",modal=true){val file=repository.backup(requireNotNull(state.value.selected),world);mutable.update{it.copy(notice="已备份：${file.path}")}}
    fun setSmallWindow(value:Boolean){getApplication<Application>().getSharedPreferences("settings",0).edit().putBoolean("pip",value).apply();mutable.update{it.copy(smallWindow=value)}}
    fun install(){mutable.update{it.copy(installing=true,installText="正在准备下载任务",installPercent=null)};RuntimeInstallWorker.start(getApplication())}
    fun pauseInstall(){mutable.update{it.copy(installText="正在暂停下载")};RuntimeInstallWorker.pause(getApplication())}
    fun stop()=action("结束当前会话"){application.stopCurrent()}
    fun resumeLaunch()=action("继续启动已准备的作品"){application.resumePendingLaunch()}
    fun run(project:WorkProject,world:WorkWorld?=null,replace:Boolean=false) {
        if(state.value.dirty){error("请先保存正在编辑的文件");return}
        if(!state.value.runtimeReady){mutable.update{it.copy(tab=2,notice="先安装运行环境，作品已保留")};return}
        action("准备测试世界") {
            application.start(project,world,replace,state.value.smallWindow,progress={label->mutable.update{it.copy(progress=label)}})
        }
    }
    fun showGame()=action("显示游戏"){application.showGame()}
    fun logs()=action("读取运行日志") {
        val session=state.value.session;require(session.isNotBlank()){ "还没有运行记录" }
        val file=File(session,"runtime.log");val log=if(file.isFile)java.io.RandomAccessFile(file,"r").use{f->f.seek((f.length()-16000).coerceAtLeast(0));val bytes=ByteArray((f.length()-f.filePointer).toInt());f.readFully(bytes);bytes.toString(Charsets.UTF_8)} else "暂时没有日志"
        mutable.update{it.copy(error=log)}
    }
}
