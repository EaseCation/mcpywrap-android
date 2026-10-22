package dev.mcpy.game;

import java.io.IOException;
import java.net.*;
import java.util.Collections;
import java.util.List;

/** Offline worlds have no launcher account. Isolate its HTTP callbacks from online services.
 * This VM is the :game process; downloads and Git run in the separate editor VM.
 * A locally owned rejecting proxy gives HTTP clients their normal IOException/failure path.
 */
public final class OfflineNetworkPolicy {
    private static ServerSocket rejector;
    private OfflineNetworkPolicy() {}
    public static synchronized void enable() throws IOException {
        if (rejector != null) return;
        rejector = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        final Proxy offline = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", rejector.getLocalPort()));
        Thread thread = new Thread(() -> {
            while (!rejector.isClosed()) {
                try (Socket socket = rejector.accept()) { socket.setSoLinger(true, 0); }
                catch (IOException e) { if (rejector.isClosed()) return; }
            }
        }, "mcpy-offline-http");
        thread.setDaemon(true);
        thread.start();
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                String host = uri.getHost();
                boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
                return Collections.singletonList(local ? Proxy.NO_PROXY : offline);
            }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {}
        });
        android.util.Log.i("McpyOffline", "External Java HTTP disabled in offline game process; loopback remains available");
    }
}
