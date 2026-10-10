package com.zqr.freenote.data.sync

import android.content.Context
import com.zqr.freenote.data.AppDatabase
import com.zqr.freenote.data.AttachmentStore
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

/**
 * 同步总控：把 P2P（内置服务 + 局域网发现 + 客户端）与服务器同步串起来。
 *
 * 与 FreeNote（Windows）对 UI 暴露的接口对应：
 * - start()/stop()（App 前台运行期间）
 * - peers()/addManualPeer()/forgetPeer()
 * - syncNow()/syncPeer()
 * - notifyLocalChange() —— 本地写入后调用，触发公告与自动同步
 */
object SyncManager {
    private const val SYNC_COOLDOWN_MS = 4_000L
    private const val LOCAL_CHANGE_DEBOUNCE_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 主线程 scope：公告/防抖/配置重启等都收敛到这里，与 start/stop 串行，消除跨线程竞争。 */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * P2P 生命周期专用单线程：socket 创建、joinGroup、停止时 join 发现线程都可能阻塞
     * 数百毫秒到 1.5 秒，不能占用主线程；单线程队列同时保证 start/stop/重启严格按调用顺序执行。
     */
    private val p2pDispatcher = Executors.newSingleThreadExecutor { target ->
        Thread(target, "freenote-p2p-lifecycle").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val syncMutex = Mutex()
    private val lastAttempt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var store: SyncStore? = null

    @Volatile
    private var attachmentStore: AttachmentStore? = null

    @Volatile
    private var serverSyncClient: ServerSyncClient? = null

    @Volatile
    private var running = false

    @Volatile
    private var cachedKey: Triple<String, String, ByteArray>? = null

    /** P2P 服务参数快照：总开关 / 口令 / 账户 / 端口 / 发现开关，任一变化都要重启服务。 */
    private data class P2pConfigSnapshot(
        val p2pEnabled: Boolean,
        val passphrase: String,
        val account: String,
        val syncPort: Int,
        val discoveryEnabled: Boolean
    )

    // server/discovery 的生命周期变更仅主线程执行；但公告/同步线程会读取实例
    // （announcement() 读端口、announceNow/syncPeerInternal），加 @Volatile 保证可见性，
    // 陈旧读最坏导致一轮公告缺失/一次直连失败，由 8 秒公告周期自愈
    @Volatile
    private var server: P2PServer? = null
    @Volatile
    private var discovery: P2PDiscovery? = null
    // autoJob 等任务字段仅主线程取消/赋值
    private var autoJob: Job? = null
    private var startSyncJob: Job? = null
    private var debounceJob: Job? = null
    private var configChangeJob: Job? = null
    private var announceJob: Job? = null
    private var p2pConfigSnapshot: P2pConfigSnapshot? = null
    private var lastPeerPersist = 0L
    /** 保护 _peers 列表的读-改-写（发现线程与同步协程会并发更新）。 */
    private val peersLock = Any()

    private val _status = MutableStateFlow("未同步")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _peers = MutableStateFlow<List<SyncPeer>>(emptyList())
    val peers: StateFlow<List<SyncPeer>> = _peers.asStateFlow()

    /** 每次同步实际应用了远端改动时自增：界面据此重排提醒、刷新桌面小组件。 */
    private val _appliedChanges = MutableStateFlow(0)
    val appliedChanges: StateFlow<Int> = _appliedChanges.asStateFlow()

    // ------------------------------------------------------------------ 生命周期

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        SyncSettings.init(ctx)
        appContext = ctx
        val syncStore = SyncStore(AppDatabase.getDatabase(ctx))
        store = syncStore
        val attachments = AttachmentStore(ctx, AppDatabase.getDatabase(ctx))
        attachmentStore = attachments
        syncStore.attachmentInventoryProvider = { attachments.inventory() }
        serverSyncClient = ServerSyncClient(syncStore, attachments)
        _peers.value = SyncSettings.peers()
    }

    /** App 进入前台时调用（P2P 部分异步在专用线程执行，不阻塞主线程）。 */
    fun start() {
        if (appContext == null) return
        if (running) return
        running = true
        _peers.value = SyncSettings.peers()
        prunePeersForAccount()
        scope.launch(p2pDispatcher) { if (running) startP2P() }
        startAutoLoop()
        // 后台迁移 legacy 图片引用为内容寻址便携标记（有变更时触发一次同步）
        scope.launch {
            val migrated = runCatching { attachmentStore?.migrateStore() ?: 0 }.getOrDefault(0)
            if (migrated > 0 && running) notifyLocalChange()
        }
        // 回到前台后尽快静默同步一次（拉取服务器/局域网上的新改动）
        startSyncJob = scope.launch {
            delay(2_000)
            if (!running) return@launch
            autoSync()
        }
    }

