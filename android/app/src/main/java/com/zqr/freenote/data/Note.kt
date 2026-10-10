package com.zqr.freenote.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.File
import java.util.UUID

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val color: Long = NoteColor.DEFAULT_COLORS[0],
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val tags: String = "",
    val reminderTime: Long? = null,
    val isLocked: Boolean = false,
    val isChecklist: Boolean = false,
    /** 文件夹路径（`工作/项目A`，空串 = 未分类；协议 v5 随同步传播）。 */
    @ColumnInfo(defaultValue = "''") val folder: String = "",
    /** 重复提醒规则（""|daily|weekly|monthly；协议 v6 随同步传播）。 */
    @ColumnInfo(defaultValue = "''") val recurrence: String = ""
) {
    fun tagList(): List<String> =
        if (tags.isBlank()) emptyList()
        else tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** 正文中的内联图片路径（`![图片](路径)` 独占一行）。 */
    fun contentImageList(): List<String> = parseImageTokens(content)
}

// ---------------------------------------------------------------------- 重复提醒

/** 重复提醒选项（值, 显示名），与 Windows 端 RECURRENCE_CHOICES 一致。 */
val RECURRENCE_CHOICES: List<Pair<String, String>> = listOf(
    "" to "不重复",
    "daily" to "每天",
    "weekly" to "每周",
    "monthly" to "每月"
)

/** 规范化重复规则：非法值一律回落为不重复。 */
fun normalizeRecurrence(value: String?): String {
    val text = (value ?: "").trim().lowercase()
    return if (text in RECURRENCE_CHOICES.map { it.first }) text else ""
}

/** 从重复规则显示名（如「每天」）取规则值；不匹配返回空串。 */
fun recurrenceLabel(value: String): String =
    RECURRENCE_CHOICES.firstOrNull { it.first == normalizeRecurrence(value) }?.second ?: "不重复"

/**
 * 按重复规则计算下一次提醒时间（本地时区）；规则为空返回 null。
 * 每月重复时日期超出目标月天数会收拢到当月最后一天（1/31 → 2/28）。
 */
fun nextRecurrenceTime(baseMs: Long, recurrence: String): Long? {
    val rule = normalizeRecurrence(recurrence)
    if (rule.isEmpty()) return null
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = baseMs }
    when (rule) {
        "daily" -> calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
        "weekly" -> calendar.add(java.util.Calendar.WEEK_OF_YEAR, 1)
        "monthly" -> {
            val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
            calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
            calendar.add(java.util.Calendar.MONTH, 1)
            val maxDay = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            calendar.set(java.util.Calendar.DAY_OF_MONTH, minOf(day, maxDay))
        }
    }
    return calendar.timeInMillis
}

// ---------------------------------------------------------------------- 模板

/** 便签模板（与 Windows 端 NOTE_TEMPLATES 保持一致）。 */
val NOTE_TEMPLATES: List<Pair<String, String>> = listOf(
    "会议记录" to "# 会议记录\n\n- 时间：\n- 参与人：\n\n## 议题\n\n1. \n\n## 结论\n\n- ",
    "任务清单" to "- [ ] 任务一\n- [ ] 任务二\n- [ ] 任务三",
    "日记" to "# 日记\n\n## 今天发生了什么\n\n\n## 感受\n\n",
    "每周总结" to "# 周总结\n\n## 本周完成\n\n- \n\n## 下周计划\n\n- "
)

// ---------------------------------------------------------------------- 文件夹

const val FOLDER_MAX_LENGTH = 200

/** 规范化文件夹路径：反斜杠转正斜杠、逐段去空白、丢弃空段并截断总长（与 Windows 端一致）。 */
fun normalizeFolder(value: String): String {
    val joined = value.replace('\\', '/')
        .split('/')
        .map { it.trim(::isFolderWhitespace) }
        .filter { it.isNotEmpty() }
        .joinToString("/")
    return truncateCodePoints(joined, FOLDER_MAX_LENGTH)
}

/** 与 Python str.strip() 一致的空白判定（Java 的 isWhitespace 不含 NBSP/图幅空格等不换行空白）。 */
private fun isFolderWhitespace(ch: Char): Boolean =
    ch.isWhitespace() || ch == '\u0085' || ch == '\u00A0' || ch == '\u2007' || ch == '\u202F'

/** 按 Unicode 码点截断：Kotlin 的 take() 按 UTF-16 单元计数，会与 Python 端（按码点切片）不一致。 */
private fun truncateCodePoints(value: String, max: Int): String {
    if (value.length <= max) return value
    var count = 0
    var index = 0
    while (index < value.length && count < max) {
        index += Character.charCount(value.codePointAt(index))
        count++
    }
    return value.substring(0, index)
}

/** 父路径；顶层文件夹或未分类返回空串。 */
fun folderParent(folder: String): String =
    if (folder.isEmpty() || '/' !in folder) "" else folder.substringBeforeLast('/')

/** 从顶层到自身的全部路径前缀（不含空串）：`a/b` → `[a, a/b]`。 */
fun folderAncestors(folder: String): List<String> {
    val result = mutableListOf<String>()
    var current = ""
    for (part in folder.split('/').filter { it.isNotEmpty() }) {
        current = if (current.isEmpty()) part else "$current/$part"
        result.add(current)
    }
    return result
}

