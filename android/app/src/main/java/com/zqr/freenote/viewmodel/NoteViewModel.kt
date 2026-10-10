package com.zqr.freenote.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.glance.appwidget.updateAll
import com.zqr.freenote.data.AppDatabase
import com.zqr.freenote.data.BackupManager
import com.zqr.freenote.data.BackupService
import com.zqr.freenote.data.CryptoUtils
import com.zqr.freenote.data.AttachmentStore
import com.zqr.freenote.data.Note
import com.zqr.freenote.data.NoteHistory
import com.zqr.freenote.data.NoteRepository
import com.zqr.freenote.data.SettingsManager
import com.zqr.freenote.data.attachmentNameFromPath
import com.zqr.freenote.data.folderAncestors
import com.zqr.freenote.data.folderInSubtree
import com.zqr.freenote.data.folderParent
import com.zqr.freenote.data.folderSortKey
import com.zqr.freenote.data.localImagePath
import com.zqr.freenote.data.normalizeFolder
import com.zqr.freenote.data.sync.SyncManager
import com.zqr.freenote.data.sync.SyncStore
import com.zqr.freenote.notification.ReminderService
import com.zqr.freenote.util.SqlLike
import com.zqr.freenote.widget.NoteWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class SortMode { UPDATED, CREATED, TITLE }

/** 修改 PIN 的结果：旧 PIN 错误 / 完成（含重加密失败计数）。 */
sealed interface ChangePinResult {
    data object WrongPin : ChangePinResult
    data class Done(val reencrypted: Int, val failed: Int) : ChangePinResult
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class NoteViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext

    // 会话解锁 PIN：仅内存保存，带超时，超时后需重新输入 PIN（见 getUnlockPin）。
    // pin 与过期时间封装为同一不可变对象并 @Volatile，保证跨线程读到一致配对
    private data class UnlockSession(val pin: String, val expiresAt: Long)

    @Volatile
    private var unlockSession: UnlockSession? = null

    private val database = AppDatabase.getDatabase(application)
    private val repository: NoteRepository = NoteRepository(
        database.noteDao(),
        SyncStore(database)
    )

    private val reminderService = ReminderService(appContext, repository)
    private val backupService = BackupService(appContext)

    /** PIN 互斥锁：串行化保存提交与改 PIN 重加密，避免并发保存仍用旧 PIN 加密覆盖新 PIN 数据。 */
    private val pinMutex = Mutex()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** 界面偏好（排序/标签筛选）持久化，进程被杀后不重置。 */
    private val uiPrefs = appContext.getSharedPreferences("freenote_ui", android.content.Context.MODE_PRIVATE)

    private val _sortMode = MutableStateFlow(
        uiPrefs.getString(KEY_SORT_MODE, null)
            ?.let { name -> SortMode.entries.firstOrNull { it.name == name } }
            ?: SortMode.UPDATED
    )
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    /** 排序方向：true = 降序（时间新→旧 / 标题 Z→A）。 */
    private val _sortDescending = MutableStateFlow(
        uiPrefs.getBoolean(KEY_SORT_DESC, true)
    )
    val sortDescending: StateFlow<Boolean> = _sortDescending.asStateFlow()

    /** 时间线视图：按修改日期分组展示（今天/昨天/…）。 */
    private val _timelineMode = MutableStateFlow(uiPrefs.getBoolean(KEY_TIMELINE, false))
    val timelineMode: StateFlow<Boolean> = _timelineMode.asStateFlow()

    private val _selectedTag = MutableStateFlow(uiPrefs.getString(KEY_SELECTED_TAG, null))
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    /** 文件夹筛选：null = 全部，"" = 未分类，其余为路径（含子树）。 */
    private val _selectedFolder = MutableStateFlow(uiPrefs.getString(KEY_SELECTED_FOLDER, null))
    val selectedFolder: StateFlow<String?> = _selectedFolder.asStateFlow()