    /** App 离开前台时调用：停止监听与发现，保留已知设备（P2P 停止异步执行）。 */
    fun stop() {
        running = false
        scope.launch(p2pDispatcher) { stopP2P() }
        startSyncJob?.cancel()
        startSyncJob = null
        autoJob?.cancel()
        autoJob = null
        debounceJob?.cancel()
        debounceJob = null
        configChangeJob?.cancel()
        configChangeJob = null
        announceJob?.cancel()
        announceJob = null
        persistPeers(force = true)
    }

    val p2pRunning: Boolean get() = server?.running == true
    val p2pPort: Int get() = server?.port ?: SyncSettings.config.value.syncPort

    private fun startP2P() {
        val cfg = SyncSettings.config.value
        // 网络功能默认关闭：P2P 总开关未开启或未配置口令前不监听端口、不参与发现
        if (!cfg.p2pEnabled || !cfg.p2pReady) return
        if (server == null) {
            val srv = P2PServer(
                store = store ?: return,
                attachments = attachmentStore ?: return,
                deviceId = cfg.deviceId,
                deviceName = cfg.deviceName,
                keyProvider = { groupKey() },
                accountFingerprintProvider = { accountFingerprint(cfg) },
                onRemoteSync = { deviceId, deviceName, address, port, applied, conflicts ->
                    onRemoteSync(deviceId, deviceName, address, port, applied, conflicts)
                },
                preferredPort = cfg.syncPort
            )
            if (srv.start()) {
                server = srv
                _status.value = "P2P 服务已启动（端口 ${srv.port}）"
            } else {
                _status.value = "无法启动 P2P 服务：端口被占用"
            }
        }
        if (discovery == null && cfg.discoveryEnabled) {
            val disc = P2PDiscovery(
                context = appContext ?: return,
                announceProvider = { announcement() },
                tagProvider = { tag() },
                onPeer = { announcement, address, sourcePort ->
                    onAnnounced(announcement, address, sourcePort)
                }
            )
            disc.selfId = cfg.deviceId
            if (disc.start()) {
                discovery = disc
            } else {
                _status.value = "局域网发现不可用（UDP 端口被占用），可使用手动添加设备"
            }
        }
        p2pConfigSnapshot = P2pConfigSnapshot(
            cfg.p2pEnabled,
            cfg.passphrase,
            accountNamespace(cfg),
            cfg.syncPort,
            cfg.discoveryEnabled
        )
    }

    private fun stopP2P() {
        discovery?.stop()
        discovery = null
        server?.stop()
        server = null
        p2pConfigSnapshot = null
    }

    private fun restartP2P() {
        stopP2P()
        if (running) startP2P()
    }

    /** 设置界面修改 P2P 总开关/口令/账户/端口/发现开关后调用（防抖后按需重启）。 */
    fun onConfigChanged() {
        configChangeJob?.cancel()
        // 主线程 delay + 重启：与 start/stop/startP2P 串行，杜绝双实例
        configChangeJob = mainScope.launch {
            delay(800)
            if (!running) return@launch
            val cfg = SyncSettings.config.value
            val snapshot = P2pConfigSnapshot(
                cfg.p2pEnabled,
                cfg.passphrase,
                accountNamespace(cfg),
                cfg.syncPort,
                cfg.discoveryEnabled
            )
            val previous = p2pConfigSnapshot
            if (snapshot != previous) {
                if (previous != null && snapshot.account != previous.account) {
                    // 账户切换：旧账户的设备条目不可用，清理后按新账户重新发现
                    prunePeersForAccount()
                }
                scope.launch(p2pDispatcher) { if (running) restartP2P() }
            }
        }
    }

    /** 设置界面修改同步间隔后调用：立即按新间隔重排定时器。 */
    fun onIntervalChanged() {
        if (!running) return
        startAutoLoop()
    }

