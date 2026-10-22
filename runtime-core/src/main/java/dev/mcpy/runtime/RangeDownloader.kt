package dev.mcpy.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/** Persist completed prefix plus independently resumable, bounded range files. */
internal class RangeDownloader {
    private fun connect(url:String,start:Long,end:Long)=(URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout=30000;readTimeout=60000;instanceFollowRedirects=false
        setRequestProperty("Range","bytes=$start-$end")
    }
    suspend fun download(url:String,target:File,progress:(RuntimeProgress)->Unit)=coroutineScope {
        val probe=connect(url,0,0)
        val total=try { check(probe.responseCode==206);probe.getHeaderField("Content-Range").substringAfterLast('/').toLong() } finally {probe.disconnect()}
        require(total in 1..8L*1024*1024*1024)
        val offset=target.length();require(offset<=total)
        require(target.parentFile!!.usableSpace>2*(total-offset)+1024L*1024*1024) { "空间不足" }
        val received=AtomicLong(offset);val last=AtomicLong(0);val semaphore=Semaphore(4)
        val chunkSize=32L*1024*1024
        val tasks=(offset until total step chunkSize).map { start ->
            val end=minOf(start+chunkSize,total)-1
            async(Dispatchers.IO) { semaphore.withPermit {
                val file=File(target.parentFile,"range-$start-$end.part")
                require(file.length()<=end-start+1)
                received.addAndGet(file.length())
                var attempts=0
                while(file.length()<end-start+1) {
                    val begin=start+file.length();val request=connect(url,begin,end)
                    try {
                        check(request.responseCode==206) { "CDN HTTP ${request.responseCode}" }
                        check(request.getHeaderField("Content-Range")=="bytes $begin-$end/$total") { "CDN 分段范围不匹配" }
                        FileOutputStream(file,true).use { out -> request.inputStream.use { input ->
                            val buffer=ByteArray(256*1024)
                            while(true) { ensureActive();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n)
                                val current=received.addAndGet(n.toLong());val now=System.currentTimeMillis();val before=last.get()
                                if(now-before>500&&last.compareAndSet(before,now))progress(RuntimeProgress("从网易 CDN 下载",current,total))
                            }
                            out.fd.sync()
                        } }
                        check(file.length()==end-start+1)
                    } catch(error:java.io.IOException) {
                        if(++attempts>=3)throw error
                        delay(500L*attempts)
                    } finally { request.disconnect() }
                }
                file
            } }
        }
        FileOutputStream(target,true).use { out ->
            for(task in tasks) {val chunk=task.await();chunk.inputStream().use {it.copyTo(out)};out.fd.sync();check(chunk.delete())}
        }
        check(target.length()==total)
    }
}
