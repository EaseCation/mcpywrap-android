package dev.mcpywrap.studio

import dev.mcpywrap.workspace.SessionLogs
import java.io.File

enum class LogChannel(val label:String) { ALL("全部"), CLIENT("客户端"), SERVER("服务端"), GAME("游戏"), ENGINE("引擎") }
enum class LogTone(val label:String) { NORMAL("普通"), ERROR("错误 / stderr"), WARNING("警告"), DEBUG("调试"), SUCCESS("成功"), TAG("标签") }

data class LogRow(val source:String,val side:String,val stream:String,val time:Long?,val text:String,val raw:String) {
    val channelLabel:String get()=when {source=="engine"->"引擎";side=="client"->"客户端";side=="server"->"服务端";else->"游戏"}
    // Presentation only, following desktop's explicit levels/traceback markers. Never a mod health verdict.
    val tone:LogTone get()=when {
        stream in listOf("error","stderr") || error.containsMatchIn(text) -> LogTone.ERROR
        warning.containsMatchIn(text) -> LogTone.WARNING
        text.contains("[DEBUG]") -> LogTone.DEBUG
        text.startsWith("[+]") || text.contains("[SUCCESS]") -> LogTone.SUCCESS
        tag.containsMatchIn(text) -> LogTone.TAG
        else -> LogTone.NORMAL
    }
    fun inChannel(channel:LogChannel)=when(channel){
        LogChannel.ALL->true;LogChannel.ENGINE->source=="engine";LogChannel.GAME->source=="game"
        LogChannel.CLIENT->source=="game"&&side=="client";LogChannel.SERVER->source=="game"&&side=="server"
    }
    companion object {
        private val error=Regex("^\\[!]|\\b(?:Error|ERROR|Exception|Traceback)\\b")
        private val warning=Regex("\\b(?:Warn|WARNING|WARN)\\b")
        private val tag=Regex("^\\[\\w+]",RegexOption.MULTILINE)
    }
}

data class LogSnapshot(val rows:List<LogRow>,val available:Set<String>,val limited:Boolean,val unparsed:Int,val errors:List<String>)

/** Same bounded JSONL/raw readers as HTTP logs, without acquiring the game operation lock. */
object SessionLogReader {
    const val MAX_ROWS=4000
    fun directory(path:String,logsRoot:File=File("/sdcard/mcpy/logs")):File {
        require(path.isNotBlank()){ "还没有运行记录" }
        val directory=File(path).canonicalFile
        require(directory.parentFile==logsRoot.canonicalFile && directory.isDirectory){"运行记录不存在或不在 mcpy 日志目录中"}
        return directory
    }
    fun read(directory:File):LogSnapshot {
        val rows=mutableListOf<LogRow>();val available=mutableSetOf<String>();val errors=mutableListOf<String>()
        var limited=false;var unparsed=0
        for(source in listOf("game","engine")) {
            try {
                val file=File(directory,if(source=="engine")"runtime.log"else "game.jsonl").canonicalFile
                require(file.parentFile==directory.canonicalFile){"日志文件指向了会话目录之外"}
                val result=SessionLogs.read(file,source,tail=10000)
                if(result.optBoolean("available"))available.add(source)
                limited=limited||result.optBoolean("scan_truncated")
                unparsed+=result.optInt("unparsed_lines")
                val entries=result.optJSONArray("entries") ?: continue
                var pending:LogRow?=null
                val content=StringBuilder();val raw=StringBuilder()
                fun flush(){
                    pending?.let{rows.add(it.copy(text=content.toString().trimEnd('\r','\n'),raw=raw.toString()))}
                    pending=null;content.setLength(0);raw.setLength(0)
                }
                for(i in 0 until entries.length()) {
                    val entry=entries.getJSONObject(i);val text=entry.optString("text")
                    if(text.isEmpty())continue
                    val row=LogRow(source,entry.optString("side"),entry.optString("stream"),
                        entry.optLong("time",-1).takeIf{it>=0},text,
                        if(source=="engine")entry.optString("raw",text) else entry.toString(2))
                    if(source=="engine"){rows.add(row);continue}
                    // Python print can emit text, spaces and newline as separate JSONL writes.
                    // Join only contiguous chunks from the same side/stream, never across channels.
                    if(pending!=null&&(pending!!.side!=row.side||pending!!.stream!=row.stream))flush()
                    if(pending==null)pending=row
                    content.append(text)
                    if(raw.isNotEmpty())raw.append('\n')
                    raw.append(row.raw)
                    if(text.endsWith('\n'))flush()
                }
                flush()
            }catch(e:Exception){errors.add("${if(source=="engine")"引擎"else "游戏"}：${e.message}")}
        }
        // Unknown timestamps remain at the end; never invent timestamps for legacy output.
        val ordered=rows.sortedWith(compareBy<LogRow>{it.time?:Long.MAX_VALUE})
        return LogSnapshot(ordered.takeLast(MAX_ROWS),available,limited||rows.size>MAX_ROWS,unparsed,errors)
    }
}

fun logMatches(text:String,query:String):List<IntRange> {
    if(query.isEmpty())return emptyList()
    val matches=mutableListOf<IntRange>();var start=0
    while(start<text.length){val at=text.indexOf(query,start,ignoreCase=true);if(at<0)break;matches.add(at until at+query.length);start=at+query.length}
    return matches
}