    /** 设备名等连接参数以外信息变化后调用：防抖后重新公告一次。 */
    fun announceSoon() {
        if (!running) return
        announceJob?.cancel()
        // 主线程执行：discovery?.announceNow() 与 stopP2P 串行，避免停机后仍发包
        announceJob = mainScope.launch {
            delay(1_000)
            discovery?.announceNow()
        }
    }

    private fun startAutoLoop() {
        autoJob?.cancel()
        autoJob = scope.launch {
            while (isActive) {
                val interval = SyncSettings.config.value.syncIntervalMin.coerceAtLeast(1)
                delay(interval * 60_000L)
                autoSync()
            }
        }
    }

    private suspend fun autoSync() {
        runIfIdle {
            val cfg = SyncSettings.config.value
            if (cfg.p2pReady && cfg.p2pAutoSync && _peers.value.isNotEmpty()) {
                syncP2PAll()
            }
            if (cfg.serverReady && cfg.serverAutoSync) {
                syncServer()
            }
        }
    }

    /** 本地写入后调用：立即公告，并按需触发一轮自动同步。 */
    fun notifyLocalChange() {
        // 已停机（退后台）后不再公告/同步，避免新任务逃逸 stop() 的取消
        if (!running) return
        // 编排收敛到主线程：与 stop()/start() 天然串行，debounceJob 等字段操作无竞争
        // （调用方可能在 IO 线程，如 changePin）；announceNow 内部转发专用线程，不阻塞主线程
        mainScope.launch {
            discovery?.announceNow()
            debounceJob?.cancel()
            debounceJob = launch {
                delay(LOCAL_CHANGE_DEBOUNCE_MS)
                if (!running) return@launch
                runIfIdle {
                    val cfg = SyncSettings.config.value
                    if (cfg.p2pReady && cfg.p2pAutoSync && _peers.value.isNotEmpty()) {
                        syncP2PAll()
                    }
                    if (cfg.serverReady && cfg.serverAutoSync) {
                        syncServer()
                    }
                }
            }
        }
    }

    private suspend fun runIfIdle(block: suspend () -> Unit) {
        if (!syncMutex.tryLock()) return
        _syncing.value = true
        try {
            block()
        } finally {
            _syncing.value = false
            syncMutex.unlock()
        }
    }

    // ------------------------------------------------------------------ 密钥与公告

    private fun accountNamespace(cfg: SyncConfig): String =
        SyncProtocol.accountNamespace(cfg.serverUrl, cfg.username, cfg.p2pAccount)

    private fun accountFingerprint(cfg: SyncConfig): String =
        SyncProtocol.accountFingerprint(accountNamespace(cfg))

    private fun accountFingerprint(): String = accountFingerprint(SyncSettings.config.value)

    /** 账户切换后清理其他账户的设备条目（本机便签数据不受影响）。 */
    private fun prunePeersForAccount() {
        val fingerprint = accountFingerprint()
        val removed = synchronized(peersLock) {
            val current = _peers.value
            val kept = current.filter { it.account == fingerprint }
            if (kept.size != current.size) {
                _peers.value = kept
                true
            } else {
                false
            }
        }
        if (removed) persistPeers(force = true)
    }

    // PBKDF2 派生可达数百毫秒，用独立细粒度锁，避免阻塞共用对象监视器的 persistPeers（主线程 stop 路径）
    private val keyLock = Any()

    /** synchronized 防止网络线程并发重复执行 PBKDF2 派生（方法内无挂起，安全）。 */
    private fun groupKey(): ByteArray? {
        val cfg = SyncSettings.config.value
        val passphrase = cfg.passphrase
        if (passphrase.isBlank()) return null
        val namespace = accountNamespace(cfg)
        synchronized(keyLock) {
            val cached = cachedKey
            if (cached != null && cached.first == passphrase && cached.second == namespace) {
                return cached.third
            }
            val key = SyncProtocol.deriveGroupKey(passphrase, namespace)
            cachedKey = Triple(passphrase, namespace, key)
            return key
        }
    }

    private fun tag(): String = groupKey()?.let { SyncProtocol.groupTag(it) } ?: ""

    private fun announcement(): P2PDiscovery.Announcement? {
        val cfg = SyncSettings.config.value
        val port = server?.port ?: 0
        if (!cfg.p2pReady || port <= 0) return null
        val digest = try {
            runBlocking { store?.stateDigest() ?: "" }
        } catch (e: Exception) {
            ""
        }
        return P2PDiscovery.Announcement(cfg.deviceId, cfg.deviceName, port, digest)
    }