    /** 本机新建的空文件夹（仅本地占位，不参与同步；与 Windows folders.local 语义一致）。 */
    private val _localFolders = MutableStateFlow(loadLocalFolders())

    /** 全部文件夹路径（便签派生 + 本机空文件夹，含隐式父级），目录序排序。 */
    val folderPaths: StateFlow<List<String>> = combine(repository.allFolders, _localFolders) { derived, local ->
        val paths = LinkedHashSet<String>()
        (derived + local).forEach { path -> paths.addAll(folderAncestors(path)) }
        paths.sortedBy(::folderSortKey)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(value: String) {
        _searchQuery.value = value
    }

    fun clearSearch() {
        _searchQuery.value = ""
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        uiPrefs.edit().putString(KEY_SORT_MODE, mode.name).apply()
        // 切换排序字段时恢复该字段的常用方向：时间新→旧，标题 A→Z
        setSortDescending(mode != SortMode.TITLE)
    }

    fun setSortDescending(descending: Boolean) {
        _sortDescending.value = descending
        uiPrefs.edit().putBoolean(KEY_SORT_DESC, descending).apply()
    }

    /** 切换时间线视图；开启时同步切到「按修改时间」排序，日期分组才有意义。 */
    fun setTimelineMode(enabled: Boolean) {
        _timelineMode.value = enabled
        uiPrefs.edit().putBoolean(KEY_TIMELINE, enabled).apply()
        if (enabled) setSortMode(SortMode.UPDATED)
    }

    fun setSelectedTag(tag: String?) {
        _selectedTag.value = tag
        uiPrefs.edit().putString(KEY_SELECTED_TAG, tag).apply()
    }

    fun toggleTag(tag: String) {
        setSelectedTag(if (_selectedTag.value == tag) null else tag)
    }

    fun setSelectedFolder(folder: String?) {
        val clean = folder?.let(::normalizeFolder)
        _selectedFolder.value = clean
        uiPrefs.edit().putString(KEY_SELECTED_FOLDER, clean).apply()
    }

    // ------------------------------------------------------------------ 文件夹管理

    private fun loadLocalFolders(): List<String> {
        val raw = uiPrefs.getString(KEY_LOCAL_FOLDERS, null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                normalizeFolder(array.optString(index)).takeIf { it.isNotEmpty() }
            }.distinct()
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    private fun saveLocalFolders(folders: List<String>) {
        val clean = folders.map(::normalizeFolder).filter { it.isNotEmpty() }.distinct()
        _localFolders.value = clean
        uiPrefs.edit().putString(KEY_LOCAL_FOLDERS, org.json.JSONArray(clean).toString()).apply()
    }

    /** 新建（空）文件夹占位；写入首条便签后由便签自身携带路径参与同步。 */
    fun createFolder(path: String) {
        val folder = normalizeFolder(path)
        if (folder.isEmpty()) return
        if (folder !in _localFolders.value) {
            saveLocalFolders(_localFolders.value + folder)
        }
    }

    fun renameFolder(old: String, new: String, onResult: (Int) -> Unit = {}) = viewModelScope.launch {
        val from = normalizeFolder(old)
        val to = normalizeFolder(new)
        if (from.isEmpty() || to.isEmpty() || from == to) {
            onResult(0)
            return@launch
        }
        val count = withContext(Dispatchers.IO) { repository.renameFolder(from, to) }
        val rewrite: (String) -> String = { path -> to + path.substring(from.length) }
        saveLocalFolders(
            _localFolders.value.map { folder ->
                if (folderInSubtree(folder, from)) rewrite(folder) else folder
            }
        )
        followSelectedFolder(from, rewrite)
        if (count > 0) {
            refreshWidget()
            SyncManager.notifyLocalChange()
        }
        onResult(count)
    }

    fun deleteFolder(path: String, onResult: (Int) -> Unit = {}) = viewModelScope.launch {
        val folder = normalizeFolder(path)
        if (folder.isEmpty()) {
            onResult(0)
            return@launch
        }
        val count = withContext(Dispatchers.IO) { repository.deleteFolder(folder) }
        // 与 NoteRepository.deleteFolder 同一改写语义：上移到父级（不丢父路径）
        val parent = folderParent(folder)
        val rewrite: (String) -> String = { existing ->
            val suffix = existing.substring(folder.length)
            if (parent.isEmpty()) suffix.trimStart('/') else parent + suffix
        }
        saveLocalFolders(
            _localFolders.value.mapNotNull { existing ->
                if (folderInSubtree(existing, folder)) {
                    rewrite(existing).takeIf { it.isNotEmpty() }
                } else {
                    existing
                }
            }
        )
        followSelectedFolder(folder, rewrite)
        if (count > 0) {
            refreshWidget()
            SyncManager.notifyLocalChange()
        }
        onResult(count)
    }

    /** 当前文件夹筛选位于被改写的子树内时跟随更新（列表页与编辑页共用同一入口）。 */
    private fun followSelectedFolder(from: String, rewrite: (String) -> String) {
        val current = _selectedFolder.value ?: return
        if (folderInSubtree(current, from)) setSelectedFolder(rewrite(current))
    }

    /** 锁定便签搜索的内存解密缓存：noteId → (updatedAt, 解密后的标题+正文小写)。 */
    private val lockedSearchCache = HashMap<String, Pair<Long, String>>()

    /** 锁定便签的解锁态搜索；PIN 会话过期后不参与搜索（密文内容不泄漏）。 */
    private suspend fun lockedSearchMatches(query: String): List<Note> {
        val pin = getUnlockPin() ?: return emptyList()
        val needle = query.lowercase()
        val matches = mutableListOf<Note>()
        repository.getLockedNotes().forEach { note ->
            val cached = lockedSearchCache[note.id]
            val haystack = if (cached != null && cached.first == note.updatedAt) {
                cached.second
            } else {
                val decrypted = decryptNote(note, pin) ?: return@forEach
                val text = (decrypted.title + "\n" + decrypted.content).lowercase()
                lockedSearchCache[note.id] = note.updatedAt to text
                text
            }
            if (needle in haystack) matches.add(note)
        }
        return matches
    }

    private val baseList = combine(
        _searchQuery,
        _selectedTag,
        _selectedFolder
    ) { query, tag, folder ->
        FilterParams(query.trim(), tag, folder)
    }
        // 仅对搜索输入防抖；首次发射/清空搜索/切换标签立即生效，
        // 否则冷启动和回到列表时会先闪 300ms 的「暂无便签」空态
        .debounce { params -> if (params.query.isEmpty()) 0L else 300L }
        .flatMapLatest { params ->
            when {
                params.query.isNotEmpty() -> repository.searchNotes(SqlLike.escape(params.query)).map { list ->
                    // 锁定便签解锁态搜索：会话 PIN 存在时内存解密匹配（并按 updatedAt 缓存）
                    val seen = list.mapTo(HashSet()) { it.id }
                    val merged = list + lockedSearchMatches(params.query).filter { it.id !in seen }
                    merged.filter { params.tag == null || it.tagList().contains(params.tag) }
                        .filter { params.folder == null || folderInSubtree(it.folder, params.folder) }
                }
                params.folder != null -> repository.notesInFolder(params.folder).map { list ->
                    if (params.tag != null) list.filter { it.tagList().contains(params.tag) } else list
                }
                params.tag != null -> repository.notesByTag(params.tag)
                else -> repository.activeNotes
            }
        }

    val visibleNotes: StateFlow<List<Note>> =
        combine(baseList, _sortMode, _sortDescending) { list, sort, descending ->
            sortList(list, sort, descending)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val trashedNotes: StateFlow<List<Note>> = repository.trashedNotes
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allTags: StateFlow<List<String>> = repository.allTags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private data class FilterParams(val query: String, val tag: String?, val folder: String?)

    init {
        SettingsManager.init(application)
        SyncManager.init(appContext)
        viewModelScope.launch {
            // 回收站保留期：30 天，到期自动永久清除（连带清理图片文件）
            val expired = withContext(Dispatchers.IO) {
                repository.purgeOldTrash(System.currentTimeMillis() - TRASH_RETENTION_MS)
            }
            expired.forEach { deleteNoteImages(it) }
            if (expired.isNotEmpty()) {
                SyncManager.notifyLocalChange()
                // 回收站到期是静默永久删除：明确告知用户，否则便签「消失」且无从解释
                android.widget.Toast.makeText(
                    appContext,
                    "已自动清理 ${expired.size} 条超过 30 天的回收站便签",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            reminderService.rescheduleAll()
            // 自动备份检查：启用且距上次超过 24 小时时后台执行一次
            maybeAutoBackup()
        }
    }

    private fun sortList(list: List<Note>, sort: SortMode, descending: Boolean): List<Note> {
        val comparator: Comparator<Note> = when (sort) {
            SortMode.CREATED ->
                if (descending) compareByDescending { it.createdAt } else compareBy { it.createdAt }
            SortMode.TITLE ->
                if (descending) compareByDescending { it.title.lowercase() } else compareBy { it.title.lowercase() }
            SortMode.UPDATED ->
                if (descending) compareByDescending { it.updatedAt } else compareBy { it.updatedAt }
        }
        return list.sortedWith(
            compareByDescending<Note> { it.isPinned }.then(comparator)
        )
    }

    suspend fun getNoteById(id: String): Note? = repository.getNoteById(id)

    /** 指定便签的历史版本列表（本机历史，不跨设备同步）。 */
    suspend fun historyFor(noteId: String): List<NoteHistory> = repository.historyFor(noteId)

    /** 恢复历史版本；返回恢复后的便签（失败返回 null）。 */
    suspend fun restoreHistory(noteId: String, historyId: Long): Note? =
        repository.restoreHistory(noteId, historyId)

    /** [[双向链接]]：按标题找到便签后回调打开；未找到时提示。 */
    fun openWikiLink(title: String, onOpen: (String) -> Unit) = viewModelScope.launch {
        val id = withContext(Dispatchers.IO) { repository.findIdByTitle(title) }
        if (id == null) {
            android.widget.Toast.makeText(
                appContext,
                "未找到标题为「$title」的便签",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else {
            onOpen(id)
        }
    }

    /** 挂起式保存：DB 写入完成后才返回，避免界面先导航导致读写竞争。 */
    suspend fun saveNote(note: Note, pinForLock: String? = null) {
        saveNoteCommit(note, pinForLock)
        saveNoteSideEffects()
    }

    /**
     * 保存提交临界区：加密、落库（含 outbox 入队）、提醒同步在 NonCancellable 内完成，
     * 并经 [pinMutex] 与 [changePin] 的重加密互斥。
     * 提交完成后即使调用方协程被取消也不再抛出，保证调用方能记录提交结果，
     * 避免新建便签因状态未同步而重复插入；未进入提交即被取消时照常抛 CancellationException。
     * 锁定便签在临界区内校验会话 PIN 仍然有效：等待锁期间会话可能因改 PIN 被清空，
     * 用陈旧 PIN 加密会造成新 PIN 无法解密（校验失败抛出，由调用方按保存失败处理）。
     */
    suspend fun saveNoteCommit(note: Note, pinForLock: String? = null) {
        var committed = false
        try {
            withContext(NonCancellable) {
                pinMutex.withLock {
                    val toSave = if (note.isLocked && pinForLock != null) {
                        val session = unlockSession
                        if (session == null || session.pin != pinForLock) {
                            throw IllegalStateException("会话 PIN 已变更，保存已取消")
                        }
                        // PBKDF2 派生较重，移出主线程；NonCancellable 随上下文保留
                        withContext(Dispatchers.Default) {
                            note.copy(
                                title = CryptoUtils.encrypt(note.title, pinForLock),
                                content = CryptoUtils.encrypt(note.content, pinForLock)
                            )
                        }
                    } else {
                        note
                    }
                    repository.upsert(toSave.copy(updatedAt = System.currentTimeMillis()))
                    // 行已落库：提醒调度失败不应升级为「保存失败」（闹钟由 rescheduleAll 兜底）
                    committed = true
                    runCatching { reminderService.sync(toSave) }
                }
            }
        } catch (e: CancellationException) {
            if (!committed) throw e
        }
    }

    /** 保存提交后的非关键副作用：刷新桌面小组件并通知同步。 */
    suspend fun saveNoteSideEffects() {
        refreshWidget()
        SyncManager.notifyLocalChange()
    }

    /** 解密锁定便签；返回 null 表示解密失败（PIN 不匹配或数据损坏）。 */
    fun decryptNote(note: Note, pin: String): Note? {
        if (!note.isLocked) return note
        val decTitle = CryptoUtils.decrypt(note.title, pin) ?: return null
        val decContent = CryptoUtils.decrypt(note.content, pin) ?: return null
        return note.copy(title = decTitle, content = decContent)
    }

    fun togglePinned(note: Note) = viewModelScope.launch {
        repository.setPinned(note.id, !note.isPinned)
        refreshWidget()
        SyncManager.notifyLocalChange()
    }

    fun moveToTrash(note: Note) = viewModelScope.launch {
        // DB 先行再撤闹钟：进程若在两步之间死亡，宁可闹钟多响一次，也不能留下有提醒时间却无闹钟的记录
        if (note.reminderTime != null) {
            repository.setReminder(note.id, null)
        }
        repository.moveToTrash(note.id)
        if (note.reminderTime != null) reminderService.cancel(note.id)
        refreshWidget()
        SyncManager.notifyLocalChange()
    }

    fun restore(note: Note) = viewModelScope.launch {
        repository.restoreFromTrash(note.id)
        reminderService.sync(note)
        refreshWidget()
        SyncManager.notifyLocalChange()
    }

    fun deletePermanently(note: Note) = viewModelScope.launch {
        if (note.reminderTime != null) reminderService.cancel(note.id)
        repository.deleteById(note.id)
        deleteNoteImages(note)
        refreshWidget()
        SyncManager.notifyLocalChange()
    }

    fun emptyTrash() = viewModelScope.launch {
        // 现查而非使用可能陈旧的缓存值，保证清空数量与实际一致
        val trashed = withContext(Dispatchers.IO) { repository.getTrashedNotesNow() }
        withContext(Dispatchers.IO) { repository.deleteByIds(trashed.map { it.id }) }
        trashed.forEach { deleteNoteImages(it) }
        refreshWidget()
        SyncManager.notifyLocalChange()
    }

    // ------------------------------------------------------------------ 批量操作

    /** 批量移到回收站（跳过已在回收站的）。 */
    fun moveToTrashBatch(notes: List<Note>) = viewModelScope.launch {
        val targets = notes.filter { !it.isDeleted }
        if (targets.isEmpty()) return@launch
        withContext(Dispatchers.IO) {
            targets.forEach { note ->
                if (note.reminderTime != null) repository.setReminder(note.id, null)
                repository.moveToTrash(note.id)
            }
        }
        targets.forEach { note -> if (note.reminderTime != null) reminderService.cancel(note.id) }
        refreshWidget()
        SyncManager.notifyLocalChange()
    }

    /** 批量移动到文件夹（含新建路径；folder 为空串表示「未分类」）。 */
    fun moveToFolderBatch(notes: List<Note>, folder: String) = viewModelScope.launch {
        val target = normalizeFolder(folder)
        val changed = notes.filter { it.folder != target }
        if (changed.isEmpty()) return@launch
        withContext(Dispatchers.IO) {
            changed.forEach { note ->
                repository.update(note.copy(folder = target, updatedAt = System.currentTimeMillis()))
            }
        }
        refreshWidget()
        SyncManager.notifyLocalChange()
    }

    /** 批量添加标签（自动去重，保留既有顺序）。 */
    fun addTagsBatch(notes: List<Note>, tags: List<String>) = viewModelScope.launch {
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return@launch
        val changed = notes.mapNotNull { note ->
            val existing = note.tagList()
            val merged = existing + clean.filter { it !in existing }
            if (merged == existing) {
                null
            } else {
                note.copy(tags = merged.joinToString(","), updatedAt = System.currentTimeMillis())
            }
        }
        if (changed.isEmpty()) return@launch
        withContext(Dispatchers.IO) { changed.forEach { repository.update(it) } }
        refreshWidget()
        SyncManager.notifyLocalChange()
    }

    /** 同步应用了远端改动后调用：重排提醒并刷新桌面小组件。 */
    fun onRemoteSyncApplied() = viewModelScope.launch {
        reminderService.rescheduleAll()
        refreshWidget()
    }

    fun setUnlockPin(pin: String) {
        unlockSession = UnlockSession(pin, System.currentTimeMillis() + UNLOCK_SESSION_MS)
    }

    /** 返回未过期的会话 PIN；过期即清除并返回 null（下次访问需重新解锁）。 */
    fun getUnlockPin(): String? {
        val session = unlockSession ?: return null
        if (System.currentTimeMillis() > session.expiresAt) {
            unlockSession = null
            return null
        }
        return session.pin
    }

    fun clearUnlockPin() {
        unlockSession = null
    }

    suspend fun lockedCount(): Int = repository.countLockedNotes()

    /**
     * 修改 PIN：验证旧 PIN 后用新 PIN 重新加密全部锁定便签，最后更新哈希。
     * 解密失败（例如便签来自其他设备的备份）的便签保持原样并计数。
     */
    suspend fun changePin(oldPin: String, newPin: String): ChangePinResult =
        withContext(Dispatchers.IO) {
            // 重加密必须整体完成：若中途被取消（如用户离开设置页），
            // 会留下「前 k 条已用新 PIN、哈希仍是旧 PIN」的永久不可解密状态，
            // 故包 NonCancellable 让其跑完（结果丢弃无妨，数据一致性优先）
            withContext(NonCancellable) {
                // verifyPin 也在锁内：并发触发的第二次 changePin 会在第一次 setPin 后
                // 因旧 PIN 校验失败而终止，不会用错误基准重加密或覆盖哈希
                pinMutex.withLock {
                    if (!SettingsManager.verifyPin(oldPin)) return@withLock ChangePinResult.WrongPin
                    val locked = repository.getLockedNotes()
                    var ok = 0
                    var failed = 0
                    locked.forEach { note ->
                        val title = CryptoUtils.decrypt(note.title, oldPin)
                        val content = CryptoUtils.decrypt(note.content, oldPin)
                        if (title != null && content != null) {
                            repository.update(
                                note.copy(
                                    title = CryptoUtils.encrypt(title, newPin),
                                    content = CryptoUtils.encrypt(content, newPin)
                                )
                            )
                            ok++
                        } else {
                            failed++
                        }
                    }
                    SettingsManager.setPin(newPin)
                    clearUnlockPin()
                    if (ok > 0) SyncManager.notifyLocalChange()
                    ChangePinResult.Done(ok, failed)
                }
            }
        }

    fun exportAll(uri: Uri, onResult: (BackupManager.BackupResult) -> Unit) =
        viewModelScope.launch {
            val notes = withContext(Dispatchers.IO) { repository.getAllActiveNotes() }
            val result = withContext(Dispatchers.IO) { backupService.export(notes, uri) }
            onResult(result)
        }

    /** 导入 .md/.txt 文件（每个文件一条便签）。 */
    fun importTextFiles(uris: List<Uri>, onResult: (BackupManager.BackupResult) -> Unit) =
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { backupService.importTextFiles(uris) }
            if (result.success) {
                refreshWidget()
                SyncManager.notifyLocalChange()
            }
            onResult(result)
        }

    // ------------------------------------------------------------------ 自动备份

    /** 立即执行一次自动备份（设置页「立即备份」）。 */
    fun autoBackupNow(onResult: (BackupManager.BackupResult) -> Unit) = viewModelScope.launch {
        val tree = SettingsManager.autoBackupTree()
        if (tree.isBlank()) {
            onResult(BackupManager.BackupResult(false, "请先选择自动备份文件夹"))
            return@launch
        }
        val result = withContext(Dispatchers.IO) { backupService.autoBackup(Uri.parse(tree)) }
        onResult(result)
    }

    /** 应用启动时检查：启用且距上次超过 24 小时则后台自动备份一次。 */
    private fun maybeAutoBackup() {
        if (!SettingsManager.autoBackupEnabled()) return
        val tree = SettingsManager.autoBackupTree()
        if (tree.isBlank()) return
        val intervalMs = 24L * 60 * 60 * 1000
        if (System.currentTimeMillis() - SettingsManager.lastAutoBackupAt() < intervalMs) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { backupService.autoBackup(Uri.parse(tree)) }.getOrNull()
            }
            if (result != null && !result.success) {
                android.widget.Toast.makeText(
                    appContext, result.message, android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun import(uri: Uri, onResult: (BackupManager.BackupResult) -> Unit) =
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { backupService.import(uri) }
            if (result.success) {
                reminderService.rescheduleAll()
                refreshWidget()
                SyncManager.notifyLocalChange()
            }
            onResult(result)
        }

    private suspend fun refreshWidget() {
        runCatching { NoteWidget().updateAll(appContext) }
    }

    /** 删除本机不再被任何便签引用的附件（内容寻址文件可能被多条便签共用）。 */
    suspend fun deleteUnusedAttachments(names: Set<String>) = withContext(Dispatchers.IO) {
        if (names.isEmpty()) return@withContext
        runCatching {
            AttachmentStore(appContext, AppDatabase.getDatabase(appContext)).deleteUnused(names)
        }
    }

    private suspend fun deleteNoteImages(note: Note) = withContext(Dispatchers.IO) {
        val imagesDir = File(appContext.filesDir, "note_images").canonicalFile
        val legacyPaths = mutableListOf<File>()
        val names = mutableSetOf<String>()
        note.contentImageList().forEach { tokenPath ->
            val name = attachmentNameFromPath(tokenPath)
            if (name != null) {
                names.add(name)
            } else {
                legacyPaths.add(File(localImagePath(tokenPath)))
            }
        }
        legacyPaths.forEach { file ->
            runCatching {
                val canonical = file.canonicalFile
                // 只删除应用自己的图片目录，避免误删其他便签仍在引用的文件
                if (canonical.parentFile?.canonicalFile == imagesDir) canonical.delete()
            }
        }
        if (names.isNotEmpty()) {
            // 内容寻址的图片可能被其他便签共用：仅在没有任何便签引用时才删除
            runCatching {
                AttachmentStore(appContext, AppDatabase.getDatabase(appContext)).deleteUnused(names)
            }
        }
    }

    companion object {
        private const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val UNLOCK_SESSION_MS = 5L * 60 * 1000
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_SORT_DESC = "sort_desc"
        private const val KEY_TIMELINE = "timeline_mode"
        private const val KEY_SELECTED_TAG = "selected_tag"
        private const val KEY_SELECTED_FOLDER = "selected_folder"
        private const val KEY_LOCAL_FOLDERS = "local_folders"
    }
}
