package dev.mcpy.runtime

import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class RuntimeIntegrityTest {
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    @Test fun repairsOnlyTheDamagedBlockAndRestoresWholeIdentity() {
        val bytes=ByteArray(2055){(it%251).toByte()};val damaged=bytes.copyOf();damaged[1500]=0
        val dir=Files.createTempDirectory("mcpy-integrity").toFile();val file=java.io.File(dir,"engine.apk");file.writeBytes(damaged)
        val hashes=bytes.toList().chunked(1024).map{hash(it.toByteArray())}
        val server=MockWebServer();server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 1024-2047/2055").setBody(Buffer().write(bytes,1024,1024)))
        try {RuntimeIntegrity.repair(file,server.url("/engine.apk").toString(),bytes.size.toLong(),1024,hashes) {};assertArrayEquals(bytes,file.readBytes());assertEquals(hash(bytes),RuntimeIntegrity.sha256(file));assertEquals("bytes=1024-2047",server.takeRequest().getHeader("Range"));assertEquals(1,server.requestCount)}
        finally {server.shutdown();dir.deleteRecursively()}
    }
    @Test fun unverifiedReplacementDoesNotOverwriteTheOriginal() {
        val dir=Files.createTempDirectory("mcpy-integrity-bad").toFile();val file=java.io.File(dir,"engine.apk");file.writeBytes(byteArrayOf(1,2,3))
        val server=MockWebServer();server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-2/3").setBody(Buffer().write(byteArrayOf(4,5,6))))
        try {var failed=false;try {RuntimeIntegrity.repair(file,server.url("/engine.apk").toString(),3,3,listOf(hash(byteArrayOf(7,8,9)))) {}}catch(e:IllegalStateException){failed=true};assertTrue(failed);assertArrayEquals(byteArrayOf(1,2,3),file.readBytes())}
        finally {server.shutdown();dir.deleteRecursively()}
    }
}
