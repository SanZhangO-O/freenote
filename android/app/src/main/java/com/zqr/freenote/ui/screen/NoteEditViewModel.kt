package com.zqr.freenote.ui.screen

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.zqr.freenote.data.AttachmentStore
import com.zqr.freenote.data.Note
import com.zqr.freenote.data.NoteColor
import com.zqr.freenote.data.NoteHistory
import com.zqr.freenote.data.SettingsManager
import com.zqr.freenote.data.imageTokenForLocalPath
import com.zqr.freenote.data.imageTokenLocalPath
import com.zqr.freenote.data.imageTokenPath
import com.zqr.freenote.data.normalizeFolder
import com.zqr.freenote.data.normalizeRecurrence
import com.zqr.freenote.data.parseImageTokens
import com.zqr.freenote.data.validAttachmentName
import com.zqr.freenote.ui.common.PinValidation
import com.zqr.freenote.ui.components.EditorBlock
import com.zqr.freenote.ui.components.EditorImageBlock
import com.zqr.freenote.ui.components.EditorTextBlock
import com.zqr.freenote.ui.components.insertImageBlocks
import com.zqr.freenote.ui.components.markdownToChecklist
import com.zqr.freenote.ui.components.markdownToChecklistLosesMarkup
import com.zqr.freenote.ui.components.mergeBackwardAtStart
import com.zqr.freenote.ui.components.moveBlockInList
import com.zqr.freenote.ui.components.newEditorBlockId
import com.zqr.freenote.ui.components.removeImageAndMerge
import com.zqr.freenote.viewmodel.NoteViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.UUID

/**
 * 便签编辑器的状态持有者（ViewModel 而非 remember），按导航 backStackEntry 作用域：
 * 配置变更（旋转屏幕）或编辑页在返回栈中保留期间，未保存的编辑内容不丢失。
 */
