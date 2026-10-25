package dev.mcpywrap.bridge

import dev.mcpywrap.workspace.WorkspaceRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Only coordination/credentials are private. User work and logs remain in the public workspace. */
class HostStore(private val directory:File,private val workspace:File,private val now:()->Long={System.currentTimeMillis()}) {
    private val random=SecureRandom()
    private val clientsFile=File(directory,"clients.json")
    private val pairings=linkedMapOf<String,JSONObject>()
    private var code=""
    private var codeExpires=0L
    private var codeUsed=false
    private val pairingFile=File(directory,"pairings.json")
    val installationId:String
    init {
        directory.mkdirs()
        val identity=File(directory,"installation-id")
        if(!identity.exists())write(identity,UUID.randomUUID().toString())
        installationId=identity.readText().trim()
        if(pairingFile.isFile){
            val saved=JSONObject(pairingFile.readText())
            code=saved.optString("code");codeExpires=saved.optLong("codeExpires");codeUsed=saved.optBoolean("codeUsed")
            val pending=saved.optJSONArray("requests")?:JSONArray()
            for(i in 0 until pending.length())pending.getJSONObject(i).let{if(now()<it.getLong("expires"))pairings[it.getString("id")]=it}
        }
        jobFiles().forEach{file->
            val job=JSONObject(file.readText())
            if(job.optString("state") in listOf("queued","running","waiting_preparation")){
                job.put("state","interrupted").put("code","host_restarted").put("error","服务已重启，操作未确认完成；不会自动重放")
                write(file,job.toString())
            }
        }
    }
    private fun jobFiles()=File(directory,"jobs").listFiles().orEmpty().filter{it.name.matches(Regex("[a-f0-9]{64}\\.json"))}
    private var lastEventSequence:Long?=null
    @Synchronized fun nextEventSequence():Long {
        val file=File(directory,"event-sequence")
        val current=lastEventSequence?:run{
            val stored=if(file.exists())file.readText().trim().toLong()else 0L
            // Preserve cursors from earlier hosts which used wall-clock sequences.
            val journal=File(workspace,"logs/bridge/events.jsonl")
            val published=if(journal.isFile)journal.useLines{lines->lines.mapNotNull{runCatching{JSONObject(it).getLong("sequence")}.getOrNull()}.filter{it in 1 until Long.MAX_VALUE}.maxOrNull()?:0L}else 0L
            maxOf(stored,published)
        }
        val next=Math.addExact(current,1L)
        write(file,next.toString());lastEventSequence=next;return next
    }
    private fun write(file:File,text:String)=WorkspaceRepository.atomic(file,text.toByteArray())
    private fun secret():String {val bytes=ByteArray(32);random.nextBytes(bytes);return bytes.joinToString(""){"%02x".format(it)}}
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun clients():JSONArray=if(clientsFile.exists())JSONArray(clientsFile.readText())else JSONArray()
    private fun savePairings()=write(pairingFile,JSONObject().put("code",code).put("codeExpires",codeExpires)
        .put("codeUsed",codeUsed).put("requests",JSONArray(pairings.values.toList())).toString())
    private fun prunePairings(){
        pairings.entries.removeAll{entry->
            if(now()>=entry.value.getLong("expires")){
                File(workspace,entry.value.getString("probe")).delete()
                File(workspace,entry.value.getString("probe").removeSuffix(".txt")+".response").delete()
                true
            }else false
        }
    }
    @Synchronized fun newCode():String {
        prunePairings();code=secret().take(12);codeUsed=false;codeExpires=now()+60*60_000;savePairings();return code
    }
    @Synchronized fun currentCode():String=if(now()<codeExpires&&!codeUsed)code else ""
    @Synchronized fun invitationCode():String=if(now()<codeExpires&&(!codeUsed||pairingProgress()!=null))code else ""
    @Synchronized fun invitationSeconds():Long=if(invitationCode().isEmpty())0 else ((codeExpires-now())/1000).coerceAtLeast(0)
    private fun pairingStart(pair:JSONObject)=JSONObject().put("id",pair.getString("id"))
        .put("secret",pair.getString("secret")).put("access",pair.getString("access"))
        .put("expires_at",pair.getLong("expires")).apply{
            if(pair.getString("access")=="shared")put("probe",pair.getString("probe")).put("challenge",pair.getString("challenge"))
        }
    @Synchronized fun beginPair(input:JSONObject):JSONObject {
        prunePairings()
        val request=input.optString("request_id")
        require(request.isEmpty()||request.matches(Regex("[a-f0-9]{32,64}"))){"无效配对请求标识"}
        val fingerprint=digest(canonical(input))
        if(request.isNotEmpty())pairings.values.firstOrNull{it.optString("request_id")==request}?.let{
            require(it.getString("fingerprint")==fingerprint){"配对请求标识已用于不同连接"}
            return pairingStart(it)
        }
        require(currentCode().isNotEmpty()&&MessageDigest.isEqual(code.toByteArray(),input.optString("code").toByteArray())){"连接邀请已过期，请在「开发」页生成新的连接验证码"}
        val access=input.optString("access","shared");require(access in listOf("shared","files")){"未知目录访问方式"}
        require(pairings.size<10){"配对请求过多，请先完成或拒绝已有连接"}
        val id=UUID.randomUUID().toString();val proof=secret();val challenge=secret();val poll=secret()
        val relative=".bridge/probes/$id.txt"
        if(access=="shared")write(File(workspace,relative),proof)
        val pair=JSONObject().put("id",id).put("label",input.optString("label","其他客户端").take(80))
            .put("secret",poll).put("probe",relative).put("proof",digest(proof)).put("challenge",challenge)
            .put("request_id",request).put("fingerprint",fingerprint)
            .put("expires",now()+15*60_000).put("access",access).put("state",if(access=="shared")"verifying"else "pending")
        pairings[id]=pair;codeUsed=true;savePairings()
        return pairingStart(pair)
    }
    private fun pending(id:String,secret:String):JSONObject {
        val pair=pairings[id]?:error("配对不存在")
        require(MessageDigest.isEqual(pair.getString("secret").toByteArray(),secret.toByteArray())){"配对凭据不正确"}
        if(now()>pair.getLong("expires"))pair.put("state","expired")
        return pair
    }
    @Synchronized fun verifyPair(id:String,input:JSONObject):JSONObject {
        val pair=pending(id,input.getString("secret"))
        if(pair.getString("state") in listOf("pending","approved"))return JSONObject().put("state",pair.getString("state"))
        require(pair.getString("state")=="verifying"){"配对已结束"}
        val response=File(workspace,pair.getString("probe").removeSuffix(".txt")+".response")
        require(input.optString("proof")==pair.getString("proof")&&response.isFile&&response.readText()==pair.getString("challenge")){"目录未正确挂载，或没有写入权限"}
        pair.put("state","pending").put("expires",now()+15*60_000);savePairings();response.delete();File(workspace,pair.getString("probe")).delete()
        return JSONObject().put("state","pending")
    }
    @Synchronized fun approve(id:String,allow:Boolean){
        val pair=pairings[id]?:error("配对不存在")
        require(pair.optString("state")=="pending"&&now()<pair.getLong("expires")){"配对已过期"}
        if(!allow){pair.put("state","denied");savePairings();return}
        val token=secret();val client=UUID.randomUUID().toString()
        val records=clients().put(JSONObject().put("id",client).put("label",pair.getString("label")).put("token_hash",digest(token)).put("access",pair.getString("access")).put("workspace",workspace.path).put("created",now()))
        write(clientsFile,records.toString())
        pair.put("state","approved").put("token",token).put("client_id",client);savePairings()
    }
    @Synchronized fun pairResult(id:String,input:JSONObject):JSONObject {
        val pair=pending(id,input.getString("secret"))
        val result=JSONObject().put("state",pair.getString("state")).put("expires_at",pair.getLong("expires"))
        if(pair.getString("state")=="approved")result.put("token",pair.getString("token")).put("client_id",pair.getString("client_id")).put("installation_id",installationId)
        return result
    }
    @Synchronized fun authenticate(header:String?):String? {
        if(header==null||!header.startsWith("Bearer "))return null
        val hash=digest(header.removePrefix("Bearer "));val list=clients()
        for(i in 0 until list.length()){val record=list.getJSONObject(i);if(MessageDigest.isEqual(hash.toByteArray(),record.getString("token_hash").toByteArray()))return record.getString("id")}
        return null
    }
    @Synchronized fun revoke(id:String){val list=clients();val kept=JSONArray();for(i in 0 until list.length())if(list.getJSONObject(i).getString("id")!=id)kept.put(list.getJSONObject(i));write(clientsFile,kept.toString())}
    @Synchronized fun sharedAccess(id:String):Boolean=publicClients().firstOrNull{it.getString("id")==id}?.optString("access","shared")=="shared"
    @Synchronized fun publicClients():List<JSONObject>{val list=clients();return (0 until list.length()).map{JSONObject(list.getJSONObject(it).toString()).apply{remove("token_hash")}}}
    @Synchronized fun pendingPairs():List<JSONObject> = pairings.values.filter{it.optString("state")=="pending"&&now()<it.getLong("expires")}.map{JSONObject().put("id",it.getString("id")).put("label",it.getString("label")).put("access",it.getString("access"))}
    @Synchronized fun pairingProgress():JSONObject?=pairings.values.lastOrNull{now()<it.getLong("expires")&&it.optString("state") in listOf("verifying","pending")}?.let{
        JSONObject().put("label",it.getString("label")).put("state",it.getString("state"))
    }
    private fun canonical(value:Any?):String=when(value){is JSONObject->value.keys().asSequence().sorted().joinToString(prefix="{",postfix="}"){JSONObject.quote(it)+":"+canonical(value.get(it))};is JSONArray->(0 until value.length()).joinToString(prefix="[",postfix="]"){canonical(value.get(it))};else->JSONArray().put(value).toString().let{it.substring(1,it.length-1)}}
    @Synchronized fun submit(client:String,request:JSONObject):Pair<JSONObject,Boolean>{
        val key=request.getString("request_id");require(key.matches(Regex("[A-Za-z0-9-]{16,80}"))){"需要有效 request_id"}
        val id=digest(client+":"+key);val file=File(directory,"jobs/$id.json");val fingerprint=digest(canonical(request))
        if(file.exists()){val job=JSONObject(file.readText());require(job.getString("fingerprint")==fingerprint){"request_id 已用于不同操作"};return job to false}
        val job=JSONObject().put("operation_id",id).put("client_id",client).put("fingerprint",fingerprint).put("request",request).put("state","queued").put("created",now())
        write(file,job.toString());return job to true
    }
    @Synchronized fun job(id:String,client:String?=null):JSONObject {
        require(id.matches(Regex("[a-f0-9]{64}"))){"无效操作 ID"}
        val file=File(directory,"jobs/$id.json");require(file.isFile){"操作不存在"}
        val value=JSONObject(file.readText());require(client==null||value.getString("client_id")==client){"无权访问操作"};return value
    }
    @Synchronized fun update(id:String,change:(JSONObject)->Unit):JSONObject{val value=job(id);change(value);write(File(directory,"jobs/$id.json"),value.toString());return value}
    /** Commit the acknowledgement before releasing the waiting worker. Retries only compare it. */
    @Synchronized fun acceptPreparation(id:String,client:String,step:String,result:JSONObject):Boolean {
        val value=job(id,client)
        val receipts=value.optJSONObject("preparation_receipts")?:JSONObject()
        val fingerprint=digest(canonical(result))
        if(receipts.has(step)){
            require(receipts.getString(step)==fingerprint){"准备结果与已提交内容不同"}
            return false
        }
        require(value.optString("state")=="waiting_preparation"&&value.optJSONObject("preparation")?.optString("id")==step){"准备阶段不存在或已结束"}
        receipts.put(step,fingerprint);value.put("preparation_receipts",receipts)
        write(File(directory,"jobs/$id.json"),value.toString())
        return true
    }
    @Synchronized fun waitingJobOwners():List<Pair<String,String>> = jobFiles().mapNotNull{file->
        runCatching{JSONObject(file.readText())}.getOrNull()?.takeIf{it.optString("state")=="waiting_user"}?.let{it.getString("operation_id") to it.getString("client_id")}
    }
    @Synchronized fun recentJobs():List<JSONObject> = jobFiles().sortedByDescending{it.lastModified()}.take(8).mapNotNull{file->
        runCatching{val job=JSONObject(file.readText());publicJob(job).put("operation",job.getJSONObject("request").getString("operation")).put("project",job.getJSONObject("request").optString("project"))}.getOrNull()
    }
    fun publicJob(value:JSONObject)=JSONObject(value.toString()).apply{
        remove("request");remove("fingerprint");remove("client_id");remove("preparation_receipts")
        if(optString("state")=="cancelled"){
            remove("hint")
            optJSONObject("result")?.let{detail->
                for(key in listOf("needs_user_action","next_action","retryable","retry_policy","user_message","agent_instruction","resume_command","hint"))detail.remove(key)
                detail.put("state","cancelled")
            }
        }
        if(optString("state") !in listOf("completed","cancelled")){
            val detail=optJSONObject("result")
            if(detail?.optBoolean("needs_user_action")==true){
                for(key in listOf("needs_user_action","next_action","retryable","retry_policy","user_message","agent_instruction"))if(detail.has(key))put(key,detail.get(key))
                put("resume_command","mcpy host resume --operation "+getString("operation_id")+" --json")
            }
        }
    }
}
