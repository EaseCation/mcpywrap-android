package dev.mcpy.runtime

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A pinned block manifest permits repair without ever accepting an unverified runtime. */
internal object RuntimeIntegrity {
    fun sha256(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b=ByteArray(1024*1024);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)} }
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
    fun repair(file:File,url:String,size:Long,blockSize:Int,expected:List<String>,progress:(RuntimeProgress)->Unit) {
        require(file.length()==size)
        val bad=mutableListOf<Int>();val buffer=ByteArray(1024*1024)
        file.inputStream().use { input ->
            for(index in expected.indices) {
                val digest=MessageDigest.getInstance("SHA-256");var remaining=minOf(blockSize.toLong(),size-index.toLong()*blockSize)
                while(remaining>0) {val n=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt());check(n>0);digest.update(buffer,0,n);remaining-=n}
                if(digest.digest().joinToString("") {"%02x".format(it)}!=expected[index])bad.add(index)
            }
        }
        for(index in bad) {
            progress(RuntimeProgress("修复未通过校验的下载分段 ${index+1}/${expected.size}"))
            val start=index.toLong()*blockSize;val end=minOf(start+blockSize,size)-1
            val temporary=File(file.parentFile,"repair-$index.part")
            val request=(URL(url).openConnection() as HttpURLConnection).apply {connectTimeout=30000;readTimeout=60000;instanceFollowRedirects=false;setRequestProperty("Range","bytes=$start-$end")}
            try {
                check(request.responseCode==206)
                check(request.getHeaderField("Content-Range")=="bytes $start-$end/$size")
                request.inputStream.use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.length()==end-start+1&&sha256(temporary)==expected[index]) {"修复分段仍未通过校验"}
                RandomAccessFile(file,"rw").use { output -> output.seek(start);temporary.inputStream().use { input -> while(true){val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n)} };output.fd.sync() }
            } finally {request.disconnect();temporary.delete()}
        }
    }
}
