package net.easecation.mcpywrap

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import dev.mcpywrap.bridge.HostController
import dev.mcpywrap.workspace.WorkspaceRepository
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

class HostProtocolTest {
    @Test fun realHttpPairingBuildReplayWebsocketAndRevocation()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val controller=HostController.get(app)
        controller.port=17653
        controller.start()
        var token:String?=null
        fun http(method:String,path:String,data:JSONObject?=null,auth:Boolean=true):Pair<Int,JSONObject>{
            val connection=URL(controller.endpoint+"/v2"+path).openConnection() as HttpURLConnection
            connection.requestMethod=method;connection.connectTimeout=5000;connection.readTimeout=15000
            if(auth&&token!=null)connection.setRequestProperty("Authorization","Bearer $token")
            if(data!=null){connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{it.write(data.toString().toByteArray())}}
            val code=connection.responseCode
            val body=(if(code>=400)connection.errorStream else connection.inputStream).bufferedReader().use{it.readText()}
            connection.disconnect();return code to JSONObject(body)
        }
        try {
            assertEquals(401,http("GET","/projects").first)
            assertEquals(2,http("GET","/capabilities",auth=false).second.getInt("protocol_version"))
            val pending=http("POST","/pairings",JSONObject().put("code",controller.store.newCode()).put("label","HTTP fixture"),false).second
            val probe=File("/sdcard/mcpy",pending.getString("probe"))
            val hash=MessageDigest.getInstance("SHA-256").digest(probe.readBytes()).joinToString(""){"%02x".format(it)}
            File(probe.parentFile,probe.nameWithoutExtension+".response").writeText(pending.getString("challenge"))
            val id=pending.getString("id");val secret=pending.getString("secret")
            assertEquals(200,http("POST","/pairings/$id/verify",JSONObject().put("secret",secret).put("proof",hash),false).first)
            assertEquals("pending",http("POST","/pairings/$id/result",JSONObject().put("secret",secret),false).second.getString("state"))
            controller.store.approve(id,true)
            val paired=http("POST","/pairings/$id/result",JSONObject().put("secret",secret),false).second
            token=paired.getString("token");val client=paired.getString("client_id")
            assertEquals(200,http("GET","/projects").first)
            val project=controller.application.repository.createProject("QA-HTTP-${System.nanoTime()}")
            val request=JSONObject().put("request_id",UUID.randomUUID().toString()).put("operation","build")
                .put("project","projects/"+project.folder).put("parameters",JSONObject())
            val job=http("POST","/operations",request).second.getString("operation_id")
            assertEquals(job,http("POST","/operations",request).second.getString("operation_id"))
            var status=JSONObject()
            withTimeout(45_000){while(true){status=http("GET","/operations/$job").second;if(status.getString("state") in listOf("completed","failed"))break;delay(150)}}
            assertEquals(status.toString(),"completed",status.getString("state"))
            assertTrue(File(status.getJSONObject("result").getString("output"),"behavior_pack/WelcomeDemo/client.py").isFile)
            assertEquals("completed",http("POST","/operations",request).second.getString("state"))
            assertEquals(400,http("POST","/operations",JSONObject(request.toString()).put("operation","run")).first)
            assertEquals(400,http("GET","/files?project=${project.folder}&path=../../outside").first)
            val path="behavior_pack/WelcomeDemo/client.py"
            val original=http("GET","/files?project=${project.folder}&path=$path").second
            File(controller.application.repository.projectDir(project.folder),path).appendText("\n# external edit\n")
            val fileRequest=JSONObject().put("request_id",UUID.randomUUID().toString()).put("project",project.folder).put("path",path).put("digest",original.getString("digest")).put("text","# stale overwrite")
            val writeId=http("PUT","/files",fileRequest).second.getString("operation_id")
            withTimeout(10_000){while(true){status=http("GET","/operations/$writeId").second;if(status.getString("state") in listOf("completed","failed"))break;delay(100)}}
            assertEquals(status.toString(),"failed",status.getString("state"))
            assertEquals(writeId,http("PUT","/files",fileRequest).second.getString("operation_id"))
            Socket("127.0.0.1",controller.port).use{socket->
                socket.soTimeout=5000
                socket.getOutputStream().write(("GET /v2/events HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer $token\r\n\r\n").toByteArray())
                val input=socket.getInputStream();val header=StringBuilder()
                while(!header.endsWith("\r\n\r\n")){val byte=input.read();assertTrue(byte>=0);header.append(byte.toChar());assertTrue(header.length<16384)}
                assertTrue(header.toString(),header.startsWith("HTTP/1.1 101"))
                controller.event("fixture",JSONObject().put("verified",true),client)
                assertEquals(1,input.read() and 0x0f)
            }
            // A cancelled external preparation must release both the application lock
            // and the isolated Python worker, allowing the queued operation to finish.
            val waitingProject=controller.application.repository.createProject("QA-Waiting-${System.nanoTime()}")
            val waitingRoot=controller.application.repository.projectDir(waitingProject.folder)
            File(waitingRoot,"must-not-run.py").writeText("raise AssertionError('cancelled stage must not execute')")
            File(waitingRoot,"pyproject.toml").writeText("""
                [project]
                name = "waiting-fixture"
                version = "1.0.0"
                [tool.mcpywrap]
                target_dir = "build"
                [tool.mcpywrap.scripts.prepare]
                python = "must-not-run.py"
                [tool.mcpywrap.hooks]
                before_build = ["prepare"]
            """.trimIndent())
            val waitingRequest=JSONObject(request.toString()).put("request_id",UUID.randomUUID().toString()).put("preparation_client",true).put("project","projects/"+waitingProject.folder)
            val waiting=http("POST","/operations",waitingRequest).second.getString("operation_id")
            withTimeout(15_000){while(http("GET","/operations/$waiting").second.getString("state")!="waiting_preparation")delay(100)}
            val queued=http("POST","/operations",JSONObject(request.toString()).put("request_id",UUID.randomUUID().toString())).second.getString("operation_id")
            assertEquals("queued",http("GET","/operations/$queued").second.getString("state"))
            assertEquals(200,http("POST","/operations/$waiting/cancel",JSONObject()).first)
            withTimeout(30_000){while(true){status=http("GET","/operations/$queued").second;if(status.getString("state") in listOf("completed","failed"))break;delay(100)}}
            assertEquals(status.toString(),"completed",status.getString("state"))
            assertEquals("interrupted",http("GET","/operations/$waiting").second.getString("state"))
            assertEquals(200,http("POST","/clients/revoke",JSONObject()).first)
            assertEquals(401,http("GET","/projects").first)
        }finally{controller.stop()}
    }
}