    // ------------------------------------------------------------------ 设备登记

    private fun upsertPeer(peer: SyncPeer, manual: Boolean? = null) {
        val fingerprint = accountFingerprint()
        synchronized(peersLock) {
            val current = _peers.value
            val existing = current.firstOrNull { it.id == peer.id }
            val merged = if (existing == null) {
                peer.copy(
                    account = fingerprint,
                    lastSeen = peer.lastSeen.takeIf { it > 0 } ?: System.currentTimeMillis()
                )
            } else {
                existing.copy(
                    name = peer.name.ifBlank { existing.name },
                    address = peer.address.ifBlank { existing.address },
                    port = if (peer.port > 0) peer.port else existing.port,
                    manual = manual ?: existing.manual,
                    digest = peer.digest.ifBlank { existing.digest },
                    account = fingerprint,
                    lastSeen = maxOf(existing.lastSeen, peer.lastSeen)
                        .takeIf { it > 0 } ?: System.currentTimeMillis()
                )
            }
            _peers.value = if (existing == null) current + merged else current.map { if (it.id == merged.id) merged else it }
        }
    }

    private fun updatePeer(peerId: String, transform: (SyncPeer) -> SyncPeer) {
        synchronized(peersLock) {
            _peers.value = _peers.value.map { if (it.id == peerId) transform(it) else it }
        }
    }

    private fun updatePeerStatus(peerId: String, status: String) {
        updatePeer(peerId) { it.copy(lastStatus = status) }
        persistPeers()
    }

    // 节流独立锁：与 keyLock（PBKDF2）解耦，主线程 stop→persistPeers 不被密钥派生阻塞
    private val persistLock = Any()