/** candidate 是否等于 root 或位于其子树下；root 为空串时表示未分类，仅精确匹配。 */
fun folderInSubtree(candidate: String, root: String): Boolean =
    if (root.isEmpty()) candidate.isEmpty()
    else candidate == root || candidate.startsWith("$root/")

/** 目录序排序键：同级按名称不区分大小写，子级紧跟父级（与 Windows folder_sort_key 等价）。 */
fun folderSortKey(folder: String): String =
    folder.split('/').filter { it.isNotEmpty() }.joinToString("\u0000") { it.lowercase() }

/** 独占一行的内联图片标记：`![任意alt](本地路径)` */
private val IMAGE_LINE_REGEX = Regex("""^!\[[^\]]*]\(([^)]+)\)\s*$""")

fun isImageTokenLine(line: String): Boolean = IMAGE_LINE_REGEX.matches(line)

/** 提取一行图片标记中的路径；不是图片行时返回 null。 */
fun imageTokenPath(line: String): String? =
    IMAGE_LINE_REGEX.matchEntire(line.trim())?.groupValues?.get(1)

/** 生成内联图片标记行（图片必须独占一行才能在预览中渲染）。 */
fun imageToken(path: String): String = "![](file://$path)"

/** 图片标记中的 file:// URI 转回本地绝对路径（应用生成的路径不含转义字符）。 */
fun localImagePath(tokenPath: String): String = tokenPath.removePrefix("file://")

// ---------------------------------------------------------------- 便携附件（协议 v6）
// 附件名 = sha256(文件字节) 前 16 位十六进制 + "." + 小写扩展名（≤8 位字母数字）。
// 正文标记：`![](freenote-img:<name>)`——与设备无关，由各端解析到本地图片目录。

const val ATTACHMENT_SCHEME = "freenote-img:"
private val ATTACHMENT_NAME_REGEX = Regex("^[0-9a-f]{16}\\.[a-z0-9]{1,8}$")

fun validAttachmentName(name: String): Boolean = ATTACHMENT_NAME_REGEX.matches(name)

/** 生成便携附件标记行（图片必须独占一行才能在预览中渲染）。 */
fun attachmentToken(name: String): String = "![]($ATTACHMENT_SCHEME$name)"

/** 从图片标记路径提取便携附件名；非便携形式（file:// 等）返回 null。 */
fun attachmentNameFromPath(tokenPath: String): String? {
    if (!tokenPath.startsWith(ATTACHMENT_SCHEME)) return null
    val name = tokenPath.removePrefix(ATTACHMENT_SCHEME)
    return name.takeIf(::validAttachmentName)
}

/** 正文中引用的全部便携附件名（按出现顺序，可能重复）。 */
fun contentAttachmentNames(content: String): List<String> =
    content.split("\n").mapNotNull { line -> imageTokenPath(line)?.let(::attachmentNameFromPath) }

/**
 * 图片块（本地绝对路径）序列化回正文标记：
 * 内容寻址命名的文件用便携标记（可跨设备同步），其余保持 legacy `file://` 标记。
 */
fun imageTokenForLocalPath(path: String): String {
    val name = File(path).name
    return if (validAttachmentName(name)) attachmentToken(name) else imageToken(path)
}

/**
 * 图片标记路径 → 本地绝对路径（便携名解析到应用图片目录，缺失时仍返回目标路径，
 * 供孤儿清理比对）；无法识别返回 null。
 */
fun imageTokenLocalPath(context: Context, tokenPath: String): String? {
    val name = attachmentNameFromPath(tokenPath)
    return when {
        name != null -> File(context.filesDir, "note_images/$name").absolutePath
        tokenPath.startsWith("file://") -> localImagePath(tokenPath)
        else -> null
    }
}

/** 图片标记路径 → 可显示的本地绝对路径（文件必须已存在）；无法解析返回 null。 */
fun resolveImageTokenPath(context: Context, tokenPath: String): String? {
    val path = imageTokenLocalPath(context, tokenPath) ?: return null
    val file = File(path)
    return if (file.isFile) file.absolutePath else null
}

/** 解析正文中所有内联图片路径（按出现顺序）。 */
fun parseImageTokens(content: String): List<String> =
    content.split("\n").mapNotNull(::imageTokenPath)

/** 把内联图片行替换为 [replacement]（连续多张图合并为一行），用于列表预览、通知等纯文本场景。 */
fun stripImageTokens(content: String, replacement: String = ""): String {
    val sb = StringBuilder()
    var pendingReplacement = false
    fun appendLine(text: String) {
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(text)
    }
    for (line in content.split("\n")) {
        if (IMAGE_LINE_REGEX.matches(line.trim())) {
            pendingReplacement = true
        } else {
            if (pendingReplacement) {
                appendLine(replacement)
                pendingReplacement = false
            }
            appendLine(line)
        }
    }
    if (pendingReplacement) appendLine(replacement)
    return sb.toString().trim()
}