class NoteEditViewModel(
    application: Application,
    private val noteId: String?,
    private val viewModel: NoteViewModel
) : AndroidViewModel(application) {

    var title by mutableStateOf("")
        private set
    var content by mutableStateOf("")
        private set
    var selectedColor by mutableLongStateOf(NoteColor.DEFAULT_COLORS[0])
        private set
    var loadedNote by mutableStateOf<Note?>(null)
        private set
    var isPinned by mutableStateOf(false)
        private set
    var isLocked by mutableStateOf(false)
        private set
    var isChecklist by mutableStateOf(false)
        private set
    var reminderTime by mutableStateOf<Long?>(null)
        private set
    var previewMode by mutableStateOf(false)
        private set
    val tags = mutableStateListOf<String>()

    /** 文件夹路径（空串 = 未分类；协议 v5 随同步传播）。 */
    var folder by mutableStateOf("")
        private set

    /** 重复提醒规则（""|daily|weekly|monthly）。 */
    var recurrence by mutableStateOf("")
        private set

    /** 选好提醒时间后弹出的重复规则选择对话框。 */
    var showRecurrenceDialog by mutableStateOf(false)
        private set

    /**
     * 文本模式的编辑块：正文文字块与内联图片块交错。
     * 每次块编辑都立即序列化回 [content]，保证清单/预览/保存读到最新正文。
     */
    val blocks = mutableStateListOf<EditorBlock>()

    /** 最近获得焦点的文字块 id：插入图片时在该块光标处切分插入。 */
    private var focusedBlockId: String? = null

    /**
     * 插入图片后应聚焦以继续输入的文字块 id；界面聚焦并唤起输入法后
     * 调用 [consumePendingFocus] 清除，避免后续重组重复抢焦点。
     */
    var pendingFocusBlockId by mutableStateOf<String?>(null)
        private set

    /** 本次会话新增的图片文件路径，保存成功后与正文比对做孤儿清理。 */
    private val addedImagePaths = mutableSetOf<String>()

    /** 上次落库时正文引用的图片路径；再次保存时不再被引用的文件会被删除。 */
    private var referencedImagePaths: Set<String> = emptySet()

    var menuExpanded by mutableStateOf(false)
        private set
    var showLockPinDialog by mutableStateOf(false)
        private set
    var pinInput by mutableStateOf("")
        private set
    var pinError by mutableStateOf(false)
        private set

    /** 会话 PIN 过期后保存时要求重新验证 PIN 的对话框。 */
    var showReauthDialog by mutableStateOf(false)
        private set
    var reauthPinInput by mutableStateOf("")
        private set
    var reauthPinError by mutableStateOf(false)
        private set

    /** 锁定便签的解锁会话已过期：自动保存暂停，界面常驻提示「验证并保存」。 */
    var sessionExpired by mutableStateOf(false)
        private set

    /** 切换为清单前的确认：会不可逆地剥离 Markdown 标记并立即自动保存。 */
    var showChecklistConvertDialog by mutableStateOf(false)
        private set

    /**
     * 保存完成后的导航回调，由界面通过 [attachSavedCallback] 挂载/卸载。
     * 不在 VM 中持有组合期闭包：旋转重建后导航回调会失效（旧 NavController）。
     */
    private var savedCallback: (() -> Unit)? = null

    fun attachSavedCallback(callback: (() -> Unit)?) {
        savedCallback = callback
    }

    /** 解除锁定的确认对话框（防止误触顶栏按钮导致明文保存）。 */
    var showUnlockConfirmDialog by mutableStateOf(false)
        private set

    /** 加载时便签处于锁定状态且当前没有会话 PIN：禁止编辑，防止密文被当作正文保存。 */
    var needsUnlock by mutableStateOf(false)
        private set

    /**
     * 解密失败（PIN 已更换或便签来自使用其他 PIN 的设备）：禁止编辑与保存，
     * 否则编辑器会把密文当成正文，保存时二次加密导致原文永久不可恢复。
     */
    var decryptFailed by mutableStateOf(false)
        private set

    /** 丢弃未保存更改的确认对话框。 */
    var showDiscardDialog by mutableStateOf(false)
        private set

    /**
     * 新建便签只设置了标签/颜色/置顶等元数据、没有正文时按返回的确认对话框：
     * 直接静默丢弃会让用户以为这些设置已随便签保存。
     */
    var showEmptyDiscardDialog by mutableStateOf(false)
        private set

    private var snapshot: NoteSnapshot? = null

    /** 待执行的自动保存任务；每次编辑重置计时，保存入口会先取消避免重复写库。 */
    private var autoSaveJob: Job? = null

    /** 自动保存失败只提示一次（避免每 1.5 秒弹一次 Toast），成功保存后复位。 */
    private var autoSaveErrorShown = false

    /** 保存互斥锁：串行化各保存入口的「取最新状态-构建-落库」，保证最后落库的是最新状态。 */
    private val saveMutex = Mutex()

    private data class NoteSnapshot(
        val title: String,
        val content: String,
        val color: Long,
        val isPinned: Boolean,
        val isChecklist: Boolean,
        val reminderTime: Long?,
        val tags: List<String>,
        val isLocked: Boolean,
        val folder: String,
        val recurrence: String
    )

    init {
        loadNote()
    }

    /** Loads the note identified by [noteId] into the editing state. */
    fun loadNote() {
        viewModelScope.launch {
            if (noteId == null) {
                // 新建便签没有可加载的正文，但仍需建好空的正文文字块，
                // 否则编辑区块列表为空，正文输入框根本不渲染
                decryptFailed = false
                // 列表页在文件夹筛选下新建：便签默认归属该文件夹（与 Windows 端一致）
                folder = normalizeFolder(viewModel.selectedFolder.value ?: "")
                rebuildBlocks("")
                return@launch
            }
            val note = withContext(Dispatchers.IO) { viewModel.getNoteById(noteId) }
            if (note == null) {
                if (!needsUnlock) {
                    Toast.makeText(getApplication(), "便签不存在或已删除", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            loadedNote = note
            isLocked = note.isLocked
            decryptFailed = false
            val pin = viewModel.getUnlockPin()
            if (note.isLocked && pin == null) {
                needsUnlock = true
                return@launch
            }
            // PBKDF2 解密耗时（低端机可达数百毫秒），放到 Default 线程避免打开时界面卡顿
            val resolved = if (note.isLocked && pin != null) {
                val decrypted = withContext(Dispatchers.Default) { viewModel.decryptNote(note, pin) }
                if (decrypted == null) {
                    // 解密失败：绝不让密文进入编辑器（否则保存会二次加密毁掉原文）
                    decryptFailed = true
                    return@launch
                }
                decrypted
            } else note
            needsUnlock = false
            title = resolved.title
            content = resolved.content
            selectedColor = resolved.color
            isPinned = resolved.isPinned
            isChecklist = resolved.isChecklist
            reminderTime = resolved.reminderTime
            folder = resolved.folder
            recurrence = resolved.recurrence
            tags.clear(); tags.addAll(resolved.tagList())
            rebuildBlocks(resolved.content)
            referencedImagePaths = parseImageTokens(content)
                .mapNotNull { imageTokenLocalPath(getApplication<Application>(), it) }
                .toSet()
            addedImagePaths.clear()
            snapshot = snapshotOf(resolved)
        }
    }

    /** 以已保存的 [note] 为基准生成快照，保证 isDirty 与落库内容一致。 */
    private fun snapshotOf(note: Note) = NoteSnapshot(
        title = note.title,
        content = note.content,
        color = note.color,
        isPinned = note.isPinned,
        isChecklist = note.isChecklist,
        reminderTime = note.reminderTime,
        tags = note.tagList(),
        isLocked = note.isLocked,
        folder = note.folder,
        recurrence = note.recurrence
    )

    /** 是否有未保存的更改（用于自动保存触发与返回判断）。 */
    fun isDirty(): Boolean {
        if (needsUnlock || decryptFailed) return false
        if (loadedNote == null) {
            // 新建便签：任何实际内容或设置的变更都视为有更改
            return title.isNotEmpty() || content.isNotEmpty() || tags.isNotEmpty() ||
                isPinned || isChecklist || reminderTime != null ||
                selectedColor != NoteColor.DEFAULT_COLORS[0]
        }
        val snap = snapshot ?: return false
        return snap.title != title.trim() || snap.content != content.trim() ||
            snap.color != selectedColor || snap.isPinned != isPinned ||
            snap.isChecklist != isChecklist || snap.reminderTime != reminderTime ||
            snap.tags != tags.toList() || snap.isLocked != isLocked ||
            snap.folder != folder || snap.recurrence != recurrence
    }

    fun onTitleChange(value: String) {
        title = value
        scheduleAutoSave()
    }

    fun onContentChange(value: String) {
        content = value
        scheduleAutoSave()
    }

    fun onColorSelected(value: Long) {
        selectedColor = value
        scheduleAutoSave()
    }

    /** 文件夹选择/重命名后更新归属；空串表示未分类。 */
    fun onFolderSelected(value: String) {
        folder = normalizeFolder(value)
        scheduleAutoSave()
    }

    /** 文件夹选择器数据源（全部路径，含本机新建的空文件夹）。 */
    val availableFolders get() = viewModel.folderPaths

    fun createFolder(path: String) = viewModel.createFolder(path)

    fun renameFolder(old: String, new: String) = viewModel.renameFolder(old, new)

    fun deleteFolder(path: String) = viewModel.deleteFolder(path)

    fun togglePin() {
        isPinned = !isPinned
        scheduleAutoSave()
    }

    fun toggleChecklist() {
        // 文本转清单会剥离 Markdown 标记且随自动保存不可撤销：有可丢失标记时先确认
        if (!isChecklist && markdownToChecklistLosesMarkup(content)) {
            showChecklistConvertDialog = true
            return
        }
        applyChecklistToggle()
    }

    fun confirmChecklistConvert() {
        showChecklistConvertDialog = false
        applyChecklistToggle()
    }

    fun dismissChecklistConvert() {
        showChecklistConvertDialog = false
    }

    private fun applyChecklistToggle() {
        val wasChecklist = isChecklist
        isChecklist = !isChecklist
        if (wasChecklist) {
            // 从清单切回文本时，按最新正文重建编辑块（清单模式下块不会被更新）
            rebuildBlocks(content)
        } else {
            // 文本切清单：先剥离 Markdown 标记，避免清单项里残留 "- " / "#" / "**"
            content = markdownToChecklist(content)
        }
        scheduleAutoSave()
    }

    fun togglePreview() { previewMode = !previewMode }
    fun onMenuExpandedChange(value: Boolean) { menuExpanded = value }

    fun clearReminder() {
        reminderTime = null
        // 与 Windows 端一致：清除提醒时同步清掉重复规则，避免残留到下次设置提醒
        recurrence = ""
        scheduleAutoSave()
    }

    /** 重复规则选择对话框：选好时间后询问是否重复（取消保持原规则）。 */
    fun applyRecurrence(value: String) {
        recurrence = normalizeRecurrence(value)
        showRecurrenceDialog = false
        scheduleAutoSave()
    }

    fun dismissRecurrenceDialog() {
        showRecurrenceDialog = false
    }

    // ------------------------------------------------------------------ 模板

    /** 系统分享（ACTION_SEND）填入：正文为空时直接采用，否则追加到末尾。 */
    fun applySharedText(text: String, subject: String?) {
        val body = text.trim()
        if (body.isEmpty()) return
        content = if (content.isBlank()) body else content.trimEnd() + "\n\n" + body
        rebuildBlocks(content)
        if (title.isBlank() && !subject.isNullOrBlank()) title = subject.trim().take(200)
        scheduleAutoSave()
        Toast.makeText(getApplication(), "已填入分享内容", Toast.LENGTH_SHORT).show()
    }

    /** 模板选择对话框。 */
    var showTemplateDialog by mutableStateOf(false)
        private set

    fun openTemplateDialog() {
        if (isChecklist) {
            Toast.makeText(getApplication(), "清单模式下无法插入 Markdown 模板", Toast.LENGTH_SHORT).show()
            return
        }
        showTemplateDialog = true
    }

    fun dismissTemplateDialog() {
        showTemplateDialog = false
    }

    /** 插入模板：正文为空时直接替换，否则追加到末尾。 */
    fun insertTemplate(template: String) {
        val current = content
        content = if (current.isBlank()) template else current.trimEnd() + "\n\n" + template
        rebuildBlocks(content)
        showTemplateDialog = false
        scheduleAutoSave()
    }

    /** [[双向链接]]：委托主 ViewModel 按标题查找并回调打开目标便签。 */
    fun openWikiLink(title: String, onOpen: (String) -> Unit) {
        viewModel.openWikiLink(title, onOpen)
    }

    // ------------------------------------------------------------------ 历史版本

    /** 历史版本列表（新→旧）与对话框开关。 */
    val historyVersions = mutableStateListOf<NoteHistory>()
    var showHistoryDialog by mutableStateOf(false)
        private set

    /** 打开历史版本对话框（未保存的新便签没有历史）。 */
    fun openHistory() {
        val noteId = loadedNote?.id
        if (noteId == null) {
            Toast.makeText(getApplication(), "新便签保存后才有历史版本", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            val versions = withContext(Dispatchers.IO) { viewModel.historyFor(noteId) }
            historyVersions.clear()
            historyVersions.addAll(versions)
            showHistoryDialog = true
        }
    }

    fun dismissHistoryDialog() {
        showHistoryDialog = false
    }

    /** 恢复历史版本为当前内容（恢复前的状态也会存入历史）。 */
    fun restoreVersion(historyId: Long) {
        val noteId = loadedNote?.id ?: return
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                viewModel.restoreHistory(noteId, historyId)
            }
            if (restored == null) {
                Toast.makeText(getApplication(), "恢复失败，请重试", Toast.LENGTH_SHORT).show()
                return@launch
            }
            applyRestoredNote(restored)
            showHistoryDialog = false
            Toast.makeText(getApplication(), "已恢复历史版本", Toast.LENGTH_SHORT).show()
        }
    }

    /** 把恢复后的便签同步到编辑器状态（加密便签用会话 PIN 解密后展示）。 */
    private suspend fun applyRestoredNote(note: Note) {
        var resolved = note
        if (note.isLocked) {
            val pin = viewModel.getUnlockPin()
            if (pin == null) {
                // 解锁会话已过期：与 loadNote 一致，绝不让密文进入编辑器（否则保存会二次加密）
                needsUnlock = true
                Toast.makeText(
                    getApplication(), "已恢复历史版本，请重新解锁查看", Toast.LENGTH_SHORT
                ).show()
                return
            }
            val decrypted = withContext(Dispatchers.Default) { viewModel.decryptNote(note, pin) }
            if (decrypted == null) {
                decryptFailed = true
                Toast.makeText(
                    getApplication(), "解密失败，无法预览恢复结果", Toast.LENGTH_SHORT
                ).show()
                return
            }
            resolved = decrypted
        }
        isLocked = note.isLocked
        title = resolved.title
        content = resolved.content
        selectedColor = resolved.color
        isPinned = resolved.isPinned
        isChecklist = resolved.isChecklist
        reminderTime = resolved.reminderTime
        folder = resolved.folder
        recurrence = resolved.recurrence
        tags.clear(); tags.addAll(resolved.tagList())
        rebuildBlocks(resolved.content)
        referencedImagePaths = parseImageTokens(content)
            .mapNotNull { imageTokenLocalPath(getApplication<Application>(), it) }
            .toSet()
        loadedNote = note
        snapshot = snapshotOf(resolved)
    }

    // ------------------------------------------------------------------ 编辑块

    private fun rebuildBlocks(fromContent: String) {
        blocks.clear()
        val textLines = mutableListOf<String>()
        fun flushText() {
            if (textLines.isEmpty()) return
            val text = textLines.joinToString("\n")
            textLines.clear()
            if (text.isNotBlank()) {
                blocks.add(EditorTextBlock(newEditorBlockId(), TextFieldValue(text)))
            }
        }
        fromContent.split("\n").forEach { line ->
            val path = imageTokenPath(line)
            if (path != null) {
                flushText()
                val resolved = imageTokenLocalPath(getApplication<Application>(), path) ?: path
                blocks.add(EditorImageBlock(newEditorBlockId(), resolved))
            } else {
                textLines.add(line)
            }
        }
        flushText()
        if (blocks.isEmpty()) blocks.add(EditorTextBlock(newEditorBlockId(), TextFieldValue("")))
    }

    /** 把编辑块序列化回纯文本正文（图片块转成独占一行的标记）。 */
    private fun serializeBlocks(): String =
        blocks.mapNotNull { block ->
            when (block) {
                is EditorTextBlock -> block.value.text.takeIf { it.isNotBlank() }
                is EditorImageBlock -> imageTokenForLocalPath(block.path)
            }
        }.joinToString("\n")

    private fun syncContentFromBlocks() {
        content = serializeBlocks()
        scheduleAutoSave()
    }

    /** 文字块获得焦点时记录位置，供插入图片时定位。 */
    fun onBlockFocused(id: String) {
        focusedBlockId = id
    }

    /** 界面完成待聚焦块的焦点请求后回调，防止重复聚焦。 */
    fun consumePendingFocus() {
        pendingFocusBlockId = null
    }

    fun updateTextBlock(id: String, value: TextFieldValue) {
        val index = blocks.indexOfFirst { it.id == id }
        if (index < 0) return
        val existing = blocks[index]
        // copy 保留 splitFromPrevious：标记记录的是「与上方图片之间没有换行」，与编辑内容无关
        blocks[index] = if (existing is EditorTextBlock) existing.copy(value = value) else EditorTextBlock(id, value)
        syncContentFromBlocks()
    }

    /**
     * 插入图片：拷贝到内部存储后，在焦点文字块的光标处切分并插入图片块，
     * 光标后的文字移到图片之后，聚焦该文字块让用户立即继续输入。
     */
    fun insertImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val paths = uris.mapNotNull { copyImageToInternal(getApplication(), it) }
            if (paths.isEmpty()) {
                Toast.makeText(getApplication(), "图片插入失败，请重试或换一张图片", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (paths.size < uris.size) {
                Toast.makeText(
                    getApplication(),
                    "有 ${uris.size - paths.size} 张图片插入失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
            val (newBlocks, focusTarget) = insertImageBlocks(blocks.toList(), focusedBlockId, paths)
            blocks.clear()
            blocks.addAll(newBlocks)
            addedImagePaths.addAll(paths)
            pendingFocusBlockId = focusTarget
            syncContentFromBlocks()
        }
    }

    /** 拖拽排序：把 [id] 的块移动到 [toIndex]；拖拽中不落库，结束时统一由 [commitBlockOrder] 同步。 */
    fun moveBlock(id: String, toIndex: Int) {
        val moved = moveBlockInList(blocks.toList(), id, toIndex) ?: return
        blocks.clear()
        blocks.addAll(moved)
    }

    /** 拖拽结束后把新块顺序序列化回正文。 */
    fun commitBlockOrder() {
        syncContentFromBlocks()
    }

    /**
     * 移除图片块：插入图片时正文在光标处被切开，删除图片后把两侧文字块拼回，
     * 残留的换行随图片一起消失；文件由保存时的引用比对统一清理。
     */
    fun removeImageBlock(id: String) {
        val (newBlocks, _) = removeImageAndMerge(blocks.toList(), id)
        blocks.clear()
        blocks.addAll(newBlocks)
        syncContentFromBlocks()
    }

    /**
     * 文字块开头按退格（硬件键盘会派发按键事件；多数软键盘在块首不派发，
     * 触屏用户用图片 ✕ 删除）：与前一个文字块合并，或删除前一个图片块，
     * 返回是否产生了块级修改。合并后焦点转移到拼接块（原块已从列表移除）。
     */
    fun backspaceAtBlockStart(id: String): Boolean {
        val outcome = mergeBackwardAtStart(blocks.toList(), id) ?: return false
        val (newBlocks, focusTarget) = outcome
        blocks.clear()
        blocks.addAll(newBlocks)
        focusTarget?.let { pendingFocusBlockId = it }
        syncContentFromBlocks()
        return true
    }

    /**
     * 选择提醒时间。Android 13+ 未授予通知权限时先触发 [onPermissionRequired]
     * 回调，由界面发起权限请求后再调用一次。系统对话框需要 Activity 主题，
     * 因此 [context] 由界面（LocalContext）传入。
     */
    fun pickReminder(context: Context, onPermissionRequired: () -> Unit = {}) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onPermissionRequired()
            return
        }
        // 默认给 5 分钟后的整点分钟：连续确认默认值不会立刻落在「已过期」上
        showReminderPickers(context, defaultReminderCalendar())
    }

    private fun showReminderPickers(context: Context, initial: Calendar) {
        val cal = initial.clone() as Calendar
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    cal.set(year, month, day, hour, minute, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    if (cal.timeInMillis <= System.currentTimeMillis()) {
                        Toast.makeText(getApplication(), "提醒时间已过期，请重新选择", Toast.LENGTH_SHORT).show()
                        // 直接重开选择器并以当前时间为基准，用户不必自己再点一次菜单
                        showReminderPickers(context, defaultReminderCalendar())
                    } else {
                        reminderTime = cal.timeInMillis
                        scheduleAutoSave()
                        // 选好时间后询问重复规则（不重复/每天/每周/每月）
                        showRecurrenceDialog = true
                    }
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(context)
            ).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun defaultReminderCalendar(): Calendar = Calendar.getInstance().apply {
        add(Calendar.MINUTE, 5)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /** 顶栏锁定按钮入口：锁定走 PIN 对话框；解锁先弹确认，避免误触。 */
    fun requestUnlock() {
        if (isLocked) {
            showUnlockConfirmDialog = true
        } else {
            requestLock()
        }
    }

    fun confirmUnlock() {
        isLocked = false
        showUnlockConfirmDialog = false
        scheduleAutoSave()
    }

    fun dismissUnlockConfirm() {
        showUnlockConfirmDialog = false
    }

    private fun requestLock() {
        if (!SettingsManager.hasPin()) {
            Toast.makeText(getApplication(), "请先在设置中设置 PIN", Toast.LENGTH_SHORT).show()
            return
        }
        // 锁定始终要求输入 PIN 并验证，避免静默复用上一次的会话 PIN
        pinInput = ""
        pinError = false
        showLockPinDialog = true
    }

    fun onPinInputChange(value: String) {
        if (PinValidation.isAcceptable(value)) {
            pinInput = value
            pinError = false
        }
    }

    fun confirmLockPin() {
        val candidate = pinInput
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { SettingsManager.verifyPin(candidate) }
            if (!isActive) return@launch
            if (ok) {
                viewModel.setUnlockPin(candidate)
                isLocked = true
                showLockPinDialog = false
                pinInput = ""
                pinError = false
                scheduleAutoSave()
            } else {
                pinError = true
            }
        }
    }

    fun dismissLockPinDialog() {
        showLockPinDialog = false
        pinInput = ""
        pinError = false
    }

    /**
     * 返回前不再询问，直接自动保存（有更改时）；仅当锁定便签会话 PIN 已过期、
     * 无法加密保存时，才弹确认对话框让用户选择放弃或留下手动保存。
     */
    fun requestBack(onNavigateBack: () -> Unit) {
        // 未解锁 / 解密失败：没有可保存的编辑内容，直接返回
        if (decryptFailed || needsUnlock) {
            back(onNavigateBack)
            return
        }
        if (!isDirty()) {
            back(onNavigateBack)
            return
        }
        // 只有标签/颜色/置顶等元数据、没有正文：这些设置无法随空便签落库，
        // 静默丢弃会让用户以为已保存；先确认再离开
        if (loadedNote == null && title.isBlank() && content.isBlank()) {
            showEmptyDiscardDialog = true
            return
        }
        if (isLocked && viewModel.getUnlockPin() == null) {
            sessionExpired = true
            showDiscardDialog = true
            return
        }
        performSave()
    }

    fun confirmDiscard(onNavigateBack: () -> Unit) {
        showDiscardDialog = false
        back(onNavigateBack)
    }

    fun cancelDiscard() {
        showDiscardDialog = false
    }

    /** 无正文便签的返回确认：确认后丢弃元数据并离开。 */
    fun confirmEmptyDiscard(onNavigateBack: () -> Unit) {
        showEmptyDiscardDialog = false
        back(onNavigateBack)
    }

    fun cancelEmptyDiscard() {
        showEmptyDiscardDialog = false
    }

    /**
     * 手动保存（顶栏 ✓ 或返回触发）：锁定便签需要有效的会话 PIN（用于重加密）。
     * 会话 PIN 可能已过期，此时先弹出重新验证对话框，验证通过后继续原保存流程。
     */
    fun save() {
        if (decryptFailed) {
            Toast.makeText(getApplication(), "便签无法解密，已阻止保存以免覆盖数据", Toast.LENGTH_SHORT).show()
            return
        }
        if (needsUnlock) {
            Toast.makeText(getApplication(), "请先解锁便签", Toast.LENGTH_SHORT).show()
            return
        }
        // 与 requestBack 一致用编辑器当前 isLocked：解锁后（loadedNote 尚未更新）不应再要求重验证
        if (isLocked && viewModel.getUnlockPin() == null) {
            sessionExpired = true
            reauthPinInput = ""
            reauthPinError = false
            showReauthDialog = true
            return
        }
        performSave()
    }

    fun onReauthPinInputChange(value: String) {
        if (PinValidation.isAcceptable(value)) {
            reauthPinInput = value
            reauthPinError = false
        }
    }

    fun confirmReauth() {
        val candidate = reauthPinInput
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { SettingsManager.verifyPin(candidate) }
            if (!isActive) return@launch
            if (!ok) {
                reauthPinError = true
                return@launch
            }
            viewModel.setUnlockPin(candidate)
            reauthPinInput = ""
            showReauthDialog = false
            sessionExpired = false
            performSave()
        }
    }

    fun dismissReauth() {
        showReauthDialog = false
        reauthPinInput = ""
        reauthPinError = false
    }

    /** 手动保存：保存成功后清除会话 PIN 并经 savedCallback 导航返回。 */
    private fun performSave() {
        if (decryptFailed) return
        autoSaveJob?.cancel()
        expireStaleReminder(announce = true)
        val sessionPin = viewModel.getUnlockPin()
        val (note, _) = buildNote(sessionPin)
        // 新建且完全为空：提示并停留在编辑页，不产生空记录（快速路径，实际保存以锁内最新状态为准）
        if (loadedNote == null &&
            note.title.isEmpty() && note.content.isEmpty()
        ) {
            Toast.makeText(getApplication(), "内容为空，未保存", Toast.LENGTH_SHORT).show()
            return
        }
        // 协程不赋给 autoSaveJob：scheduleAutoSave 的重新计时不应取消已确认的手动保存，
        // 与自动保存的并发写由 saveMutex 串行化，锁内取最新状态与实时会话 PIN 后落库
        viewModelScope.launch {
            var needsReauth = false
            var emptyContent = false
            val saved = saveMutex.withLock {
                val latestPin = viewModel.getUnlockPin()
                if (isLocked && latestPin == null) {
                    // 锁外守卫取 PIN 后、锁内构建前的窗口里会话可能恰好过期：
                    // 无法重加密，放弃本次保存并要求重新验证，绝不把锁定便签明文落库
                    needsReauth = true
                    false
                } else {
                    val (latest, pinForLock) = buildNote(latestPin)
                    if (loadedNote == null &&
                        latest.title.isEmpty() && latest.content.isEmpty()
                    ) {
                        // 锁内复查：等待锁期间内容可能已被清空，不落库空记录
                        emptyContent = true
                        false
                    } else {
                        doSave(latest, pinForLock)
                    }
                }
            }
            when {
                needsReauth -> {
                    reauthPinInput = ""
                    reauthPinError = false
                    showReauthDialog = true
                }
                emptyContent -> Toast.makeText(getApplication(), "内容为空，未保存", Toast.LENGTH_SHORT).show()
                // 挂起等待 DB 写入完成后再导航，避免快速重开读到旧数据
                saved -> {
                    Toast.makeText(getApplication(), "已保存", Toast.LENGTH_SHORT).show()
                    sessionExpired = false
                    viewModel.clearUnlockPin()
                    // 回调由界面挂载，旋转重建后仍指向当前 NavController
                    savedCallback?.invoke()
                }
                else -> Toast.makeText(getApplication(), "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 编辑停顿 [AUTO_SAVE_DELAY_MS] 后自动保存一次（期间有新输入则重新计时）。
     * 仅落库，不导航、不清会话 PIN、无成功提示。
     */
    fun scheduleAutoSave() {
        if (needsUnlock || decryptFailed) return
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(AUTO_SAVE_DELAY_MS)
            autoSave()
        }
    }

    /** 界面退到后台（ON_STOP）时立即落库，防止进程被杀丢失未保存内容。 */
    fun saveOnStop() {
        autoSaveJob?.cancel()
        viewModelScope.launch { autoSave() }
    }

    private suspend fun autoSave() {
        saveMutex.withLock {
            // 锁内取最新状态判断与构建，保证与其他保存入口并发时最后落库的是最新内容
            if (needsUnlock || decryptFailed || !isDirty()) return
            val sessionPin = viewModel.getUnlockPin()
            // 会话 PIN 已过期无法加密保存：暂停自动保存并在界面常驻提示，
            // 避免用户以为一直在保存、直到进程被杀丢失这一段编辑
            if (isLocked && sessionPin == null) {
                sessionExpired = true
                return
            }
            // 过期提醒被移除要可见：静默清除会让用户以为提醒还在（手动保存/自动保存一致）
            expireStaleReminder(announce = true)
            val (note, pinForLock) = buildNote(sessionPin)
            // 新建且完全为空：不落库，避免随手进入就产生空记录
            if (loadedNote == null &&
                note.title.isEmpty() && note.content.isEmpty()
            ) {
                return
            }
            if (!doSave(note, pinForLock)) {
                if (!autoSaveErrorShown) {
                    autoSaveErrorShown = true
                    Toast.makeText(
                        getApplication(),
                        "自动保存失败，请检查存储空间；后续保存会自动重试",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                autoSaveErrorShown = false
            }
        }
    }

    private fun expireStaleReminder(announce: Boolean) {
        val reminder = reminderTime
        if (reminder != null && reminder <= System.currentTimeMillis()) {
            if (announce) {
                Toast.makeText(getApplication(), "提醒时间已过期，已移除提醒", Toast.LENGTH_SHORT).show()
            }
            reminderTime = null
        }
    }

    /** 由当前编辑状态构建 Note；一次性取定会话 PIN，避免保存途中过期的竞态。 */
    private fun buildNote(sessionPin: String?): Pair<Note, String?> {
        val effectiveLocked = isLocked && sessionPin != null
        val note = Note(
            id = loadedNote?.id ?: UUID.randomUUID().toString(),
            title = title.trim(),
            content = content.trim(),
            color = selectedColor,
            createdAt = loadedNote?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            isPinned = isPinned,
            isDeleted = false,
            tags = tags.joinToString(","),
            reminderTime = reminderTime,
            isLocked = effectiveLocked,
            isChecklist = isChecklist,
            folder = folder,
            recurrence = normalizeRecurrence(recurrence)
        )
        return note to if (effectiveLocked) sessionPin else null
    }

    /** 写库并在成功后同步快照、复用 id、清理正文不再引用的图片文件。 */
    private suspend fun doSave(note: Note, pinForLock: String?): Boolean {
        val saved = try {
            viewModel.saveNoteCommit(note, pinForLock)
            // 提交已在 NonCancellable 内原子完成，立即记录真实 id 与快照，
            // 防止新建便签因 loadedNote 仍为 null 而在下次保存生成新 id 重复插入
            loadedNote = note
            snapshot = snapshotOf(note)
            true
        } catch (e: CancellationException) {
            // 协程在提交前被取消（新输入重新计时/切后台/退出页面）不是保存失败：
            // 未落库内容仍标记为脏，随后一次自动保存或手动保存会重试
            throw e
        } catch (e: Exception) {
            false
        }
        if (saved) {
            // 小部件与同步为非关键副作用：异步执行，保持原保存 upsert→提醒→小部件→同步的先后效果
            viewModelScope.launch {
                runCatching { viewModel.saveNoteSideEffects() }
            }
            cleanupOrphanImages(
                parseImageTokens(note.content)
                    .mapNotNull { imageTokenLocalPath(getApplication<Application>(), it) }
                    .toSet()
            )
        }
        return saved
    }

    /**
     * 保存成功后清理图片文件：上次落库引用、或本次会话新增、但当前正文
     * 已不再引用的路径会被删除（用户删除了图片块或手动删了标记行）。
     */
    private suspend fun cleanupOrphanImages(current: Set<String>) = withContext(Dispatchers.IO) {
        // 只删应用自己的图片目录：标记行可能来自同步内容，不校验目录会把任意路径当孤儿图片删掉
        val imagesDir = File(getApplication<Application>().filesDir, "note_images").canonicalFile
        val orphans = (referencedImagePaths - current) + (addedImagePaths - current)
        val portableNames = mutableSetOf<String>()
        orphans.forEach { path ->
            val name = File(path).name
            if (validAttachmentName(name)) {
                // 内容寻址的附件可能被其他便签引用，交给全局引用检查再删
                portableNames.add(name)
            } else {
                runCatching {
                    val file = File(path).canonicalFile
                    if (file.parentFile?.canonicalFile == imagesDir) file.delete()
                }
            }
        }
        if (portableNames.isNotEmpty()) {
            viewModel.deleteUnusedAttachments(portableNames)
        }
        referencedImagePaths = current
        addedImagePaths.clear()
        addedImagePaths.addAll(current.intersect(addedImagePaths))
    }

    /** Clears the unlock session PIN and signals the caller to navigate back. */
    fun back(onNavigateBack: () -> Unit) {
        viewModel.clearUnlockPin()
        onNavigateBack()
    }

    companion object {
        private const val AUTO_SAVE_DELAY_MS = 1_500L

        fun factory(noteId: String?, appViewModel: NoteViewModel): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                    NoteEditViewModel(app, noteId, appViewModel)
                }
            }
    }
}

/** 图片标记中存的是便携附件名或 file:// URI；删除文件、本地加载时解析成本地绝对路径。 */

private suspend fun copyImageToInternal(
    context: Context,
    uri: Uri
): String? = withContext(Dispatchers.IO) {
    return@withContext try {
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        val ext = when (mime.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
        val dir = File(context.filesDir, "note_images").apply { mkdirs() }
        // 先落下临时文件再按内容命名：内容寻址（协议 v6），图片可跨设备同步
        val temp = File.createTempFile("img", ".tmp", dir)
        try {
            input.use { src ->
                temp.outputStream().use { out -> src.copyTo(out) }
            }
            if (temp.length() <= 0 || temp.length() > AttachmentStore.MAX_ATTACHMENT_BYTES) {
                temp.delete()
                return@withContext null
            }
            val name = AttachmentStore.nameForFile(temp, ext)
            val dest = File(dir, name)
            if (dest.isFile) {
                temp.delete()
            } else if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
            dest.absolutePath
        } catch (e: Exception) {
            temp.delete()
            null
        }
    } catch (e: Exception) {
        null
    }
}
