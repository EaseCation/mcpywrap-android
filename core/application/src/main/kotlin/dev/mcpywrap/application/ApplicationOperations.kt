package dev.mcpywrap.application

import android.app.Application
import android.content.Intent
import dev.mcpy.runtime.RuntimeRepository
import dev.mcpywrap.engineering.EngineeringClient
import dev.mcpywrap.workspace.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ApplicationFailure(val code:String,message:String,val state:String="failed",val details:JSONObject=JSONObject()):IllegalStateException(message)

/** Application operations are shared by Compose and the authenticated host API. */
class ApplicationOperations private constructor(private val app:Application) {
    companion object {
        @Volatile private var instance:ApplicationOperations?=null
        fun get(app:Application)=instance?:synchronized(this){instance?:ApplicationOperations(app).also{instance=it}}
        val operations=listOf("doctor","init","mod","add","remove","sync","build","package","script.list","script.run",
            "run","status","logs","stop","restart","runtime.capabilities","runtime.py","runtime.py-result","runtime.reload","screenshot",
            "files.write","files.trash","projects.rename","projects.trash","instances.list","instances.create","instances.trash")
    }
    val repository=WorkspaceRepository()
    private val engineering=EngineeringClient(app)
    val diagnostics=LaunchDiagnostics(app,repository)
    val pendingLaunches=PendingLaunchStore(File(app.noBackupFilesDir,"launch-handoff.json"),File(repository.root,"logs"))
    @Volatile var foreground=false
    private val mutations=Mutex()
    suspend fun <T> mutate(block:suspend()->T):T=mutations.withLock{withContext(Dispatchers.IO){block()}}
    suspend fun core(operation:String,folder:String?=null,options:JSONObject?=null,progress:(String)->Unit={},prepare:(suspend(String)->String)?=null):JSONObject=
        engineering.request(operation,folder,options=options,onProgress=progress,onPreparation=prepare)

    private fun project(path:String):WorkProject {
        require(path.startsWith("projects/")&&path.removePrefix("projects/").split('/').size==1){"请选择 mcpy/projects 中的作品"}
        val folder=WorkspaceRepository.name(path.removePrefix("projects/"))
        return repository.projects().firstOrNull{it.folder==folder}?:error("作品不存在")
    }
    private fun session(project:WorkProject,id:String?=null):JSONObject {
        val record=if(id.isNullOrBlank())repository.session()?:error("还没有运行会话")else{
            require(id.matches(Regex("session-[a-f0-9-]{36}"))){"无效会话 ID"}
            JSONObject(File(WorkspaceRepository.inside(File(repository.root,"logs"),id),"launch.json").readText())
        }
        require(record.getString("projectId")==project.id){"会话不属于当前作品"}
        return record
    }
    fun resolveCurrentSessionProject(record:JSONObject):WorkProject = repository.projects().firstOrNull{it.id==record.optString("projectId")}
        ?: throw ApplicationFailure("project_unavailable","当前测试所属的作品已不存在，请先从回收站恢复作品")
    fun resolveCurrentSessionInstance(record:JSONObject,project:WorkProject):WorkWorld = repository.worlds(project).firstOrNull{it.id==record.optString("instanceId")&&it.projectId==project.id}
        ?: throw ApplicationFailure("instance_unavailable","当前测试实例已不可用，请返回作品选择或恢复实例")

