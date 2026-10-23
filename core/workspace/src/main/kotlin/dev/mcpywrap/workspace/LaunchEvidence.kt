package dev.mcpywrap.workspace

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Each process owns a separate bounded file; timestamps describe evidence, never health. */
object LaunchEvidence {
    private fun lines(file:File):List<String> {
        if(!file.isFile)return emptyList()
        return runCatching{RandomAccessFile(file,"r").use{input->
            val start=maxOf(0,input.length()-65536);input.seek(start)
            val raw=ByteArray((input.length()-start).toInt());input.readFully(raw)
            val text=raw.toString(Charsets.UTF_8);(if(start>0)text.substringAfter('\n',"")else text).lineSequence().filter{it.isNotBlank()}.toList()
        }}.getOrDefault(emptyList())
    }
    @Synchronized fun record(directory:File,source:String,stage:String,details:JSONObject=JSONObject()) {
        require(source in listOf("app","launcher"))
        val file=File(directory,"$source-timeline.jsonl")
        val entry=JSONObject(details.toString()).put("stage",stage).put("observed_at",System.currentTimeMillis()).put("source",source)
        val old=lines(file).takeLast(63)
        runCatching{WorkspaceRepository.atomic(file,(old.plus(entry.toString()).joinToString("\n")+"\n").toByteArray())}
    }
    fun timeline(directory:File,created:Long,now:Long):JSONObject {
        val events=mutableListOf<JSONObject>();var truncated=false
        for(source in listOf("app","launcher","bootstrap","game")){
            val file=File(directory,"$source-timeline.jsonl")
            if(!file.isFile)continue
            val lines=lines(file);if(lines.size>=64||file.length()>65536)truncated=true
            lines.takeLast(64).forEach{line->runCatching{JSONObject(line)}.getOrNull()?.takeIf{it.optLong("observed_at")>0}?.let{events.add(it.put("source",source))}}
        }
        // Older sessions still provide their latest receipts; do not invent intermediate stages.
        if(events.isEmpty())for(name in listOf("launch-receipt.json","bootstrap.json")){
            val file=File(directory,name)
            if(file.isFile)runCatching{JSONObject(file.readText())}.getOrNull()?.takeIf{it.optLong("observed_at")>0}?.let{events.add(it.put("source",name.removeSuffix(".json")))}}
        val sorted=events.sortedBy{it.getLong("observed_at")}
        if(sorted.size>128)truncated=true
        val selected=sorted.takeLast(128)
        return JSONObject().put("events",JSONArray(selected.mapIndexed{i,event->
            val time=event.getLong("observed_at")
            event.put("age_ms",(now-time).coerceAtLeast(0)).put("elapsed_ms",(time-created).coerceAtLeast(0))
                .put("since_previous_ms",if(i==0)JSONObject.NULL else (time-selected[i-1].getLong("observed_at")).coerceAtLeast(0))
        })).put("truncated",truncated)
    }
}

class LaunchHistory(private val file:File,private val now:()->Long={System.currentTimeMillis()}) {
    @Synchronized fun record(attempt:String,background:Boolean,outcome:String,code:String,environment:String){
        val entries=read().filter{it.optString("attempt")!=attempt}.takeLast(15).toMutableList()
        entries.add(JSONObject().put("attempt",attempt).put("background",background).put("outcome",outcome).put("code",code).put("observed_at",now()).put("environment",environment))
        WorkspaceRepository.atomic(file,JSONArray(entries).toString().toByteArray())
    }
    private fun read():List<JSONObject> = if(!file.isFile)emptyList()else runCatching{val a=JSONArray(file.readText());(0 until a.length()).map{a.getJSONObject(it)}}.getOrDefault(emptyList())
    @Synchronized fun latestBackground(environment:String):JSONObject?=read().lastOrNull{it.optBoolean("background")}?.let{
        JSONObject(it.toString()).apply{remove("attempt");remove("environment")}.put("age_ms",(now()-it.getLong("observed_at")).coerceAtLeast(0))
            .put("same_environment",environment==it.optString("environment"))
    }
}
