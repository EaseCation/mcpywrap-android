package dev.mcpywrap.android

import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.DexClassLoader
import dev.mcpy.runtime.RuntimeRepository
import java.io.File
import org.junit.Test

/** Optional read-only inspection of the locally downloaded GameActivity lifecycle ABI. */
class RuntimeAbiInspection {
    @Test fun inspectLifecycle(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val runtime=requireNotNull(RuntimeRepository(context).installed())
        val loader=DexClassLoader(runtime.apk.path,context.codeCacheDir.path,runtime.directory.resolve("lib").path,ClassLoader.getSystemClassLoader().parent)
        val type=Class.forName("com.google.androidgamesdk.GameActivity",false,loader)
        File("/sdcard/mcpy/logs/product-evidence/gameactivity-abi.txt").writeText(type.declaredFields.joinToString("\n"){it.toString()}+"\n"+type.declaredMethods.joinToString("\n"){it.toString()})
    }
}
