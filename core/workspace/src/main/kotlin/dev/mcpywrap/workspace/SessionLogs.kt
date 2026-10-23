package dev.mcpywrap.workspace

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Bounded retrieval only: no severity guessing or mod-specific interpretation. */
object SessionLogs {
    const val LIMIT=512*1024
    fun read(file:File,source:String,after:Long?=null,tail:Int=100,contains:String?=null,summary:Boolean=false,since:Long?=null,until:Long?=null):JSONObject {
        require(source in listOf("game","client","server","engine"))
        require(tail in 0..10000&& (contains?.length?:0)<=1024)
        require(after==null||after>=0);require(since==null||since>=0);require(until==null||until>=0)
        require(since==null||until==null||since<=until){"起始时间不能大于截止时间"}
        val result=JSONObject().put("available",file.isFile).put("source",source).put("scan_limit_bytes",LIMIT)
        if(!file.isFile)return result.put("text","").put("cursor",0).put("matched_lines",0).put("scanned_lines",0).put("scan_truncated",false).apply{if(summary)put("summary",JSONArray())}
        var scanned=0;var unknownTime=0;var malformed=0
        val matches=mutableListOf<JSONObject>()
        RandomAccessFile(file,"r").use{input->
            val size=input.length();val reset=after!=null&&after>size
            val start=if(after!=null&&!reset)after else maxOf(0,size-LIMIT)
            input.seek(start)
            val bytes=ByteArray(minOf(LIMIT.toLong(),size-start).toInt());input.readFully(bytes)
            val final=bytes.indexOfLast{it==10.toByte()}
            // A writer may not have completed the last line; do not consume it prematurely.
            val end=if(final>=0)final+1 else 0
            require(end>0||bytes.size<LIMIT){"日志单行超过 512 KiB；请检查原始日志"}
            val partialStart=start>0&&(after==null||reset)
            var offset=if(partialStart)bytes.indexOfFirst{it==10.toByte()}.let{if(it<0)end else it+1}else 0
            while(offset<end){
                val stop=(offset until end).first{bytes[it]==10.toByte()}
                val line=String(bytes,offset,stop-offset,Charsets.UTF_8).removeSuffix("\r");offset=stop+1;scanned++
                val entry=if(source=="engine"){
                    val stamp=line.substringBefore(' ').toLongOrNull()
                    JSONObject().put("text",if(stamp==null)line else line.substringAfter(' ',line)).put("time",stamp?:JSONObject.NULL).put("source",source).put("raw",line)
                }else runCatching{JSONObject(line)}.getOrNull()?.apply{put("source","game")}
                if(entry==null){malformed++;continue}
                if(source in listOf("client","server")&&entry.optString("side")!=source)continue
                val time=entry.optLong("time",-1)
                if(since!=null||until!=null){if(time<0){unknownTime++;continue};if(since!=null&&time<since||until!=null&&time>until)continue}
                if(contains!=null&&!entry.optString("text").contains(contains))continue
                matches.add(entry)
            }
            result.put("cursor",start+end).put("cursor_reset",reset).put("has_more",start+end<size&&end>0)
                .put("scan_truncated",start>0&&(after==null||reset)).put("scan_start",start)
        }
        val selected=if(after!=null)matches else if(tail==0)emptyList()else matches.takeLast(tail)
        val text=selected.filter{it.optString("text").isNotBlank()}.joinToString(""){entry->
            val label=entry.optString("side").takeIf{it.isNotBlank()}?.let{"[$it] "}.orEmpty()
            label+entry.optString("raw",entry.optString("text")).let{if(it.endsWith("\n"))it else "$it\n"}
        }
        result.put("text",text).put("entries",JSONArray(selected)).put("scanned_lines",scanned).put("matched_lines",matches.size)
            .put("unparsed_lines",malformed).put("excluded_without_timestamp",unknownTime)
        if(summary){
            val counts=linkedMapOf<Triple<String,String,String>,JSONObject>()
            matches.filter{it.optString("text").isNotBlank()}.forEach{entry->
                val key=Triple(entry.optString("side"),entry.optString("stream"),entry.optString("text").trimEnd('\r','\n'))
                val row=counts.getOrPut(key){JSONObject().put("side",key.first).put("stream",key.second).put("text",key.third).put("count",0)}
                row.put("count",row.getInt("count")+1)
            }
            result.put("summary",JSONArray(counts.values.sortedByDescending{it.getInt("count")}.take(tail))).put("summary_truncated",counts.size>tail).put("unique_messages",counts.size)
        }
        return result
    }
}
