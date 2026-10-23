package dev.mcpywrap.bridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class HostStoreTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun approved(store:HostStore,workspace:File):JSONObject{
        val pending=store.beginPair(JSONObject().put("code",store.newCode()).put("label","Test client"))
        val probe=File(workspace,pending.getString("probe"))
        val proof=MessageDigest.getInstance("SHA-256").digest(probe.readBytes()).joinToString(""){"%02x".format(it)}
        File(probe.parentFile,probe.nameWithoutExtension+".response").writeText(pending.getString("challenge"))
        store.verifyPair(pending.getString("id"),JSONObject().put("secret",pending.getString("secret")).put("proof",proof))
        store.approve(pending.getString("id"),true)
        return store.pairResult(pending.getString("id"),JSONObject().put("secret",pending.getString("secret")))
    }
    @Test fun userHandoffIsVisibleAtTopLevelAndRemovedAfterCancellation(){
        val store=HostStore(temporary.newFolder("private"),temporary.newFolder("public"))
        val detail=dev.mcpywrap.workspace.UserIntervention.attach(JSONObject().put("state","waiting_user"))
        val value=JSONObject().put("operation_id","a".repeat(64)).put("state","waiting_user").put("result",detail)
        val visible=store.publicJob(value)
        assertTrue(visible.getBoolean("needs_user_action"));assertEquals("ask_user",visible.getString("next_action"))
        assertTrue(visible.getString("resume_command").contains("a".repeat(64)))
        val cancelled=store.publicJob(value.put("state","cancelled"))
        assertFalse(cancelled.has("needs_user_action"));assertFalse(cancelled.getJSONObject("result").has("user_message"))
    }
    @Test fun pairingRequiresReadWriteProofAndExplicitApproval(){
        val root=temporary.newFolder("public");val store=HostStore(temporary.newFolder("private"),root)
        val pending=store.beginPair(JSONObject().put("code",store.newCode()))
        assertEquals("verifying",store.pairResult(pending.getString("id"),JSONObject().put("secret",pending.getString("secret"))).getString("state"))
        assertThrows(IllegalArgumentException::class.java){store.approve(pending.getString("id"),true)}
        assertThrows(IllegalArgumentException::class.java){store.verifyPair(pending.getString("id"),JSONObject().put("secret",pending.getString("secret")).put("proof","wrong"))}
    }
    @Test fun credentialsAreHashedPrivateAndRevocable(){
        val root=temporary.newFolder("public");val private=temporary.newFolder("private");val store=HostStore(private,root)
        val pair=approved(store,root);val token=pair.getString("token");val id=pair.getString("client_id")
        assertEquals(id,store.authenticate("Bearer $token"))
        assertFalse(File(private,"clients.json").readText().contains(token))
        assertFalse(root.walk().filter{it.isFile}.any{it.readText().contains(token)})
        assertFalse(store.publicClients().toString().contains(token))
        store.revoke(id);assertNull(store.authenticate("Bearer $token"))
    }
    @Test fun expiredCodeCannotPair(){
        var time=1000L;val store=HostStore(temporary.newFolder("private"),temporary.newFolder("public")){time}
        val code=store.newCode();time+=61*60_000
        assertThrows(IllegalArgumentException::class.java){store.beginPair(JSONObject().put("code",code))}
    }
    @Test fun requestIdsAreIdempotentAndScopedToClients(){
        val store=HostStore(temporary.newFolder("private"),temporary.newFolder("public"))
        val request=JSONObject().put("request_id","a".repeat(32)).put("operation","build").put("parameters",JSONObject())
        val (first,fresh)=store.submit("one",request);assertTrue(fresh)
        store.update(first.getString("operation_id")){it.put("state","completed").put("result",JSONObject().put("value",42))}
        val (again,second)=store.submit("one",JSONObject(request.toString()));assertFalse(second);assertEquals(42,again.getJSONObject("result").getInt("value"))
        assertTrue(store.submit("two",request).second)
        assertThrows(IllegalArgumentException::class.java){store.submit("one",JSONObject(request.toString()).put("operation","run"))}
        assertThrows(IllegalArgumentException::class.java){store.job(first.getString("operation_id"),"two")}
    }
    @Test fun restartMarksIncompleteJobsWithoutReplayingThem(){
        val private=temporary.newFolder("private");val root=temporary.newFolder("public");val store=HostStore(private,root)
        val (job,_)=store.submit("client",JSONObject().put("request_id","a".repeat(32)).put("operation","run"))
        val next=HostStore(private,root)
        assertEquals("interrupted",next.job(job.getString("operation_id"),"client").getString("state"))
        assertEquals(store.installationId,next.installationId)
    }
    @Test fun preparationResultsCanBeRetriedAfterWorkerAdvancesAndServiceRestarts(){
        val private=temporary.newFolder("private");val root=temporary.newFolder("public");val store=HostStore(private,root)
        val (job,_)=store.submit("client",JSONObject().put("request_id","b".repeat(32)).put("operation","build"))
        val id=job.getString("operation_id");val result=JSONObject().put("ok",true).put("result",42)
        store.update(id){it.put("state","waiting_preparation").put("preparation",JSONObject().put("id","step"))}
        assertTrue(store.acceptPreparation(id,"client","step",result))
        store.update(id){it.put("state","completed");it.remove("preparation")}
        val restarted=HostStore(private,root)
        assertFalse(restarted.acceptPreparation(id,"client","step",result))
        assertFalse(restarted.publicJob(restarted.job(id)).has("preparation_receipts"))
        assertThrows(IllegalArgumentException::class.java){restarted.acceptPreparation(id,"client","step",JSONObject().put("ok",false))}
        assertThrows(IllegalArgumentException::class.java){restarted.acceptPreparation(id,"another","step",result)}
    }

    @Test fun ordinaryHttpClientCanRequestFileAccessWithoutFakeMountProof(){
        val root=temporary.newFolder("public");val store=HostStore(temporary.newFolder("private"),root)
        val pending=store.beginPair(JSONObject().put("code",store.newCode()).put("access","files").put("label","HTTP editor"))
        assertFalse(pending.has("probe"));assertFalse(root.walk().any{it.isFile})
        assertEquals("pending",store.pairResult(pending.getString("id"),JSONObject().put("secret",pending.getString("secret"))).getString("state"))
        store.approve(pending.getString("id"),true)
        val pair=store.pairResult(pending.getString("id"),JSONObject().put("secret",pending.getString("secret")))
        assertEquals(pair.getString("client_id"),store.authenticate("Bearer "+pair.getString("token")))
        assertFalse(store.sharedAccess(pair.getString("client_id")))
    }

    @Test fun crashLeftTemporaryFilesDoNotBlockRecoveryOrReuseEventCursors(){
        val private=temporary.newFolder("private");val public=temporary.newFolder("public")
        val store=HostStore(private,public){10000L}
        val sequence=store.nextEventSequence()
        File(private,"jobs").mkdirs();File(private,"jobs/.partial.json.temporary.tmp").writeText("{unfinished")
        val restarted=HostStore(private,public){1L}
        assertTrue(restarted.nextEventSequence()>sequence)
        assertTrue(restarted.recentJobs().isEmpty())
    }

    @Test fun pairingCanResumeAfterMissingMountLostRepliesAndProcessRestart(){
        val root=temporary.newFolder("public");val private=temporary.newFolder("private")
        var time=1000L;var store=HostStore(private,root){time}
        val code=store.newCode();time+=40*60_000 // Installing tools does not consume confirmation time.
        val request=JSONObject().put("code",code).put("request_id","c".repeat(32)).put("label","Minis")
        val pending=store.beginPair(request);val id=pending.getString("id")
        val secret=JSONObject().put("secret",pending.getString("secret"))
        assertEquals(code,store.invitationCode())
        assertThrows(IllegalArgumentException::class.java){store.beginPair(JSONObject(request.toString()).put("request_id","d".repeat(32)))}
        assertEquals(id,store.beginPair(request).getString("id"))
        assertThrows(IllegalArgumentException::class.java){store.verifyPair(id,JSONObject(secret.toString()).put("proof","wrong"))}
        store=HostStore(private,root){time}
        assertEquals(id,store.beginPair(request).getString("id"))
        val probe=File(root,pending.getString("probe"))
        val hash=MessageDigest.getInstance("SHA-256").digest(probe.readBytes()).joinToString(""){"%02x".format(it)}
        File(probe.parentFile,probe.nameWithoutExtension+".response").writeText(pending.getString("challenge"))
        val verify=JSONObject(secret.toString()).put("proof",hash)
        store.verifyPair(id,verify);assertFalse(probe.exists())
        assertEquals("pending",store.verifyPair(id,verify).getString("state"))
        store=HostStore(private,root){time};store.approve(id,true)
        val approved=store.pairResult(id,secret)
        assertEquals("approved",store.verifyPair(id,verify).getString("state"))
        assertEquals(approved.getString("token"),HostStore(private,root){time}.pairResult(id,secret).getString("token"))
        assertEquals(1,store.publicClients().size)
    }

    @Test fun confirmationExpiresWithoutAuthorizingAndWrongSecretCannotResume(){
        var time=1000L;val store=HostStore(temporary.newFolder("private"),temporary.newFolder("public")){time}
        val pending=store.beginPair(JSONObject().put("code",store.newCode()).put("access","files"))
        assertThrows(IllegalArgumentException::class.java){store.pairResult(pending.getString("id"),JSONObject().put("secret","wrong"))}
        time+=16*60_000
        assertTrue(store.pendingPairs().isEmpty())
        assertThrows(IllegalArgumentException::class.java){store.approve(pending.getString("id"),true)}
        assertTrue(store.publicClients().isEmpty())
    }

}
