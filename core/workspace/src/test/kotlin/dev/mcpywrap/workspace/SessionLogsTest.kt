package dev.mcpywrap.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionLogsTest {
    @get:Rule val temp=TemporaryFolder()
    private fun entry(text:String,side:String="client",time:Long=1000)=JSONObject().put("text",text).put("side",side).put("time",time).put("stream","stdout").toString()+"\n"
    @Test fun sparseMessagesAreFilteredBeforeTailAndSummaryKeepsSidesSeparate(){
        val file=temp.newFile();file.writeText(entry("needle")+entry("noise").repeat(756)+entry("needle")+entry("needle","server")+entry("ready"))
        val result=SessionLogs.read(file,"game",tail=1,contains="needle",summary=true)
        assertEquals(3,result.getInt("matched_lines"));assertEquals("[server] needle\n",result.getString("text"))
        assertEquals(2,result.getJSONArray("summary").getJSONObject(0).getInt("count"))
        assertEquals("client",result.getJSONArray("summary").getJSONObject(0).getString("side"))
    }
    @Test fun filteredCursorConsumesUnmatchedButNotPartialRecords(){
        val file=temp.newFile();file.writeText(entry("noise")+"{\"text\":\"needle")
        val first=SessionLogs.read(file,"game",after=0,contains="needle")
        assertEquals("",first.getString("text"));assertEquals(entry("noise").toByteArray().size.toLong(),first.getLong("cursor"))
        file.appendText("\",\"side\":\"client\",\"time\":1000}\n")
        val next=SessionLogs.read(file,"game",after=first.getLong("cursor"),contains="needle",tail=0)
        assertEquals("[client] needle\n",next.getString("text"));assertEquals(file.length(),next.getLong("cursor"))
    }
    @Test fun timeAndSideFilteringDoesNotGuessMissingTimestamps(){
        val file=temp.newFile();file.writeText(entry("old",time=999)+entry("yes",time=1000)+entry("server","server",1001)+entry("late",time=2001)+"{\"text\":\"unknown\",\"side\":\"client\"}\n")
        val result=SessionLogs.read(file,"client",since=1000,until=2000)
        assertEquals("[client] yes\n",result.getString("text"));assertEquals(1,result.getInt("excluded_without_timestamp"))
    }
    @Test fun scanBoundsAndRotationAreVisible(){
        val file=temp.newFile();file.writeText(entry("old needle")+entry("noise").repeat(15000))
        val result=SessionLogs.read(file,"game",contains="needle")
        assertTrue(result.getBoolean("scan_truncated"));assertEquals(0,result.getInt("matched_lines"))
        val cursor=result.getLong("cursor");file.writeText(entry("new"))
        val reset=SessionLogs.read(file,"game",after=cursor)
        assertTrue(reset.getBoolean("cursor_reset"));assertEquals("[client] new\n",reset.getString("text"))
    }
    @Test fun absentAndEmptyAreDifferentAndRegexCharactersAreLiteral(){
        val file=File(temp.root,"absent")
        assertFalse(SessionLogs.read(file,"game").getBoolean("available"));file.writeText("")
        assertTrue(SessionLogs.read(file,"game").getBoolean("available"));file.writeText(entry(".*")+entry("other"))
        assertEquals(1,SessionLogs.read(file,"game",contains=".*").getInt("matched_lines"))
    }
    @Test fun invalidRangesAndLargeUnterminatedRecordsFailExplicitly(){
        val file=temp.newFile()
        assertThrows(IllegalArgumentException::class.java){SessionLogs.read(file,"game",since=2,until=1)}
        file.writeText("x".repeat(SessionLogs.LIMIT))
        assertThrows(IllegalArgumentException::class.java){SessionLogs.read(file,"game")}
    }
    @Test fun engineSummaryIgnoresTimestampButPreservesRawOutput(){
        val file=temp.newFile();file.writeText("1000 error\n2000 error\nTraceback missing timestamp\n")
        val result=SessionLogs.read(file,"engine",summary=true)
        assertEquals("1000 error\n2000 error\nTraceback missing timestamp\n",result.getString("text"))
        assertEquals(1,SessionLogs.read(file,"engine",contains="Traceback").getInt("matched_lines"));assertEquals(2,result.getJSONArray("summary").getJSONObject(0).getInt("count"))
    }
}