    /** 节流写 SharedPreferences；synchronized 保证并发下 15 秒节流有效且写入完全串行（勿在持有 peersLock 时调用）。 */
    private fun persistPeers(force: Boolean = false) {
        synchronized(persistLock) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPeerPersist < 15_000) return
            lastPeerPersist = now
            SyncSettings.setPeers(_peers.value)
        }
    }

    fun forgetPeer(peerId: String) {
        synchronized(peersLock) {
            _peers.value = _peers.value.filterNot { it.id == peerId }
        }
        persistPeers(force = true)
    }

    /** 把与真实设备 地址+端口 完全一致的手动占位条目并入正式条目。 */
    private fun mergeAddressPlaceholder(deviceId: String, address: String, devicePort: Int) {
        if (address.isBlank() || devicePort <= 0) return
        val placeholder = synchronized(peersLock) {
            val found = _peers.value.firstOrNull {
                it.id.startsWith("manual:") && it.id != deviceId &&
                    it.address == address && it.port == devicePort
            } ?: return
            _peers.value = _peers.value.filterNot { it.id == found.id }
            found
        }
        upsertPeer(placeholder.copy(id = deviceId), manual = placeholder.manual)
    }

    /** 本机地址集合（回环 + 各网卡 IP），用于识别指向自己的对端登记（模拟器/端口转发环回）。 */
    private fun localAddresses(): Set<String> {
        val out = HashSet(listOf("127.0.0.1", "localhost"))
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (nif in interfaces) {
                for (ia in nif.inetAddresses) {
                    ia.hostAddress?.let { out.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** 地址+端口是否指向本机自己的监听端口（NAT 环回场景）。 */
    private fun isSelfRoute(address: String, port: Int): Boolean {
        if (address.isBlank() || port <= 0) return false
        if (port != (server?.port ?: SyncSettings.config.value.syncPort)) return false
        return address in localAddresses()
    }

    private fun onRemoteSync(
        deviceId: String,
        deviceName: String,
        address: String,
        devicePort: Int,
        applied: Int,
        conflicts: Int
    ) {
        if (deviceId.isBlank()) return
        // 自身经 NAT/端口转发环回（模拟器等）：登记只会产生自同步失败
        if (deviceId == SyncSettings.config.value.deviceId) return
        mergeAddressPlaceholder(deviceId, address, devicePort)
        val selfRoute = isSelfRoute(address, devicePort)
        val existing = synchronized(peersLock) { _peers.value.firstOrNull { it.id == deviceId } }
        if (selfRoute && existing == null) return
        // 手动添加的地址是用户显式配置的；「自指路由」也不能覆盖可用地址：
        // 这两种情况只更新名称与活跃时间（port=0 表示保持原端口）
        if (selfRoute || existing?.manual == true) {
            upsertPeer(
                SyncPeer(
                    id = deviceId,
                    name = deviceName,
                    port = 0,
                    lastSeen = System.currentTimeMillis()
                )
            )
        } else {
            upsertPeer(
                SyncPeer(
                    id = deviceId,
                    name = deviceName,
                    address = address,
                    port = devicePort,
                    lastSeen = System.currentTimeMillis()
                )
            )
        }
        if (applied > 0 || conflicts > 0) {
            updatePeer(deviceId) {
                it.copy(
                    lastSyncAt = System.currentTimeMillis(),
                    lastStatus = "对端合并 $applied 条"
                )
            }
        }
        persistPeers()
    }

    private fun onAnnounced(announcement: P2PDiscovery.Announcement, address: String, sourcePort: Int) {
        if (groupKey() == null) return
        upsertPeer(
            SyncPeer(
                id = announcement.deviceId,
                name = announcement.name,
                address = address,
                port = announcement.port,
                digest = announcement.digest,
                lastSeen = System.currentTimeMillis()
            )
        )
        persistPeers()
        if (!SyncSettings.config.value.p2pAutoSync) return
        scope.launch {
            val localDigest = try {
                store?.stateDigest() ?: return@launch
            } catch (e: Exception) {
                return@launch
            }
            if (announcement.digest.isNotBlank() && announcement.digest == localDigest) return@launch
            val now = System.currentTimeMillis()
            val last = lastAttempt[announcement.deviceId] ?: 0L
            if (now - last < SYNC_COOLDOWN_MS) return@launch
            lastAttempt[announcement.deviceId] = now
            runIfIdle {
                val peer = _peers.value.firstOrNull { it.id == announcement.deviceId } ?: return@runIfIdle
                syncPeerInternal(peer)
            }
        }
    }

    // ------------------------------------------------------------------ 同步执行

    private data class PeerSyncOutcome(
        val ok: Boolean,
        val message: String,
        val pushed: Int = 0,
        val pulled: Int = 0,
        val conflicts: Int = 0,
        val upToDate: Boolean = false
    )

    private suspend fun syncPeerInternal(peer: SyncPeer): PeerSyncOutcome {
        val syncStore = store ?: return PeerSyncOutcome(false, "同步未初始化")
        val attachments = attachmentStore ?: return PeerSyncOutcome(false, "同步未初始化")
        val cfg = SyncSettings.config.value
        if (cfg.passphrase.isBlank()) return PeerSyncOutcome(false, "尚未设置同步口令")
        if (peer.address.isBlank() || peer.port <= 0) return PeerSyncOutcome(false, "设备地址缺失")
        if (peer.account != accountFingerprint(cfg)) {
            return PeerSyncOutcome(false, "该设备属于其他同步账户，需重新发现或添加")
        }
        val client = P2PClient(
            store = syncStore,
            attachments = attachments,
            keyProvider = { groupKey() },
            deviceId = cfg.deviceId,
            deviceName = cfg.deviceName,
            listenPort = server?.port ?: cfg.syncPort,
            accountFingerprint = accountFingerprint(cfg)
        )
        return try {
            val result = client.sync(peer.address, peer.port)
            updatePeer(peer.id) {
                it.copy(
                    address = peer.address,
                    port = peer.port,
                    lastSeen = System.currentTimeMillis(),
                    lastSyncAt = System.currentTimeMillis(),
                    digest = result.peerDigest.ifBlank { it.digest },
                    lastStatus = if (result.upToDate) "已是最新" else "同步完成"
                )
            }
            persistPeers()
            if (!result.upToDate) discovery?.announceNow()
            if (result.pulled > 0) _appliedChanges.value += 1
            if (result.imagesPending > 0 && (result.imagesPushed + result.imagesPulled) > 0) {
                // 附件按批传输：仍有待续传且有进展时，短延迟后自动续跑一轮
                val snapshot = peer
                scope.launch {
                    delay(1_500)
                    if (!running) return@launch
                    runIfIdle { syncPeerInternal(snapshot) }
                }
            }
            PeerSyncOutcome(
                ok = true,
                message = result.message,
                pushed = result.pushed,
                pulled = result.pulled,
                conflicts = result.conflicts,
                upToDate = result.upToDate
            )
        } catch (e: P2PAuthException) {
            updatePeerStatus(peer.id, "同步口令不一致，已拒绝")
            PeerSyncOutcome(false, "同步口令不一致，请确认两台设备使用相同的同步口令")
        } catch (e: P2PAccountException) {
            updatePeerStatus(peer.id, "同步账户不一致，已拒绝")
            PeerSyncOutcome(false, e.message ?: "同步账户不一致，已拒绝同步")
        } catch (e: Exception) {
            updatePeerStatus(peer.id, "失败：${e.message}")
            PeerSyncOutcome(false, e.message ?: "同步失败")
        }
    }

    private suspend fun syncP2PAll(): String {
        val peers = _peers.value.filter { it.address.isNotBlank() && it.port > 0 }
        if (peers.isEmpty()) return "暂无可同步的设备"
        var synced = 0
        var failed = 0
        var pushed = 0
        var pulled = 0
        var conflicts = 0
        val errors = mutableListOf<String>()
        for (peer in peers) {
            val outcome = syncPeerInternal(peer)
            if (outcome.ok) {
                synced++
                pushed += outcome.pushed
                pulled += outcome.pulled
                conflicts += outcome.conflicts
            } else {
                failed++
                errors.add("${peer.displayName()}: ${outcome.message}")
            }
        }
        val parts = mutableListOf("已同步 $synced/${peers.size} 台设备")
        if (pushed > 0) parts.add("送出 $pushed 条")
        if (pulled > 0) parts.add("取回 $pulled 条")
        if (conflicts > 0) parts.add("$conflicts 条冲突保留较新版本")
        if (failed > 0) parts.add("$failed 台失败")
        if (errors.isNotEmpty()) parts.add(errors.joinToString("；"))
        val message = parts.joinToString("；")
        _status.value = message
        return message
    }

    private suspend fun syncServer(): String {
        val cfg = SyncSettings.config.value
        if (!cfg.serverReady) return "服务器未登录"
        val result = (serverSyncClient ?: return "同步未初始化").sync(cfg.serverUrl, cfg.token)
        if (result.ok && result.pulled > 0) _appliedChanges.value += 1
        _status.value = result.message
        return result.message
    }

    /** 手动同步：P2P（全部设备）与服务器一起执行。 */
    suspend fun syncNow(): String {
        val cfg = SyncSettings.config.value
        val configured = cfg.p2pReady || cfg.serverReady
        if (!configured) {
            return "尚未配置同步：请在「设置 → 同步」填写 P2P 口令，或登录 FreeNote 服务器"
        }
        if (!syncMutex.tryLock()) return "同步正在进行中…"
        _syncing.value = true
        try {
            val parts = mutableListOf<String>()
            val peers = _peers.value.filter { it.address.isNotBlank() && it.port > 0 }
            if (cfg.p2pReady && peers.isNotEmpty()) {
                parts.add(syncP2PAll())
            } else if (cfg.p2pReady) {
                parts.add("暂无可同步的 P2P 设备")
            }
            if (cfg.serverReady) {
                parts.add(syncServer())
            }
            val message = parts.joinToString("；")
            _status.value = message
            return message
        } finally {
            _syncing.value = false
            syncMutex.unlock()
        }
    }

    /** 单台设备同步（设备列表里的「同步」按钮）。 */
    suspend fun syncPeer(peerId: String): String {
        val peer = _peers.value.firstOrNull { it.id == peerId } ?: return "设备不存在"
        if (!syncMutex.tryLock()) return "同步正在进行中…"
        _syncing.value = true
        try {
            val outcome = syncPeerInternal(peer)
            val message = if (outcome.ok) {
                "与「${peer.displayName()}」${outcome.message}"
            } else {
                "同步失败：${outcome.message}"
            }
            _status.value = message
            return message
        } finally {
            _syncing.value = false
            syncMutex.unlock()
        }
    }

    // ------------------------------------------------------------------ 设备操作

    suspend fun addManualPeer(text: String): Pair<Boolean, String> {
        val parsed = parseAddress(text) ?: return false to "地址格式无效，请使用 主机:端口（例如 192.168.1.8:8765）"
        val (host, port) = parsed
        val cfg = SyncSettings.config.value
        if (host in listOf("127.0.0.1", "localhost", "::1") && port == (server?.port ?: cfg.syncPort)) {
            return false to "不能添加本机作为同步设备"
        }
        val syncStore = store ?: return false to "同步未初始化"
        val attachments = attachmentStore ?: return false to "同步未初始化"
        val fingerprint = accountFingerprint(cfg)
        val client = P2PClient(
            store = syncStore,
            attachments = attachments,
            keyProvider = { groupKey() },
            deviceId = cfg.deviceId,
            deviceName = cfg.deviceName,
            listenPort = server?.port ?: cfg.syncPort,
            accountFingerprint = fingerprint
        )
        return try {
            val payload = client.ping(host, port)
            when {
                payload.optString("app") != "freenote" -> false to "该地址不是 FreeNote 设备"
                payload.optString("account") != fingerprint -> false to
                    "对方设备属于另一个同步账户：请确认两台设备登录了同一服务器账号，或填写相同的离线账户"
                else -> {
                    val peerId = "manual:$host:$port"
                    upsertPeer(SyncPeer(id = peerId, address = host, port = port, manual = true))
                    persistPeers(force = true)
                    true to "已添加设备 $host:$port，点「立即同步」开始同步"
                }
            }
        } catch (e: Exception) {
            false to "无法连接 $host:$port：${e.message}"
        }
    }

    // ------------------------------------------------------------------ 服务器账号

    suspend fun testServer(url: String): Pair<Boolean, String> {
        val client = serverSyncClient ?: return false to "同步未初始化"
        return client.testConnection(url)
    }

    suspend fun register(url: String, username: String, password: String): ServerAuthResult {
        val client = serverSyncClient ?: return ServerAuthResult(false, "同步未初始化")
        val result = client.register(url, username, password)
        if (result.ok) {
            SyncSettings.setServerUrl(ServerUrl.normalize(url))
            SyncSettings.setUsername(result.username.ifBlank { username })
            SyncSettings.setToken(result.token)
            onConfigChanged()  // 服务器账号改变 P2P 同步账户
            triggerServerSync()
        }
        return result
    }

    suspend fun login(url: String, username: String, password: String): ServerAuthResult {
        val client = serverSyncClient ?: return ServerAuthResult(false, "同步未初始化")
        val result = client.login(url, username, password)
        if (result.ok) {
            SyncSettings.setServerUrl(ServerUrl.normalize(url))
            SyncSettings.setUsername(result.username.ifBlank { username })
            SyncSettings.setToken(result.token)
            onConfigChanged()  // 服务器账号改变 P2P 同步账户
            triggerServerSync()
        }
        return result
    }

    suspend fun logout() {
        val cfg = SyncSettings.config.value
        serverSyncClient?.logout(cfg.serverUrl, cfg.token)
        SyncSettings.setToken("")
        onConfigChanged()
    }

    suspend fun changePassword(oldPassword: String, newPassword: String): ServerAuthResult {
        val client = serverSyncClient ?: return ServerAuthResult(false, "同步未初始化")
        val cfg = SyncSettings.config.value
        return client.changePassword(cfg.serverUrl, cfg.token, oldPassword, newPassword)
    }

    suspend fun deleteAccount(password: String): ServerAuthResult {
        val client = serverSyncClient ?: return ServerAuthResult(false, "同步未初始化")
        val cfg = SyncSettings.config.value
        val result = client.deleteAccount(cfg.serverUrl, cfg.token, password)
        if (result.ok) {
            // 注销后清除登录态：服务器账号消失，P2P 回到离线账户分组
            SyncSettings.setToken("")
            onConfigChanged()
        }
        return result
    }

    private fun triggerServerSync() {
        scope.launch {
            runIfIdle { syncServer() }
        }
    }

    private fun parseAddress(text: String): Pair<String, Int>? {
        val value = text.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close < 0) return null
            val host = value.substring(1, close)
            val rest = value.substring(close + 1).removePrefix(":")
            val port = if (rest.isBlank()) DEFAULT_SYNC_PORT else rest.toIntOrNull() ?: return null
            return if (host.isBlank() || port !in 1..65535) null else host to port
        }
        val colon = value.lastIndexOf(':')
        if (colon > 0) {
            val port = value.substring(colon + 1).toIntOrNull()
            if (port != null) {
                val host = value.substring(0, colon)
                return if (host.isBlank() || host.contains(":") || port !in 1..65535) null else host to port
            }
        }
        return if (value.contains(":")) null else value to DEFAULT_SYNC_PORT
    }
}
