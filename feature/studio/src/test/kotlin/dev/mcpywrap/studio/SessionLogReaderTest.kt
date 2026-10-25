package dev.mcpywrap.studio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SessionLogReaderTest {
    @get:Rule val temp=TemporaryFolder()
    private fun entry(text:String,side:String,time:Long,stream:String="stdout")=JSONObject().put("text",text+"\n").put("time",time).put("side",side).put("stream",stream).toString()+"\n"
    @Test fun channelsKeepRealSidesAndErrorStreamWhileMergingTimestamps(){
        val dir=temp.newFolder()
        File(dir,"game.jsonl").writeText(entry("[Welcome] ready","client",100)+entry("trace line","server",300,"stderr"))
        File(dir,"runtime.log").writeText("200 [WARN] waiting\n")
        val result=SessionLogReader.read(dir)
        assertEquals(listOf(100L,200L,300L),result.rows.map{it.time})
        assertEquals(listOf(LogTone.TAG,LogTone.WARNING,LogTone.ERROR),result.rows.map{it.tone})
        assertEquals(1,result.rows.count{it.inChannel(LogChannel.CLIENT)})
        assertEquals(2,result.rows.count{it.inChannel(LogChannel.GAME)})
        assertEquals(1,result.rows.count{it.inChannel(LogChannel.ENGINE)})
        assertTrue(result.rows.last().raw.contains("stderr"))
    }
    @Test fun unfinishedWritesAndRotationDoNotFabricateOrRetainOldRows(){
        val dir=temp.newFolder();val game=File(dir,"game.jsonl")
        game.writeText(entry("first","client",100)+"{\"text\":\"half")
        assertEquals(listOf("first"),SessionLogReader.read(dir).rows.map{it.text})
        game.writeText(entry("new","server",200))
        assertEquals(listOf("new"),SessionLogReader.read(dir).rows.map{it.text})
    }
    @Test fun missingAndEmptySourcesAreDistinguishedAndMalformedRecordsReported(){
        val dir=temp.newFolder();assertTrue(SessionLogReader.read(dir).available.isEmpty())
        File(dir,"runtime.log").writeText("")
        File(dir,"game.jsonl").writeText("not json\n")
        val result=SessionLogReader.read(dir)
        assertEquals(setOf("game","engine"),result.available);assertEquals(1,result.unparsed);assertTrue(result.rows.isEmpty())
    }
    @Test fun escapesAreRejectedAndBadSourceDoesNotHideOtherChannel(){
        val root=temp.newFolder();val dir=File(root,"session-test").apply{mkdir()}
        assertEquals(dir.canonicalFile,SessionLogReader.directory(dir.path,root))
        assertThrows(IllegalArgumentException::class.java){SessionLogReader.directory(root.path,root)}
        val outside=temp.newFile();outside.writeText("100 secret\n")
        Files.createSymbolicLink(File(dir,"runtime.log").toPath(),outside.toPath())
        File(dir,"game.jsonl").writeText(entry("visible","client",100))
        val result=SessionLogReader.read(dir)
        assertEquals(listOf("visible"),result.rows.map{it.text});assertEquals(1,result.errors.size)
    }
    @Test fun fragmentedPrintWritesRemainOneContinuousLine(){
        val dir=temp.newFolder()
        fun chunk(text:String)=JSONObject().put("text",text).put("side","client").put("stream","stdout").put("time",100).toString()+"\n"
        File(dir,"game.jsonl").writeText(chunk("value:")+chunk(" ")+chunk("42")+chunk("\n"))
        val result=SessionLogReader.read(dir)
        assertEquals(listOf("value: 42"),result.rows.map{it.text})
        assertTrue(result.rows.single().raw.contains("42"))
    }
    @Test fun searchIsLiteralCaseInsensitiveAndReturnsCorrectChineseHighlightOffsets(){
        assertEquals(listOf(0..1,6..7),logMatches("错误 .* 错误","错误"))
        assertEquals(listOf(3..4),logMatches("错误 .* 错误",".*"))
        assertEquals(listOf(0..3,5..8),logMatches("WARN warn","warn"))
        assertTrue(logMatches("hello","").isEmpty())
    }
    @Test fun scanIsBoundedAndDoesNotClaimWholeFileWasRead(){
        val dir=temp.newFolder();File(dir,"game.jsonl").writeText(entry("noise "+"x".repeat(100),"client",100).repeat(5000))
        val result=SessionLogReader.read(dir);assertTrue(result.limited);assertTrue(result.rows.size<=SessionLogReader.MAX_ROWS)
    }
}
