package dev.mcpywrap.workspace

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import java.io.File
import java.net.URI

class ProjectImporter(private val context: Context, private val repository: WorkspaceRepository) {
    suspend fun git(address: String, title: String, progress: (String) -> Unit): WorkProject {
        val uri=URI(address.trim())
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null) { "请填写公开仓库的 HTTPS Git 地址，不含密码或令牌" }
        val coroutine=currentCoroutineContext();val stage=repository.staging()
        try {
            Git.cloneRepository().setURI(uri.toASCIIString()).setDirectory(stage).setDepth(1).setCloneSubmodules(false).setTimeout(30).setProgressMonitor(object:ProgressMonitor {
                override fun start(totalTasks: Int) {}
                override fun beginTask(title: String,totalWork: Int) { progress("正在克隆仓库 · $title") }
                override fun update(completed: Int) {}
                override fun endTask() {}
                override fun isCancelled(): Boolean = runCatching{coroutine.ensureActive()}.isFailure
                override fun showDuration(enabled: Boolean) {}
            }).call().use { }
            coroutine.ensureActive()
            return repository.publishImport(stage,title,address.trim())
        } finally { stage.deleteRecursively() }
    }
    suspend fun folder(uri: Uri, title: String): WorkProject {
        val source=requireNotNull(DocumentFile.fromTreeUri(context,uri)) { "无法打开文件夹" }
        val stage=repository.staging();var count=0;var bytes=0L
        suspend fun copy(doc: DocumentFile, destination: File, depth: Int) {
            currentCoroutineContext().ensureActive();require(depth<32 && ++count<=WorkspaceRepository.MAX_ENTRIES) { "目录太深或文件过多" }
            if(doc.isDirectory) { destination.mkdirs();doc.listFiles().filter{it.name!=".git"}.forEach{child->copy(child,File(destination,WorkspaceRepository.name(child.name?:error("无法读取文件名"))),depth+1)} }
            else { destination.parentFile!!.mkdirs();requireNotNull(context.contentResolver.openInputStream(doc.uri)).use{input->destination.outputStream().use{output->val buffer=ByteArray(65536);while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;bytes+=n;require(bytes<=WorkspaceRepository.MAX_IMPORT){"项目超过 512 MB"};output.write(buffer,0,n)}}} }
        }
        try { copy(source,stage,0);return repository.publishImport(stage,title,uri.toString()) } finally { stage.deleteRecursively() }
    }
}