    /** Caller holds mutate(): recheck the exact active session after waiting for other operations. */
    suspend fun restartCurrentSession(expectedSession:String,smallWindow:Boolean=true,progress:(String)->Unit={},
        prepare:(suspend(String)->String)?=null,external:Boolean=false,owner:JSONObject?=null,launchTimeout:Int=45):JSONObject {
        val record=repository.session()?:throw ApplicationFailure("session_unavailable","还没有测试记录")
        val id=File(record.getString("session")).name
        require(id==File(expectedSession).name){"当前测试已被其他操作更新，请查看新的运行卡片"}
        val project=resolveCurrentSessionProject(record)
        val world=resolveCurrentSessionInstance(record,project)
        val observed=repository.sessionStatus(record)!!
        require(observed.optString("phase")!="saving"){"游戏正在保存，请保存完成后再重新测试"}
        // Invalidate a delayed Activity request before cancelling its prepared session.
        pendingLaunch()?.let{pending->
            require(pending.getString("session")==id){"另一个启动请求正在处理，请先查看它的状态"}
            pendingLaunches.transition(id,"cancelled","user_restart","用户已在 App 重新测试")
            val latest=repository.sessionStatus(record)!!
            if(latest.optInt("pid")<=0||latest.opt("process_alive")==false){
                WorkspaceRepository.atomic(File(WorkspaceRepository.inside(File(repository.root,"logs"),id),"status.json"),JSONObject().put("phase","cancelled").put("code","launch_cancelled").put("error","已重新测试，原启动已取消").toString().toByteArray())
            }else{
                progress("正在保存上一次游戏")
                val stopped=stopCurrent()
                check(stopped.optString("state")=="saved"){"没有确认保存成功，已停止重新测试"}
                delay(500)
            }
        }
        return start(project,world,replace=true,smallWindow=smallWindow,progress=progress,prepare=prepare,
            external=external,owner=owner,launchTimeout=launchTimeout,previousSession=id)
    }
    private suspend fun awaitGameExit(record:JSONObject){
        val observed=repository.sessionStatus(record)?:return
        if(observed.optInt("pid")<=0||observed.opt("process_alive")==false)return
        try{withTimeout(15_000){while(repository.sessionStatus(record)?.opt("process_alive")!=false)delay(100)}}
        catch(e:TimeoutCancellationException){throw ApplicationFailure("game_exit_pending","游戏仍在退出，请稍后重新测试；原实例保持不变")}
    }
    suspend fun start(project:WorkProject,world:WorkWorld?=null,replace:Boolean=false,smallWindow:Boolean=true,
                      progress:(String)->Unit={},prepare:(suspend(String)->String)?=null,external:Boolean=false,owner:JSONObject?=null,launchTimeout:Int=45,previousSession:String?=null):JSONObject {
        require(launchTimeout in 5..120){"启动确认等待需为 5..120 秒"}
        pendingLaunch()?.let{throw waitingFailure(it.getString("session"),it.optString("reason","launch_requires_user"))}
        diagnostics.requireReady(external&&!foreground,smallWindow,allowDeferred=external)
        if(repository.active()){
            require(replace){"游戏正在运行，请先保存退出"}
            session(project)
            progress("正在保存上一次游戏")
            repository.requestStop()
            withTimeout(90_000){while(repository.active())delay(250)}
            check(repository.sessionStatus()?.optString("phase")=="saved"){"未确认保存成功"}
            repository.session()?.let{awaitGameExit(it)}
        }else{
            repository.session()?.takeIf{repository.sessionStatus(it)?.optString("phase")=="saved"}?.let{awaitGameExit(it)}
        }
        progress(if(previousSession!=null)"正在重新构建作品"else "解析依赖并组装作品")
        val built=core("command",project.folder,
            JSONObject().put("operation","prepare_run").put("parameters",JSONObject()),progress,prepare)
        progress(if(previousSession!=null)"正在启动同一个测试实例"else "正在部署到测试实例")
        val plan=repository.prepareRun(project,world?:repository.defaultWorld(project),File(built.getString("output")))
        val sources=built.getJSONObject("source_packs")
        val launch=JSONObject(File(plan.session,"launch.json").readText()).put("source_packs",sources).put("configuration_digest",configurationDigest(project,sources)).put("launch_external",external).put("small_window",smallWindow).put("launch_timeout_seconds",launchTimeout)
        if(previousSession!=null)launch.put("previous_session",previousSession)
        WorkspaceRepository.atomic(File(plan.session,"launch.json"),launch.toString().toByteArray())
        WorkspaceRepository.atomic(File(repository.root,"logs/active.json"),launch.toString().toByteArray())
        launch.put("core_version",built.getString("coreVersion"))
        WorkspaceRepository.atomic(File(plan.session,"launch.json"),launch.toString().toByteArray())
        LaunchEvidence.record(plan.session,"app","deployed")
        pendingLaunches.create(launch,owner)
        return launchPrepared(launch,external,smallWindow)
    }
    private suspend fun launchPrepared(launch:JSONObject,external:Boolean,smallWindow:Boolean):JSONObject {
        val directory=WorkspaceRepository.inside(File(repository.root,"logs"),File(launch.getString("session")).name)
        val backgroundRequest=external&&!foreground
        val attempt=pendingLaunches.read()!!.getString("attempt")
        fun history(outcome:String,code:String=""){runCatching{diagnostics.history.record(attempt,backgroundRequest,outcome,code,diagnostics.environment)}}
        fun receipt(stage:String){LaunchEvidence.record(directory,"app",stage);WorkspaceRepository.atomic(File(directory,"launch-receipt.json"),JSONObject().put("stage",stage).put("observed_at",System.currentTimeMillis()).toString().toByteArray())}
        val eligibility=diagnostics.preflight(external&&!foreground,smallWindow)
        if(!eligibility.getBoolean("ready")){
            val blockers=eligibility.getJSONArray("blockers")
            val blocker=(0 until blockers.length()).map{blockers.getJSONObject(it)}.firstOrNull{it.optString("code")=="vendor_background_launch_denied"}?:blockers.getJSONObject(0)
            history("waiting_user",blocker.getString("code"))
            deferLaunch(launch,blocker.getString("code"))
        }
        history("requested")
        receipt("launch_requested")
        try{withContext(Dispatchers.Main){
            val directPip=smallWindow
            require(!directPip||android.os.Build.VERSION.SDK_INT>=33){"此系统不支持直接启动游戏画中画，请关闭默认小窗运行"}
            val target=if(directPip)"net.easecation.mcpywrap.GamePipLauncherActivity"else "com.mojang.minecraftpe.MainActivity"
            val intent=Intent().setClassName(app,target)
                .putExtra("data",launch.getString("data")).putExtra("session",directory.path).putExtra("smallWindow",smallWindow)
                .putExtra("title","mcpy · ${launch.getString("project")}")
                .putExtra("launchAttempt",pendingLaunches.read()!!.getString("attempt"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or (if(directPip)Intent.FLAG_ACTIVITY_CLEAR_TOP else 0))
            app.startActivity(intent);receipt("activity_request_sent")
        }}
        catch(error:Exception){
            if(error is SecurityException){history("waiting_user","launch_blocked_by_os");deferLaunch(launch,"launch_blocked_by_os")}
            val code="activity_launch_failed"
            history("failed",code)
            pendingLaunches.transition(directory.name,"failed")
            WorkspaceRepository.atomic(File(directory,"status.json"),JSONObject().put("phase","failed").put("code",code).put("error",error.message).toString().toByteArray())
            throw ApplicationFailure(code,error.message?:"游戏启动请求失败",details=diagnostics.observe(launch))
        }
        // Separate process acknowledgement from world loading. A timed-out Activity request
        // remains a prepared handoff, never a request to re-run scripts or create another world.
        try{withTimeout(launch.optInt("launch_timeout_seconds",20).toLong()*1000){while(true){
            val status=repository.sessionStatus()
            if(status?.optString("phase") in listOf("failed","interrupted")){
                val observed=diagnostics.observe(launch)
                history("failed",observed.optString("code","game_start_failed"))
                pendingLaunches.transition(directory.name,"failed")
                throw ApplicationFailure(observed.optString("code").ifBlank{"game_start_failed"},observed.optString("error","游戏启动失败"),details=observed)
            }
            if(status!=null&&status.optInt("pid",0)>0&&(!smallWindow||status.optBoolean("pip")))break
            delay(100)
        }}}
        catch(error:TimeoutCancellationException){
            val observed=diagnostics.observe(launch)
            if(observed.optString("phase") in listOf("failed","interrupted")){
                history("failed",observed.optString("code","game_start_failed"))
                pendingLaunches.transition(directory.name,"failed")
                throw ApplicationFailure(observed.optString("code","game_start_failed"),observed.optString("error"),details=observed)
            }
            history("unconfirmed",if(observed.optInt("pid")>0)"game_presentation_pending"else "background_launch_unconfirmed")
            deferLaunch(launch,if(observed.optInt("pid")>0)"game_presentation_pending"else "background_launch_unconfirmed")
        }
        history("started")
        receipt("presentation_confirmed")
        pendingLaunches.transition(directory.name,"started")
        return JSONObject().put("session",directory.name).put("state",repository.sessionStatus()?.optString("phase")?:"loading").put("coreVersion",launch.optString("core_version")).apply{if(launch.has("previous_session"))put("previous_session",launch.getString("previous_session")).put("session_changed",true).put("hint","会话已更换，后续日志、截图和调试请使用新 session；旧会话记录仍保留。") }
    }
    fun pendingLaunch():JSONObject? {
        val pending=pendingLaunches.read()?:return null
        if(pending.optString("state") !in listOf("dispatching","waiting_user","presentation_waiting"))return null
        val id=pending.getString("session")
        val record=runCatching{JSONObject(File(WorkspaceRepository.inside(File(repository.root,"logs"),id),"launch.json").readText())}.getOrNull()
        val observed=record?.let{repository.sessionStatus(it)}
        if(observed!=null&&observed.optInt("pid")>0){
            if(observed.opt("process_alive")==false||observed.optString("phase") in listOf("saved","failed","interrupted")){
                pendingLaunches.transition(id,if(observed.optString("phase")=="saved")"finished"else "failed");return null
            }
            if(observed.optBoolean("pip")||record.optBoolean("small_window",true)==false){pendingLaunches.transition(id,"started");return null}
            if(pending.optString("state")!="presentation_waiting")pendingLaunches.transition(id,"presentation_waiting","game_presentation_pending","游戏进程已启动，切回 mcpy 恢复画面")
        }
        return pendingLaunches.pending()
    }
    fun waitingLaunchResult(id:String,reason:String):JSONObject=waitingFailure(id,reason).details
    private fun waitingFailure(id:String,reason:String):ApplicationFailure {
        val record=JSONObject(File(WorkspaceRepository.inside(File(repository.root,"logs"),id),"launch.json").readText())
        val observed=diagnostics.observe(record)
        val started=observed.optInt("pid")>0&&observed.opt("process_alive")!=false
        val preflight=observed.getJSONObject("launch_preconditions")
        val blockers=preflight.getJSONArray("blockers")
        val blocker=(0 until blockers.length()).map{blockers.getJSONObject(it)}.firstOrNull{it.optString("code")==reason}
        val guidance=blocker?.optString("hint")?.takeIf{it.isNotBlank()}?:diagnostics.background.inspect().getString("hint")
        val hint=if(started)"游戏进程已启动，切回 mcpy 恢复画面；不要重新 run 或创建世界。"else
            "作品已准备，游戏尚未启动。$guidance。切回 mcpy 继续原会话，不要重复 run --new。"
        val details=observed.put("prepared",true).put("game_started",started).put("state","waiting_user")
            .put("reason",reason).put("hint",hint).put("recovery_uri","mcpy://service/start").put("needs_user_action",true)
            .put("expires_at",if(started)JSONObject.NULL else pendingLaunches.read()?.opt("expires_at")?:JSONObject.NULL)
            .put("coreVersion",record.optString("core_version"))
            .put("actions",JSONArray().put(JSONObject().put("kind","open_app").put("uri","mcpy://service/start"))
                .put(JSONObject().put("kind","settings").put("background_launch",diagnostics.background.inspect())))
        UserIntervention.attach(details,reason)
        return ApplicationFailure(reason,if(started)"游戏画面等待恢复"else "后台启动需要用户继续，已保留准备结果","waiting_user",details)
    }
    private fun deferLaunch(record:JSONObject,reason:String):Nothing {
        val id=File(record.getString("session")).name
        val started=(repository.sessionStatus(record)?.optInt("pid",0)?:0)>0
        pendingLaunches.transition(id,if(started)"presentation_waiting"else "waiting_user",reason,
            "切回 mcpy 继续同一会话，或先在设置中允许后台弹出界面")
        LaunchEvidence.record(File(record.getString("session")),"app","waiting_user",JSONObject().put("reason",reason))
        throw waitingFailure(id,reason)
    }
    suspend fun resumePendingLaunch():JSONObject? {
        val pending=pendingLaunch()?:return null
        if(pending.getString("state")=="dispatching")return null
        val id=pending.getString("session")
        val record=JSONObject(File(WorkspaceRepository.inside(File(repository.root,"logs"),id),"launch.json").readText())
        require(File(repository.session()?.optString("session").orEmpty()).name==id){"待启动会话已被替换"}
        require(record.getString("projectId")==pending.getString("project_id")&&record.getString("instanceId")==pending.getString("instance_id"))
        val observed=repository.sessionStatus(record)!!
        if(observed.optInt("pid")>0){
            if(observed.opt("process_alive")==false||observed.optString("phase") in listOf("failed","interrupted","saved")){
                pendingLaunches.transition(id,"failed")
                throw ApplicationFailure("prepared_process_ended","原游戏进程已结束，请重新运行原实例",details=diagnostics.observe(record))
            }
            if(!observed.optBoolean("pip")&&observed.optInt("bufferWidth")>0)showGame()
            if(observed.optBoolean("pip"))pendingLaunches.transition(id,"started")
            return observed
        }
        try{diagnostics.requireReady(false,record.optBoolean("small_window",true))}
        catch(e:ApplicationFailure){deferLaunch(record,e.code)}
        pendingLaunches.retry(id)
        return launchPrepared(record,external=true,smallWindow=record.optBoolean("small_window",true))
    }
    suspend fun stopCurrent():JSONObject {
        val record=repository.session()?:return JSONObject().put("state","idle")
        val id=File(record.getString("session")).name
        val pending=pendingLaunch()?.takeIf{it.getString("session")==id}
        if(pending!=null&&((repository.sessionStatus(record)?.optInt("pid",0)?:0)==0||repository.sessionStatus(record)?.opt("process_alive")==false)){
            pendingLaunches.transition(id,"cancelled")
            // A prepared handoff has no process that can acknowledge save-exit. Mark
            // the session terminal so an explicit in-app retry can reuse its instance
            // without waiting forever on repository.active().
            WorkspaceRepository.atomic(File(WorkspaceRepository.inside(File(repository.root,"logs"),id),"status.json"),JSONObject()
                .put("phase","cancelled").put("code","launch_cancelled").put("error","启动已由用户取消").toString().toByteArray())
            return JSONObject().put("state","cancelled").put("session",id).put("saved",false)
        }
        repository.requestStop()
        withTimeout(90_000){while(repository.active())delay(250)}
        if(repository.sessionStatus(record)?.optString("phase")=="saved")awaitGameExit(record)
        if(pending!=null)pendingLaunches.transition(id,"finished")
        return JSONObject().put("state",repository.sessionStatus()?.optString("phase"))
    }
    suspend fun showGame() {
        require(repository.active()){ "游戏已经退出" }
        val config=repository.session()!!
        withContext(Dispatchers.Main){app.startActivity(Intent().setClassName(app,"com.mojang.minecraftpe.MainActivity")
            .putExtra("data",config.getString("data")).putExtra("session",config.getString("session")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))}
    }
    suspend fun game(project:WorkProject,id:String?,action:String,code:String="",side:String="client"):JSONObject {
        val record=session(project,id)
        require(record.getString("session")==repository.session()?.getString("session")){"游戏会话已结束或被替换"}
        val observed=diagnostics.observe(record)
        val capturable=observed.opt("process_alive")==true&&observed.optInt("bufferWidth")>0&&observed.optInt("bufferHeight")>0
        if(if(action=="screenshot")!capturable else observed.optString("phase")!="running")
            throw ApplicationFailure(if(action=="screenshot")"game_surface_not_ready"else "game_runtime_not_ready","游戏${if(action=="screenshot")"画面"else "运行时"}尚未就绪",details=observed)
        val directory=File(app.noBackupFilesDir,"game-control/"+File(record.getString("session")).name)
        val identity=UUID.randomUUID().toString().replace("-","")
        val response=File(directory,"$identity.json")
        require(directory.listFiles().orEmpty().none{it.name.endsWith(".running")}&&!File(directory,"request.json").exists()&&!File(directory,"screenshot-request.json").exists()){ "游戏已有请求等待处理" }
        WorkspaceRepository.atomic(File(directory,if(action=="screenshot")"screenshot-request.json"else "request.json"),JSONObject().put("id",identity).put("action",action).put("code",code).put("side",side).toString().toByteArray())
        try{withTimeout(30_000){while(!response.exists())delay(100)}}
        catch(error:TimeoutCancellationException){throw ApplicationFailure("game_result_unknown","游戏请求结果未知，请查询会话，不要重复发送代码","unknown",JSONObject().put("request_id",identity).put("session",File(record.getString("session")).name))}
        val value=JSONObject(response.readText())
        check(value.optBoolean("ok")){value.optString("error",value.toString())}
        return if(action=="screenshot")value else value.getJSONObject("reply").getJSONObject("ret").also{it.put("ok",it.optString("state")=="completed")}
    }
    private fun configurationDigest(project:WorkProject,sources:JSONObject):String {
        val base=repository.projectDir(project.folder)
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        val paths=listOf("pyproject.toml","mcpy-git.lock.json","mcpy-code-libraries.lock.json")+sources.keys().asSequence().flatMap{kind->sequenceOf(sources.getString(kind)+"/manifest.json",sources.getString(kind)+"/pack_manifest.json")}.toList()
        for(path in paths.sorted()){val file=WorkspaceRepository.inside(base,path);digest.update(path.toByteArray());if(file.isFile)digest.update(file.readBytes())}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private suspend fun reload(project:WorkProject,id:String?,params:JSONObject):JSONObject {
        require(params.optString("kind")=="python"){"当前仅支持 Python 热更"}
        val side=params.optString("side","client");require(side in listOf("client","server"))
        val current=session(project,id)
        require(current.optString("configuration_digest")==configurationDigest(project,current.getJSONObject("source_packs"))){"项目配置或组件身份已变化，请重新运行作品"}
        val sourceRoot=WorkspaceRepository.inside(repository.projectDir(project.folder),current.getJSONObject("source_packs").getString("behavior"))
        val filename=params.optString("filename").takeIf{it.isNotBlank()}
        val module=if(filename!=null){
            val file=WorkspaceRepository.inside(repository.projectDir(project.folder),filename)
            require(file.path.startsWith(sourceRoot.path+"/")&&file.extension=="py")
            file.relativeTo(sourceRoot).invariantSeparatorsPath.removeSuffix(".py").replace('/','.').removeSuffix(".__init__")
        }else params.optString("module")
        require(module.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*"))){"请指定当前作品的 Python 模块"}
        val source=if(filename!=null)WorkspaceRepository.inside(repository.projectDir(project.folder),filename)else{
            val file=WorkspaceRepository.inside(sourceRoot,module.replace('.','/')+".py")
            if(file.isFile)file else WorkspaceRepository.inside(sourceRoot,module.replace('.','/')+"/__init__.py")
        }
        require(source.isFile){"模块不属于当前作品，依赖源码变更请重新运行"}
        val bytes=source.readBytes();require(bytes.size<=24000){"热更模块源码超过 24 KiB，与标准 mcpy 限制一致"}
        val inspect=game(project,id,"execute","import sys\n_result = {'file':sys.modules[${JSONObject.quote(module)}].__file__}",side)
        check(inspect.optBoolean("ok")){inspect.optString("error")}
        val deployed=File(inspect.getJSONObject("value").getString("file")).canonicalFile
        val roots=current.getJSONArray("sourceRoots")
        require((0 until roots.length()).any{deployed.path.startsWith(File(roots.getString(it)).canonicalPath+"/")}){"模块不在当前部署目录"}
        val encoded=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
        val compile=game(project,id,"execute","import base64\ncompile(base64.b64decode('$encoded'), '<mcpy-reload>', 'exec')\n_result = True",side)
        if(!compile.optBoolean("ok"))return compile
        require(bytes.contentEquals(source.readBytes())){"热更期间源码已变化，请重新提交"}
        WorkspaceRepository.atomic(deployed,bytes)
        return game(project,id,"execute","import sys, base64\n_module = sys.modules[${JSONObject.quote(module)}]\nexec compile(base64.b64decode('$encoded'), _module.__file__, 'exec') in _module.__dict__\n_result = {'module':${JSONObject.quote(module)},'instance_recreated':False}",side)
    }
    fun logs(path:String,id:String?,after:Long?=null,tail:Int=100,source:String="game",contains:String?=null,summary:Boolean=false,since:Long?=null,until:Long?=null):JSONObject {
        val owner=project(path);val record=session(owner,id);val directory=File(record.getString("session"))
        if(source=="all"){
            require(after==null){"all 返回分源 cursors；续读请分别指定 game 或 engine"}
            val streams=JSONObject();val cursors=JSONObject();val combined=StringBuilder()
            for(kind in listOf("game","engine")){
                val value=logs(path,id,tail=tail,source=kind,contains=contains,summary=summary,since=since,until=until)
                streams.put(kind,value);cursors.put(kind,value.getLong("cursor"))
                if(value.getString("text").isNotBlank())combined.append("[$kind]\n").append(value.getString("text")).append("\n")
            }
            return JSONObject().put("text",combined.toString()).put("streams",streams).put("cursors",cursors).put("session",directory.name)
        }
        require(source in listOf("game","client","server","engine")){"未知日志来源"}
        val value=SessionLogs.read(File(directory,if(source=="engine")"runtime.log"else "game.jsonl"),source,after,tail,contains,summary,since,until).put("session",directory.name)
        if(!value.getBoolean("available"))value.put("hint","尚无此来源日志；不能据此认定模组已加载或功能正常")
        return value
    }
    suspend fun execute(operation:String,path:String,parameters:JSONObject,progress:(String)->Unit={},prepare:(suspend(String)->String)?=null,owner:JSONObject?=null):JSONObject {
        val params=JSONObject(parameters.toString())
        params.keys().asSequence().toList().filter{params.isNull(it)}.forEach{params.remove(it)}
        require(operation in operations){"未提供此能力：$operation"}
        if(operation in listOf("run","restart"))require(params.optInt("launch_timeout",45) in 5..120){"启动确认等待需为 5..120 秒"}
        if(operation=="doctor")return core("status").put("runtime_ready",RuntimeRepository(app).installed()!=null).put("launch_preconditions",diagnostics.preflight()).put("operations",JSONArray(operations))
        if(operation=="restart"||(operation=="run"&&!params.optBoolean("list")&&!params.optBoolean("clean_all")&&params.optString("delete").isBlank()))
            diagnostics.requireReady(!foreground,params.isNull("background")||params.optBoolean("background"),allowDeferred=true)
        repository.initialize()
        if(operation=="init"){
            require(params.optString("project_type","addon")=="addon"){"手机宿主仅支持 Addon"}
            require(path.startsWith("projects/")&&path.removePrefix("projects/").split('/').size==1){"作品必须位于 projects 下"}
            val folder=WorkspaceRepository.name(path.removePrefix("projects/"))
            val target=repository.projectDir(folder);target.mkdirs();repository.projects()
        }
        val project=project(path)
        val id=params.optString("session").takeIf{it.isNotBlank()}
        return when(operation){
            "files.write"->{
                val relative=params.getString("path")
                require(relative.split('/').none{it==".mcpy-project.json"}){"作品标识不能通过文件接口修改"}
                val creating=params.optBoolean("create");val directory=params.optBoolean("directory")
                val text=if(directory)""else params.getString("text")
                require(text.toByteArray().size<=WorkspaceRepository.MAX_TEXT)
                require(!directory||creating){"目录操作需要 create"}
                if(creating)repository.createEntry(project.folder,relative.substringBeforeLast('/',""),relative.substringAfterLast('/'),directory)
                if(directory)JSONObject().put("path",relative).put("directory",true)
                else{
                    val digest=if(creating)repository.open(project.folder,relative).digest else params.getString("digest")
                    val saved=repository.save(project.folder,OpenFile(relative,"",digest),text)
                    JSONObject().put("path",saved.path).put("digest",saved.digest)
                }
            }
            "files.trash"->JSONObject().put("trash_id",repository.trash(project.folder,params.getString("path")))
            "projects.rename"->{val updated=repository.renameProject(project,params.getString("name"));JSONObject().put("id",updated.id).put("path","projects/"+updated.folder).put("name",updated.name)}
            "projects.trash"->JSONObject().put("trash_id",repository.trashProject(project))
            "instances.list"->JSONObject().put("instances",JSONArray(repository.worlds(project).map{JSONObject().put("id",it.id).put("name",it.name).put("project_id",project.id)}))
            "instances.create"->{val world=repository.createWorld(params.getString("name"),project);JSONObject().put("id",world.id).put("name",world.name).put("project_id",project.id)}
            "instances.trash"->{val world=repository.worlds(project).single{it.id==params.getString("instance")};JSONObject().put("trash_id",repository.trashWorld(project,world))}
            "run"->{
                require(!params.optBoolean("mcs_auth")&&params.isNull("cppconfig")&&params.optInt("clients",1)==1){"此宿主仅支持本地单实例开发"}
                for(key in listOf("game_executable","game_executable_path","mcs_download_path","engine_version"))require(params.isNull(key)||params.optString(key).isBlank()){"此宿主不使用桌面引擎路径或版本覆盖"}
                if(params.optBoolean("clean_all")){
                    require(params.optBoolean("force")){"批量移除实例需要 --force"}
                    repository.worlds(project).forEach{repository.trashWorld(project,it)};JSONObject().put("deleted",true)
                }else if(params.optBoolean("list"))JSONObject().put("instances",JSONArray(repository.worlds(project).map{JSONObject().put("id",it.id).put("name",it.name)}))
                else if(params.optString("delete").isNotBlank()){
                    val world=repository.worlds(project).first{it.id.startsWith(params.getString("delete"))};repository.trashWorld(project,world);JSONObject().put("deleted",world.id)
                }else{
                    pendingLaunch()?.let{throw waitingFailure(it.getString("session"),it.optString("reason","launch_requires_user"))}
                    val current=repository.session()
                    if(repository.active()&&current?.optString("projectId")==project.id&&!params.optBoolean("new")&&params.optString("instance_prefix").isBlank()){
                        return JSONObject().put("session",File(current.getString("session")).name).put("state",repository.sessionStatus()?.optString("phase")).put("reused",true)
                    }
                    diagnostics.requireReady(!foreground,params.isNull("background")||params.optBoolean("background"),allowDeferred=true)
                    val worlds=repository.worlds(project)
                    val prefix=params.optString("instance_prefix").takeIf{it.isNotBlank()}
                    val world=if(params.optBoolean("new"))repository.createWorld("开发测试${worlds.size+1}",project)else prefix?.let{p->worlds.single{it.id.startsWith(p)}}
                    start(project,world,smallWindow=params.isNull("background")||params.optBoolean("background"),progress=progress,prepare=prepare,external=true,owner=owner,launchTimeout=params.optInt("launch_timeout",45))
                }
            }
            "restart"->{val current=session(project,id);restartCurrentSession(current.getString("session"),progress=progress,prepare=prepare,external=true,owner=owner,launchTimeout=params.optInt("launch_timeout",45))}
            "status"->{
                val current=if(id!=null)session(project,id)else repository.session()
                val value=if(current!=null)diagnostics.observe(current)else JSONObject().put("phase","idle")
                if(current?.optString("projectId")==project.id)value.put("session",File(current.getString("session")).name)
                else return JSONObject().put("state","idle").put("sessions",JSONArray())
                value.put("state",value.optString("phase"));if(params.optBoolean("list"))JSONObject().put("sessions",JSONArray().put(value))else value
            }
            "stop"->{val selected=session(project,id);require(selected.getString("session")==repository.session()?.getString("session")){"会话已经结束"};stopCurrent()}
            "logs"->logs(path,id,tail=params.optInt("tail",100),source=params.optString("source","game"),contains=params.optString("contains").takeIf{params.has("contains")},summary=params.optBoolean("summary"),since=params.optLong("since_ms").takeIf{params.has("since_ms")},until=params.optLong("until_ms").takeIf{params.has("until_ms")})
            "runtime.capabilities"->{
                val record=session(project,id)
                val active=record.getString("session")==repository.session()?.optString("session")&&repository.sessionStatus()?.optString("phase")=="running"
                val server=active&&repository.sessionStatus()?.optBoolean("serverReady")==true
                val reload=JSONArray();if(active)reload.put("python.client");if(server)reload.put("python.server")
                JSONObject().put("python",JSONObject().put("client",active).put("server",server)).put("screenshot",repository.sessionStatus()?.let{it.opt("process_alive")==true&&it.optInt("bufferWidth")>0}==true).put("reload",reload).put("input",false)
            }
            "runtime.py"->{require(!params.optBoolean("no_wait")&&!params.has("wait_until")){"此宿主暂不支持 Python 排队条件或 --no-wait"};val side=params.optString("side","client");require(side in listOf("client","server"));require(params.getString("code").toByteArray().size<=32768){"代码超过 32 KiB"};game(project,id,"execute",params.getString("code"),side)}
            "runtime.py-result"->{
                require(!params.optBoolean("cancel")){"此宿主不提供 Python 请求队列取消"}
                val record=session(project,id);val requestId=params.getString("request_id")
                require(requestId.matches(Regex("[a-f0-9]{32}"))){"无效游戏请求 ID"}
                val directory=File(app.noBackupFilesDir,"game-control/"+File(record.getString("session")).name)
                val response=File(directory,"$requestId.json")
                if(!response.isFile)JSONObject().put("ok",false).put("state","unknown").put("code","game_result_unknown")
                    .put("request_id",requestId).put("error","尚未收到结果，不要重复发送代码")
                else{
                    val value=JSONObject(response.readText())
                    if(!value.optBoolean("ok"))value
                    else value.getJSONObject("reply").getJSONObject("ret").also{it.put("ok",it.optString("state")=="completed").put("request_id",requestId)}
                }
            }
            "runtime.reload"->reload(project,id,params)
            "screenshot"->game(project,id,"screenshot")
            else->{
                val clean=JSONObject(params.toString())
                core("command",project.folder,JSONObject().put("operation",operation).put("parameters",clean),progress,prepare)
            }
        }
    }
}
