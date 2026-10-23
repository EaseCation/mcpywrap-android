package dev.mcpywrap.bridge

import dev.mcpywrap.workspace.OpenFile
import dev.mcpywrap.workspace.WorkspaceRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class HostServer(private val host:HostController){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO+CoroutineExceptionHandler{_,error->host.serverFailed(error)})
    private var engine:EmbeddedServer<CIOApplicationEngine,CIOApplicationEngine.Configuration>?=null
    suspend fun start(port:Int){
        engine=scope.embeddedServer(CIO,host="127.0.0.1",port=port){
            install(WebSockets){maxFrameSize=1_048_576;masking=false}
            routing{
                route("/v2"){
                    intercept(ApplicationCallPipeline.Call){
                        val origin=call.request.headers[HttpHeaders.Origin]
                        if(origin!=null){call.respondText("{\"ok\":false,\"error\":\"Browser origins are not accepted\"}",ContentType.Application.Json,HttpStatusCode.Forbidden);finish()}
                    }
                    get("/capabilities"){respond(call){host.capabilities()}}
                    post("/pairings"){respond(call){host.store.beginPair(body(call)).also{host.changed()}}}
                    post("/pairings/{id}/verify"){respond(call){host.store.verifyPair(call.parameters["id"]!!,body(call)).also{host.changed()}}}
                    post("/pairings/{id}/result"){respond(call){host.store.pairResult(call.parameters["id"]!!,body(call))}}
                    get("/logs"){respond(call){auth(call);val q=call.request.queryParameters;host.application.logs(q["project"]?:error("缺少作品"),q["session"]?.takeIf{it.isNotBlank()},q["after"]?.toLongOrNull(),q["tail"]?.toIntOrNull()?:100,q["source"]?:"game",q["contains"],q["summary"]?.let{require(it in listOf("true","false"));it=="true"}?:false,q["since_ms"]?.toLong(),q["until_ms"]?.toLong())}}
                    get("/projects"){respond(call){auth(call);JSONObject().put("projects",JSONArray(host.application.repository.projects().map{JSONObject().put("id",it.id).put("name",it.name).put("path","projects/"+it.folder)}))}}
                    post("/clients/revoke"){respond(call){host.revoke(auth(call));JSONObject()}}
                    post("/operations"){respond(call){host.submit(auth(call),body(call))}}
                    get("/operations/{id}"){respond(call){host.operationStatus(call.parameters["id"]!!,auth(call))}}
                    post("/operations/{id}/cancel"){respond(call){host.cancel(auth(call),call.parameters["id"]!!);JSONObject()}}
                    post("/operations/{id}/preparation/{step}"){respond(call){host.completePreparation(auth(call),call.parameters["id"]!!,call.parameters["step"]!!,body(call));JSONObject()}}
                    get("/events"){respond(call){val client=auth(call);JSONObject().put("events",JSONArray(host.events(call.request.queryParameters["after"]?.toLongOrNull()?:0,client)))}}
                    webSocket("/events"){
                        val client=host.store.authenticate(call.request.headers[HttpHeaders.Authorization])
                        if(client==null){close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,"Authentication required"));return@webSocket}
                        var cursor=call.request.queryParameters["after"]?.toLongOrNull()?:0
                        while(host.online){
                            if(host.store.authenticate(call.request.headers[HttpHeaders.Authorization])==null){close();break}
                            for(event in host.events(cursor,client)){send(Frame.Text(event.toString()));cursor=event.getLong("sequence")}
                            delay(300)
                        }
                    }
                    get("/artifacts/{id}"){
                        try{
                            val job=host.store.job(call.parameters["id"]!!,auth(call));require(job.optString("state")=="completed")
                            val result=job.getJSONObject("result")
                            val path=result.optString("artifact").ifEmpty{result.getString("path")}
                            val root=host.application.repository.root.canonicalFile
                            val file=File(path).canonicalFile;require(file.path.startsWith(root.path+"/")&&file.isFile)
                            call.respondFile(file)
                        }catch(e:Exception){call.respondText(failure(e).toString(),ContentType.Application.Json,HttpStatusCode.BadRequest)}
                    }
                    get("/files"){respond(call){
                        auth(call);val folder=(call.request.queryParameters["project"]?:error("缺少作品")).removePrefix("projects/")
                        val path=call.request.queryParameters["path"].orEmpty()
                        val repository=host.application.repository
                        if(call.request.queryParameters["list"]=="true")JSONObject().put("files",JSONArray(repository.list(folder,path).map{JSONObject().put("path",it.path).put("directory",it.directory).put("bytes",it.bytes)}))
                        else{val file=repository.open(folder,path);JSONObject().put("path",file.path).put("text",file.text).put("digest",file.digest)}
                    }}
                    put("/files"){respond(call){
                        val client=auth(call);val input=body(call)
                        val project=input.getString("project").let{if(it.startsWith("projects/"))it else "projects/$it"}
                        val request=JSONObject().put("request_id",input.getString("request_id")).put("project",project).put("operation","files.write")
                        input.remove("request_id");input.remove("project");request.put("parameters",input)
                        host.submit(client,request)
                    }}
                }
            }
        }.start(wait=false)
        engine!!.engine.resolvedConnectors()
    }
    fun stop(){engine?.stop(100,1000);engine=null;scope.cancel()}
    private fun auth(call:ApplicationCall)=host.store.authenticate(call.request.headers[HttpHeaders.Authorization])?:throw SecurityException("请先在 App 配对")
    private suspend fun body(call:ApplicationCall):JSONObject {
        val text=call.receiveStream().use{input->
            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=1_048_576){"请求超过大小限制"};output.write(buffer,0,count)}
            output.toString("UTF-8")
        }
        return JSONObject(text)
    }
    private fun failure(e:Exception)=JSONObject().put("ok",false).put("error",e.message?:"操作失败").put("code",if(e is SecurityException)"unauthorized" else "invalid_request")
    private suspend fun respond(call:ApplicationCall,block:suspend()->JSONObject){
        try{val result=block();if(!result.has("ok"))result.put("ok",true);call.respondText(result.toString(),ContentType.Application.Json)}
        catch(e:Exception){call.respondText(failure(e).toString(),ContentType.Application.Json,if(e is SecurityException)HttpStatusCode.Unauthorized else HttpStatusCode.BadRequest)}
    }
}
