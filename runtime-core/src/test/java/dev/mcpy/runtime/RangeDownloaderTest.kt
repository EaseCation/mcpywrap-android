package dev.mcpy.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RangeDownloaderTest {
    @Test fun resumesExistingPrefixWithoutDuplicatingBytes()=runBlocking {
        val payload=ByteArray(128*1024) { (it%251).toByte() }
        val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
        val server=MockWebServer()
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse {
            val range=r.getHeader("Range")!!;requests.add(range)
            val values=range.removePrefix("bytes=").split('-');val start=values[0].toInt();val end=values[1].toInt()
            return MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${payload.size}").setBody(Buffer().write(payload,start,end-start+1))
        }}
        val dir=Files.createTempDirectory("mcpy-range").toFile();val file=java.io.File(dir,"apk.part");file.writeBytes(payload.copyOfRange(0,4097))
        try { RangeDownloader().download(server.url("/engine.apk").toString(),file) {};assertArrayEquals(payload,file.readBytes());assertTrue(requests.contains("bytes=4097-${payload.size-1}")) }
        finally {server.shutdown();dir.deleteRecursively()}
    }
    @Test fun rejectsIncorrectContentRange()=runBlocking {
        val server=MockWebServer()
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-0/1024").setBody("x"))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 1-1023/1024").setBody("invalid"))
        val dir=Files.createTempDirectory("mcpy-range-bad").toFile();val file=java.io.File(dir,"apk.part")
        try {var failed=false;try {RangeDownloader().download(server.url("/engine.apk").toString(),file) {}}catch(e:IllegalStateException){failed=true};assertTrue(failed);assertEquals(0L,file.length())}
        finally {server.shutdown();dir.deleteRecursively()}
    }
}
