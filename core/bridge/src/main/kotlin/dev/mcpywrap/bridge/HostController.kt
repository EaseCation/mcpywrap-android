package dev.mcpywrap.bridge

import android.app.Application
import dev.mcpywrap.application.ApplicationOperations
import dev.mcpywrap.workspace.WorkspaceRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HostController private constructor(val app:Application) {
    companion object {
        @Volatile private var instance:HostController?=null
        fun get(app:Application)=instance?:synchronized(this){instance?:HostController(app).also{instance=it}}
    }
    val application=ApplicationOperations.get(app)
    val store=HostStore(File(app.filesDir,"host-service"),application.repository.root)
    private val mutable=MutableStateFlow(0L)
    val changes=mutable.asStateFlow()
    private val taskMutable=MutableStateFlow<List<JSONObject>>(emptyList())
    val tasks=taskMutable.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val running=ConcurrentHashMap<String,Job>()
    private val stages=ConcurrentHashMap<String,CompletableDeferred<String>>()
    private var server:HostServer?=null
    private var monitor:Job?=null
    @Volatile var error:String?=null;private set
    @Volatile var online=false;private set
    @Volatile var starting=false;private set
    var foreground:Boolean
        get()=application.foreground
        set(value){application.foreground=value}
    @Volatile var confirmationShowing=false
    @Volatile var gameLauncher:(suspend (suspend ()->JSONObject)->JSONObject)?=null
    val launchProgress=MutableStateFlow<String?>(null)
    fun approvePair(id:String,allow:Boolean){store.approve(id,allow);changed()}
    private val startLock=Mutex()
    @Volatile var coreVersion="";private set
    var port:Int
        get()=app.getSharedPreferences("host",0).getInt("port",17653)
        set(value){require(value in 1024..65535);check(!online&&!starting){"请先等待启动完成并停止服务"};app.getSharedPreferences("host",0).edit().putInt("port",value).apply();changed()}
    val endpoint get()="http://127.0.0.1:$port"
    fun changed(){mutable.value=mutable.value+1}
    suspend fun start()=startLock.withLock{
        if(online)return@withLock
        starting=true;changed()
        try{
            application.repository.initialize()
            val status=application.core("status")
            coreVersion=status.getString("version")
            val supported=status.getJSONObject("capabilities").getJSONArray("operations")
            require((0 until supported.length()).any{supported.getString(it)=="prepare_run"}){"请先升级工程核心，当前版本不支持本机开发接口"}
            server=HostServer(this);server!!.start(port);online=true;error=null
            event("service",JSONObject().put("state","online"));changed()
            monitor?.cancel()
            monitor=scope.launch {
                var lastState="";var session="";var cursor=0L
                while(isActive&&online){
                    runCatching {
                        application.pendingLaunches.read()?.let{pending->
                            val id=pending.optString("operation_id");val client=pending.optString("client_id")
                            if(id.isNotBlank()&&store.job(id,client).optString("state")=="waiting_user")operationStatus(id,client)
                        }
                        val current=application.repository.session()
                        if(current!=null){
                            val identity=File(current.getString("session")).name
                            if(identity!=session){session=identity;cursor=0}
                            val state=application.repository.sessionStatus()?.optString("phase").orEmpty()
                            if(lastState!=identity+state){lastState=identity+state;event("session",JSONObject().put("session",identity).put("project",current.getString("project")).put("state",state))}
                            val logs=application.logs("projects/"+current.getString("project"),identity,cursor,0)
                            cursor=logs.getLong("cursor")
                            if(logs.getString("text").isNotEmpty())event("log",logs)
                        }
                    }
                    delay(700)
                }
            }
        }catch(e:Exception){server?.stop();server=null;online=false;error="无法启动本机服务：${e.message}。如端口被占用，请修改端口。";changed();throw e}finally{starting=false;changed()}
    }
    fun serverFailed(failure:Throwable){
        // Startup failures are returned by resolvedConnectors; asynchronous failures
        // must also clear the visible service state instead of killing the UI process.
        if(online)scope.launch{
            stop();error="本机服务已停止：${failure.message?:failure.javaClass.simpleName}";changed()
            HostService.stop(app,userInitiated=false)
        }
    }
    fun stop(){monitor?.cancel();monitor=null;server?.stop();server=null;online=false;running.values.forEach{it.cancel()};stages.values.forEach{it.cancel()};changed()}
    fun capabilities()=JSONObject().put("protocol_version",2).put("installation_id",store.installationId)
        .put("launch_preconditions",application.diagnostics.preflight())
        .put("background_execution",application.diagnostics.execution.inspect())
        .put("recovery_uri","mcpy://service/start")
        .put("client_commands",JSONArray(listOf("modsdk","skill","host")))
        .put("api_only_operations",JSONArray(listOf("instances.create","instances.trash","files.write","files.trash","projects.rename","projects.trash")))
        .put("command_examples",JSONObject().put("instances.list","mcpy --project <作品目录> run --list --json").put("status","mcpy --project <作品目录> status --session <id> --json"))
        .put("log_sources",JSONArray(listOf("game","engine","client","server","all")))
        .put("cli_log_sources",JSONArray(listOf("game","engine","client","server","all")))
        .put("log_query",JSONArray(listOf("contains","summary","since_ms","until_ms")))
        .put("launch_options",JSONObject().put("ack_timeout",true).put("default_seconds",45).put("min_seconds",5).put("max_seconds",120))
        .put("pairing",JSONObject().put("resume",true).put("confirmation","overlay_or_notification")).put("core_version",coreVersion).put("operations",JSONArray(ApplicationOperations.operations))
        .put("workspace",JSONObject().put("projects","projects")).put("events","/v2/events").put("preparation",JSONArray(listOf("git_source","pip","script","environment","distribution","markers","script_recovery")))
    private val eventFile get()=File(application.repository.root,"logs/bridge/events.jsonl")
    @Synchronized fun event(kind:String,detail:JSONObject,client:String?=null){
        val record=JSONObject().put("sequence",store.nextEventSequence()).put("kind",kind).put("detail",detail).put("client",client?:JSONObject.NULL)
        eventFile.parentFile!!.mkdirs();if(eventFile.length()>8*1024*1024)eventFile.writeText("")
        eventFile.appendText(record.toString()+"\n");taskMutable.value=store.recentJobs();changed()
    }
    @Synchronized fun events(after:Long,client:String):List<JSONObject> {
        if(!eventFile.isFile)return emptyList()
        return eventFile.useLines{lines->lines.mapNotNull{runCatching{JSONObject(it)}.getOrNull()}.filter{it.getLong("sequence")>after&&(it.isNull("client")||it.optString("client")==client)}.take(256).map{it.apply{remove("client")}}.toList()}
    }
    fun submit(client:String,request:JSONObject):JSONObject {
        require(!request.optBoolean("preparation_client")||store.sharedAccess(client)){"工具准备需要先验证共享目录"}
        require(request.optString("operation") in ApplicationOperations.operations){"未提供此能力"}
        require(request.optJSONObject("parameters")!=null){"缺少 parameters"}
        val (record,fresh)=store.submit(client,request);val id=record.getString("operation_id")
        if(fresh){
            val job=scope.launch(start=CoroutineStart.LAZY){
                try{
                    val prepare:(suspend(String)->String)?=if(request.optBoolean("preparation_client")){raw->
                        val step=JSONObject(raw).put("id",UUID.randomUUID().toString());val key=id+":"+step.getString("id")
                        val result=CompletableDeferred<String>();stages[key]=result
                        store.update(id){it.put("state","waiting_preparation").put("preparation",step)}
                        event("preparation",JSONObject().put("operation_id",id).put("step_id",step.getString("id")).put("kind",step.getString("kind")),client)
                        try{withTimeout(if(step.optString("kind")=="script")((step.getJSONObject("options").getJSONObject("spec").optDouble("timeout",120.0)+30)*1000).toLong()else 900_000L){result.await()}}finally{stages.remove(key);store.update(id){it.remove("preparation");it.put("state","running")}}
                    }else null
                    val result=application.mutate{
                        store.update(id){it.put("state","running")};event("operation",JSONObject().put("operation_id",id).put("state","running"),client)
                        val operation=request.getString("operation")
                        val parameters=request.getJSONObject("parameters")
                        val execute:suspend ()->JSONObject={
                            application.execute(operation,request.getString("project"),parameters,
                                progress={label->store.update(id){it.put("progress",label)};if(launchProgress.value!=null)launchProgress.value=label;event("progress",JSONObject().put("operation_id",id).put("text",label),client)},prepare=prepare,owner=JSONObject().put("operation_id",id).put("client_id",client))
                        }
                        if(operation in listOf("run","restart")&&!parameters.optBoolean("list")&&!parameters.optBoolean("clean_all")&&parameters.optString("delete").isBlank()){
                            val presenter=gameLauncher?:throw dev.mcpywrap.application.ApplicationFailure("host_service_required","请在开发工具页开启连接服务")
                            presenter(execute)
                        }else execute()
                    }
                    if(request.getString("operation") in listOf("screenshot","package"))result.put("artifact_id",id)
                    val state=if(result.optBoolean("ok",true))"completed"else "failed"
                    store.update(id){it.put("state",state).put("result",result);if(state=="failed")it.put("error",result.optString("error","操作失败")).put("code",result.optString("code","operation_failed")).put("hint",result.optString("hint"))}
                    event("operation",JSONObject().put("operation_id",id).put("state",state),client)
                }catch(e:CancellationException){cancelPendingHandoff(id);store.update(id){it.put("state","interrupted").put("error","任务已中断，已发送的游戏请求可能继续执行；请查询状态，不要自动重发")}}
                catch(e:Exception){store.update(id){
                    it.put("state","failed").put("code","operation_failed").put("error",e.message?:"操作失败")
                    if(e is dev.mcpywrap.application.ApplicationFailure){dev.mcpywrap.workspace.UserIntervention.attach(e.details,e.code);it.put("state",e.state).put("code",e.code).put("result",e.details).put("hint",e.details.optString("hint"))}
                    if(e is dev.mcpywrap.engineering.EngineeringFailure){it.put("error",e.reason).put("code",e.code);e.hint?.let{hint->it.put("hint",hint)};e.details?.let{detail->it.put("result",detail)}}
                };event("operation",JSONObject().put("operation_id",id).put("state",store.job(id).getString("state")),client)}
                finally{running.remove(id);changed()}
            }
            running[id]=job;job.start()
        }
        return store.publicJob(record)
    }
    fun operationStatus(id:String,client:String):JSONObject {
        val original=store.job(id,client);val result=store.publicJob(original)
        val session=result.optJSONObject("result")?.optString("session").orEmpty()
        if(original.getJSONObject("request").optString("operation") in listOf("run","restart")&&session.matches(Regex("session-[a-f0-9-]{36}"))){
            runCatching{
                val record=JSONObject(File(WorkspaceRepository.inside(File(application.repository.root,"logs"),session),"launch.json").readText())
                require("projects/"+record.getString("project")==original.getJSONObject("request").getString("project"))
                val observed=application.diagnostics.observe(record)
                result.put("observation",observed)
                if(original.optString("state")=="waiting_user"){
                    val started=observed.optInt("pid")>0&&observed.opt("process_alive")==true&&
                        observed.optString("phase") in listOf("loading","running")&&(!record.optBoolean("small_window",true)||observed.optBoolean("pip"))
                    if(started||observed.optString("phase")=="saved"){
                        store.update(id){job->if(job.optString("state")=="waiting_user"){job.put("state","completed").put("result",JSONObject(observed.toString()).put("ok",true).put("coreVersion",record.optString("core_version")).put("resumed",true));job.remove("error");job.remove("code");job.remove("hint")}}
                        result.put("state","completed").put("result",store.job(id).getJSONObject("result"));result.remove("error");result.remove("code");result.remove("hint")
                        taskMutable.value=store.recentJobs();changed()
                        application.pendingLaunches.read()?.takeIf{it.optString("session")==session}?.let{application.pendingLaunches.transition(session,"started")}
                    }else if(observed.optString("phase") in listOf("cancelled","failed","interrupted")){
                        val state=if(observed.optString("phase")=="cancelled")"cancelled"else "failed"
                        store.update(id){if(it.optString("state")=="waiting_user")it.put("state",state).put("code",observed.optString("code")).put("error",observed.optString("error","启动已结束"))}
                        result.put("state",state).put("code",observed.optString("code"));taskMutable.value=store.recentJobs();changed()
                    }else{
                        val reason=if(observed.optInt("pid")>0&&observed.opt("process_alive")!=false)"game_presentation_pending"else original.optString("code","launch_requires_user")
                        val waiting=application.waitingLaunchResult(session,reason)
                        if(original.optString("code")!=reason||original.optString("hint")!=waiting.optString("hint")||original.optJSONObject("result")?.optBoolean("game_started")!=waiting.optBoolean("game_started")){
                            store.update(id){if(it.optString("state")=="waiting_user")it.put("result",waiting).put("code",reason).put("hint",waiting.optString("hint"))}
                            taskMutable.value=store.recentJobs();changed()
                        }
                    }
                }
            }
        }
        return store.publicJob(store.job(id,client)).also{if(result.has("observation"))it.put("observation",result.getJSONObject("observation"))}
    }
    suspend fun resumeFromForeground(){
        if(!foreground)return
        val pending=application.pendingLaunches.read()
        val client=pending?.optString("client_id").orEmpty()
        if(client.isNotBlank()&&store.publicClients().none{it.getString("id")==client}){
            cancelPendingHandoff(pending!!.optString("operation_id"));changed();return
        }
        runCatching{application.mutate{application.resumePendingLaunch()}}.onSuccess{error=null}.onFailure{
            error=it.message;changed()
        }
        changed()
    }
    fun completePreparation(client:String,id:String,step:String,result:JSONObject){
        if(store.acceptPreparation(id,client,step,result)){
            check(stages[id+":"+step]?.complete(result.toString())==true){"准备阶段已中断；查询原任务状态，不要重新执行脚本"}
        }
    }
    private fun cancelPendingHandoff(operation:String){
        val pending=application.pendingLaunches.read()?:return
        if(pending.optString("operation_id")!=operation)return
        val current=application.repository.session()
        val live=current?.let{application.repository.sessionStatus(it)}?.let{it.optInt("pid")>0&&it.opt("process_alive")!=false}==true
        application.pendingLaunches.transition(pending.getString("session"),if(live)"started"else "cancelled")
    }
    fun cancel(client:String,id:String){
        val job=store.job(id,client)
        if(job.optString("state")=="waiting_user"){
            cancelPendingHandoff(id)
            store.update(id){it.put("state","cancelled").put("code","launch_cancelled").put("error","启动等待已取消；已运行的游戏需另行保存退出")}
            taskMutable.value=store.recentJobs()
        }else running[id]?.cancel()
        changed()
    }
    fun revoke(client:String){
        store.revoke(client)
        application.pendingLaunches.read()?.takeIf{it.optString("client_id")==client}?.let{pending->
            val id=pending.optString("operation_id")
            cancelPendingHandoff(id)
            if(id.isNotBlank()&&store.job(id).optString("state")=="waiting_user")store.update(id){it.put("state","cancelled").put("code","client_revoked").put("error","连接已撤销，待启动请求已取消")}
        }
        running.forEach{(id,job)->if(store.job(id).getString("client_id")==client)job.cancel()}
        changed()
    }
    fun pairingCommand(kind:String,workspace:String,code:String):String {
        fun quote(value:String)="'"+value.replace("'", "'\"'\"'")+"'"
        return "mcpy host pair --url $endpoint --code $code --workspace ${quote(workspace)} --label ${quote(kind)}"
    }
    private val documentationSetup="可选准备独立的 ModSDK 文档工具：https://github.com/MCNeteaseDevs/modsdk_mcp_server.git 。按官方说明检查当前客户端是否支持 MCP；支持时配置 MCP，不支持时可调用仓库的 docs_reader 离线查询，并只在你的工具环境里添加轻量调用入口。优先按需获取文档、结构化索引与必要代码，避免默认下载教程图片和完整历史。记录来源版本，并用带客户端/服务端信息的 API 查询验证安装。该工具不是 mcpy 依赖，不加入工程或游戏包；下载失败不阻塞 mcpy 配对。"
    fun setupPrompt(kind:String):String {
        val destination=if(kind=="Minis")"/var/minis/skills/mcpywrap"else "当前 Agent 的标准技能目录"
        return """请帮我准备手机上的 mcpy 开发工具环境，暂时不配对。
使用官方 https://github.com/EaseCation/mcpywrap 的 mcpywrap，按标准 pip/uv 安装 Python CLI，并用 mcpy skill install 安装完整 skill 到 $destination。
Python、虚拟环境和 Git 放在本应用私有 Linux 环境。作品使用内部存储的 mcpy 文件夹，Minis 通常挂载到 /var/minis/mounts/mcpy；检查真实读写权限，不创建同名私有副本。
$documentationSetup
模型账号、目录挂载或系统权限需要我操作时，每次给我一个明确步骤。准备完成后，让我回到 mcpy 的开发工具页点击「工具已准备好，连接手机」，取得连接提示词。不要自行猜测配对码或读取凭据。""".trimIndent()
    }
    fun bootstrapPrompt(kind:String):String {
        val code=store.invitationCode().ifEmpty{store.newCode()}
        val skills=if(kind=="Minis")"将完整 mcpywrap skill 安装到 /var/minis/skills/mcpywrap，并确认应用发现技能。"else "将完整 mcpywrap skill 安装到你的 Agent 技能目录。"
        return """请帮我配置这台手机的 mcpy 模组开发环境。
使用官方 https://github.com/EaseCation/mcpywrap 提供的 mcpywrap CLI（准备辅助版本与 App 核心 $coreVersion 对齐）与完整 skill，不创建替代构建器。$skills
$documentationSetup
在当前 Linux 环境准备 Python、pip/uv 和 Git。工具和虚拟环境放在此应用私有环境中；项目必须使用手机内部存储的 mcpy 文件夹。
先确认共享目录可读写；Minis 通常挂载为 /var/minis/mounts/mcpy。目录权限或模型登录需要我操作时，给我一个明确步骤。
使用 mcpy host pair --url $endpoint --code $code --workspace <实际挂载路径> --label "$kind" 配对。等待我点击 mcpy 确认卡或通知中的「允许连接」，无需要求我来回切换 App；没有提示时再引导打开开发工具页。
执行 mcpy --project <挂载目录>/projects/<作品> host status --json，核对核心版本、能力和 launch_preconditions；能连接不代表能启动游戏，出现 needs_user_action 或 host_unavailable 时立即停止自动尝试，把 user_message 告诉我并等我明确确认。不要循环重试或自行唤起；用户确认后只查询原任务。后台容易断连时引导我打开 mcpy 开发连接页的省电设置，小米选择「无限制」。绑定后始终由 App 解析、组装与运行；失败不退回本地构建，不输出连接令牌。
完成后按标准 skill 创建普通源码模组、构建并在游戏小窗运行，以双端日志和游戏截图验证结果。连接邀请一小时内有效。网络中断或目录权限修复后重试同一条配对命令，继续原请求；明确过期才重新复制。""".trimIndent()
    }
}
