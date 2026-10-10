package com.zqr.freenote.data.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * 局域网设备发现（与 FreeNote p2p/discovery.py 一致）：
 * - 每 8 秒向组播组 239.255.42.42:8766 公告设备 id/名称/同步端口/摘要；
 * - 公告带「账户 + 口令」派生的 tag，不同账户或口令的设备互相不可见；
 * - 收到陌生设备公告后单播回敬，加速相互发现。
 */
class P2PDiscovery(
    private val context: Context,
    private val announceProvider: () -> Announcement?,
    private val tagProvider: () -> String,
    private val onPeer: (Announcement, String, Int) -> Unit
) {

    data class Announcement(
        val deviceId: String,
        val name: String,
        val port: Int,
        val digest: String = ""
    ) {
        fun toWire(tag: String): ByteArray = JSONObject()
            .put("magic", MAGIC)
            .put("v", SyncProtocol.PROTOCOL_VERSION)
            .put("tag", tag)
            .put("id", deviceId)
            .put("name", name)
            .put("port", port)
            .put("digest", digest)
            .toString()
            .toByteArray(Charsets.UTF_8)

        companion object {
            fun fromWire(data: ByteArray): Announcement? = try {
                val payload = JSONObject(String(data, Charsets.UTF_8))
                if (payload.optString("magic") != MAGIC) {
                    null
                } else {
                    val deviceId = payload.optString("id")
                    if (deviceId.isBlank()) {
                        null
                    } else {
                        Announcement(
                            deviceId = deviceId,
                            name = payload.optString("name"),
                            port = payload.optInt("port"),
                            digest = payload.optString("digest")
                        )
                    }
                }
            } catch (e: Exception) {
                null
            }

            fun tagOf(data: ByteArray): String = try {
                JSONObject(String(data, Charsets.UTF_8)).optString("tag")
            } catch (e: Exception) {
                ""
            }

            fun versionOf(data: ByteArray): Int = try {
                JSONObject(String(data, Charsets.UTF_8)).optInt("v")
            } catch (e: Exception) {
                0
            }
        }
    }

    companion object {
        const val DISCOVERY_PORT = 8766
        const val MULTICAST_GROUP = "239.255.42.42"
        const val DISCOVERY_INTERVAL_MS = 8_000L
        private const val STARTUP_INTERVAL_MS = 1_500L
        private const val STARTUP_WINDOW_MS = 5_000L
        private const val MAGIC = "freenote-announce"
        private const val TAG = "P2PDiscovery"
    }

    var selfId: String = ""

    @Volatile
    private var running = false

    @Volatile
    private var socket: MulticastSocket? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var lastReply = 0L

    // ------------------------------------------------------------------ 生命周期

    fun start(): Boolean {
        if (running) return true
        acquireMulticastLock()
        val sock = try {
            MulticastSocket(DISCOVERY_PORT)
        } catch (e: IOException) {
            Log.d(TAG, "discovery socket failed: ${e.message}")
            releaseMulticastLock()
            return false
        }
        try {
            sock.reuseAddress = true
            sock.broadcast = true
            sock.timeToLive = 1
            sock.soTimeout = 500
            try {
                sock.joinGroup(InetAddress.getByName(MULTICAST_GROUP))
            } catch (e: IOException) {
                // 组播被阻止时仍可通过手动添加设备同步
                Log.d(TAG, "joinGroup failed: ${e.message}")
            }
        } catch (e: IOException) {
            runCatching { sock.close() }
            releaseMulticastLock()
            return false
        }
        socket = sock
        running = true
        thread = Thread({ loop() }, "freenote-p2p-discovery").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        // 先置停并关 socket：close 让阻塞中的 receive 立即抛 IOException，
        // loop 检查 !running 后退出，避免调用方长时间卡在 join
        running = false
        val sock = socket
        socket = null
        runCatching { sock?.close() }
        announceExecutor.shutdownNow()
        val t = thread
        if (t != null && t.isAlive) {
            t.join(1500)
        }
        thread = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            val lock = wifi.createMulticastLock("freenote-p2p") ?: return
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (e: Exception) {
            Log.d(TAG, "multicast lock failed: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    // ------------------------------------------------------------------ 收发

    // 公告专用单线程：公告需经 announceProvider 查询状态摘要（全表哈希，笔记多时可达数十毫秒），
    // 转发到这里执行，绝不阻塞调用方线程（可能是主线程）
    private val announceExecutor = Executors.newSingleThreadExecutor { target ->
        Thread(target, "freenote-p2p-announce").apply { isDaemon = true }
    }

    fun announceNow() {
        if (!running) return
        // 与 stop() 竞争时 execute 可能被拒绝，静默忽略（停机后本就不应再公告）
        runCatching {
            announceExecutor.execute {
                if (running) sendAnnouncement()
            }
        }
    }

    fun sendUnicast(address: String, port: Int) {
        val sock = socket ?: return
        val announcement = announceProvider() ?: return
        val tag = tagProvider()
        if (tag.isBlank()) return
        sendTo(sock, announcement.toWire(tag), address, port)
    }

    private fun sendAnnouncement() {
        val sock = socket ?: return
        val announcement = announceProvider() ?: return
        val tag = tagProvider()
        if (tag.isBlank()) return
        val data = announcement.toWire(tag)
        sendTo(sock, data, MULTICAST_GROUP, DISCOVERY_PORT)
        sendTo(sock, data, "255.255.255.255", DISCOVERY_PORT)
    }

    private fun sendTo(sock: MulticastSocket, data: ByteArray, address: String, port: Int) {
        // loop 线程与公告线程会并发使用同一 socket，串行化发送
        synchronized(sock) {
            try {
                sock.send(DatagramPacket(data, data.size, InetAddress.getByName(address), port))
            } catch (e: IOException) {
                Log.d(TAG, "send failed: ${e.message}")
            }
        }
    }

    private fun loop() {
        val buffer = ByteArray(4096)
        val startedAt = System.currentTimeMillis()
        var lastAnnounce = 0L
        while (running) {
            val now = System.currentTimeMillis()
            val interval =
                if (now - startedAt < STARTUP_WINDOW_MS) STARTUP_INTERVAL_MS else DISCOVERY_INTERVAL_MS
            if (now - lastAnnounce >= interval) {
                lastAnnounce = now
                sendAnnouncement()
            }
            val sock = socket ?: break
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                sock.receive(packet)
                handlePacket(packet.data.copyOf(packet.length), packet.address?.hostAddress.orEmpty(), packet.port)
            } catch (e: SocketTimeoutException) {
                // 正常超时：继续循环
            } catch (e: IOException) {
                if (!running) break
            }
        }
    }

    private fun handlePacket(data: ByteArray, address: String, sourcePort: Int) {
        val expected = tagProvider()
        if (expected.isBlank() || Announcement.tagOf(data) != expected) return
        if (Announcement.versionOf(data) != SyncProtocol.PROTOCOL_VERSION) return
        val announcement = Announcement.fromWire(data) ?: return
        if (announcement.deviceId == selfId) return
        val now = System.currentTimeMillis()
        if (now - lastReply > 2_000) {
            lastReply = now
            sendUnicast(address, sourcePort)
        }
        try {
            onPeer(announcement, address, sourcePort)
        } catch (e: Exception) {
            Log.d(TAG, "onPeer error: ${e.message}")
        }
    }
}
