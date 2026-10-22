package dev.mcpy.runtime

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

data class RuntimeProgress(val phase: String, val bytes: Long = 0, val total: Long = 0)
data class InstalledRuntime(val version: String, val directory: File, val apk: File)

class RuntimeRepository(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "runtimes").apply { mkdirs() }
    companion object {
        private val installMutex = Mutex()
        const val VERSION = "3.10.100.299889"
        const val SHA256 = "c0f714fcfe3e79d596fca2482f4fdd3c9c3db1568083a4acb1fbd630c7417cb1"
        private const val CDN_KEY = "mEE7Cot48r9j2AvEL2N6jpXEc"
    }
    fun installed(): InstalledRuntime? {
        val dir = File(root, SHA256)
        return if (File(dir, "ready.json").isFile && File(dir,"engine.apk").length()==2315617832L && listOf("libminecraftpe.so","libc++_shared.so","libfmod.so","libHttpClient.Android.so","libPlayFabMultiplayer.so").all{File(dir,"lib/$it").length()>0}) InstalledRuntime(VERSION, dir, File(dir, "engine.apk")) else null
    }
    private fun connection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30000; readTimeout = 60000; instanceFollowRedirects = false
    }
    private fun digest(bytes: ByteArray, algorithm: String) = MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }
    suspend fun prepare(progress: (RuntimeProgress) -> Unit): InstalledRuntime = installMutex.withLock { prepareLocked(progress) }
    private suspend fun prepareLocked(progress: (RuntimeProgress) -> Unit): InstalledRuntime = withContext(Dispatchers.IO) {
        installed()?.let {
            File(context.noBackupFilesDir,"active-runtime.txt").writeText(it.directory.path)
            return@withContext it
        }
        require(android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "运行内核需要 ARM64 手机" }
        progress(RuntimeProgress("准备已适配的官方运行环境"))
        val source = "https://g79.gdl.netease.com/dev_launcher_$VERSION.apk"
        val parsed = URL(source)
        val expiry = (System.currentTimeMillis()/1000 + 43200).toString(16)
        val signed = "$source?key1=${digest((CDN_KEY+parsed.path+expiry).toByteArray(), "MD5")}&key2=$expiry"
        val damaged=File(root,SHA256)
        if(damaged.exists())check(damaged.deleteRecursively()){ "无法清理损坏的运行缓存" }
        val stage = File(root, "$SHA256.staging").apply { mkdirs() }
        val apk = File(stage, "engine.apk")
        val part = File(stage, "engine.apk.part")
        if (!apk.exists()) {
            RangeDownloader().download(signed, part, progress)
            check(part.renameTo(apk))
        }
        progress(RuntimeProgress("校验 APK 身份"))
        if (RuntimeIntegrity.sha256(apk) != SHA256) {
            apk.setWritable(true)
            val profile=JSONObject(context.assets.open("engine-profile.json").bufferedReader().use {it.readText()})
            val blocks=profile.getJSONArray("blocks")
            RuntimeIntegrity.repair(apk,signed,profile.getLong("size"),profile.getInt("blockSize"),(0 until blocks.length()).map {blocks.getString(it)},progress)
        }
        check(RuntimeIntegrity.sha256(apk)==SHA256) { "APK 摘要与已审核版本不符" }
        val packageInfo = context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("无效 APK")
        check(packageInfo.packageName == "com.netease.mctest")
        val certificates=packageInfo.signingInfo?.apkContentsSigners ?: error("APK 缺少签名身份")
        check(certificates.any { digest(it.toByteArray(), "SHA-256") == "09efd061af7b7b28a4c1ea362151e760b550f58507691204823630daa878a807" }) { "APK 签名身份不匹配" }
        progress(RuntimeProgress("准备原生库与游戏资源"))
        ZipFile(apk).use { zip ->
            val entries=zip.entries()
            while(entries.hasMoreElements()) {
                currentCoroutineContext().ensureActive()
                val e=entries.nextElement()
                if (!e.name.startsWith("lib/arm64-v8a/") || e.isDirectory) continue
                val out=File(stage,"lib/"+e.name.substringAfterLast('/'))
                require(out.canonicalPath.startsWith(stage.canonicalPath+File.separator))
                out.parentFile!!.mkdirs()
                zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                check(out.length()==e.size);out.setReadOnly()
            }
        }
        apk.setReadOnly()
        val receipt=JSONObject().put("version",VERSION).put("sha256",SHA256).put("source",source).put("downloadedOnDevice",true)
        File(stage,"ready.json").writeText(receipt.toString(2))
        val target=File(root,SHA256);check(stage.renameTo(target))
        File(context.noBackupFilesDir,"active-runtime.txt").writeText(target.path)
        progress(RuntimeProgress("运行环境已就绪"))
        InstalledRuntime(VERSION,target,File(target,"engine.apk"))
    }
}
