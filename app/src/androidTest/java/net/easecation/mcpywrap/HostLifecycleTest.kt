package net.easecation.mcpywrap

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import dev.mcpywrap.bridge.HostController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress

class HostLifecycleTest {
    @Test fun occupiedPortFailsExplicitlyAndCanRecover()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val host=HostController.get(app)
        try{
            ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use{occupied->
                host.port=occupied.localPort
                var failed=false
                try{host.start()}catch(_:Exception){failed=true}
                assertTrue("Port conflict must not claim successful startup",failed)
                assertFalse(host.online);assertFalse(host.starting);assertNotNull(host.error)
            }
            host.port=17653;host.start();assertTrue(host.online);assertNull(host.error)
        }finally{host.stop();host.port=17653}
    }
}
